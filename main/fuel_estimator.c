#include "fuel_estimator.h"

#include <stddef.h>

#define FUEL_PI 3.14159265f
#define FUEL_R_AIR 287.05f         /* J/(kg*K), dry air */
#define FUEL_KELVIN_OFFSET 273.15f
#define FUEL_MIN_RPM 200.0f        /* below this the engine is not running */
#define FUEL_MAX_DT_MS 1000        /* longer gaps are not integrated */

void fuel_estimator_init(fuel_estimator_t *est, const fuel_estimator_config_t *config)
{
    est->config = *config;
    float bore_m = config->bore_mm / 1000.0f;
    float stroke_m = config->stroke_mm / 1000.0f;
    est->displacement_m3 = FUEL_PI / 4.0f * bore_m * bore_m * stroke_m;
    est->lambda_filtered = 1.0f;
    est->fuel_mg_filtered = 0.0f;
    est->flow_filtered_lph = 0.0f;
    est->speed_filtered_kmh = 0.0f;
    est->lambda_primed = false;
    est->output_primed = false;
    est->moving = false;
    est->total_fuel_l = 0.0;
    est->total_distance_km = 0.0;
    est->running_hours = 0.0;
}

/* Narrowband sensors sit near 0.45 V at stoichiometry and swing to about
 * 0.1 V lean / 0.8 V rich. Averaged over the ECU's closed-loop switching
 * this maps, roughly linearly, onto lambda 1.10 .. 0.90. Outside that the
 * sensor saturates, so the result is clamped rather than extrapolated. */
float fuel_narrowband_lambda(float avg_volts)
{
    float lambda = 1.0f - (avg_volts - 0.45f) * (0.10f / 0.35f);
    if (lambda < 0.90f) lambda = 0.90f;
    if (lambda > 1.10f) lambda = 1.10f;
    return lambda;
}

/* First-order low-pass with alpha derived from the real time step, so the
 * smoothing is the same whatever rate the caller runs at. */
static float smooth(float previous, float sample, uint32_t dt_ms, uint32_t tau_ms)
{
    if (tau_ms == 0) return sample;
    float alpha = (float)dt_ms / (float)(tau_ms + dt_ms);
    return previous + alpha * (sample - previous);
}

static bool detect_fuel_cut(const fuel_estimator_config_t *cfg, const fuel_estimator_inputs_t *in)
{
    return in->rpm_valid && in->throttle_valid && in->o2_valid &&
           in->rpm >= cfg->cut_min_rpm &&
           in->throttle_pct <= cfg->cut_max_throttle &&
           in->o2_volts <= cfg->cut_max_o2_volts;
}

static void fill_averages(const fuel_estimator_t *est, fuel_estimate_t *out)
{
    out->total_fuel_l = (float)est->total_fuel_l;
    out->total_distance_km = (float)est->total_distance_km;
    out->avg_l_per_h = est->running_hours > 0.0
                           ? (float)(est->total_fuel_l / est->running_hours) : 0.0f;
    if (est->total_distance_km >= est->config.avg_min_distance_km &&
        est->total_distance_km > 0.0) {
        out->avg_unit = FUEL_UNIT_L_PER_100KM;
        out->avg_l_per_100km = (float)(est->total_fuel_l / est->total_distance_km * 100.0);
    } else {
        out->avg_unit = FUEL_UNIT_L_PER_H;
        out->avg_l_per_100km = 0.0f;
    }
}

bool fuel_estimator_update(fuel_estimator_t *est, const fuel_estimator_inputs_t *in,
                           uint32_t dt_ms, fuel_estimate_t *out)
{
    const fuel_estimator_config_t *cfg = &est->config;
    uint32_t integrate_ms = dt_ms > FUEL_MAX_DT_MS ? 0 : dt_ms;

    out->displacement_cc = est->displacement_m3 * 1e6f;
    bool measured = in->measured_flow_valid && in->measured_flow_lph >= 0.0f;
    out->flow_measured = measured;
    out->fuel_cut = measured ? (in->rpm_valid && in->rpm >= FUEL_MIN_RPM &&
                                in->measured_flow_lph <= 0.0f)
                             : detect_fuel_cut(cfg, in);

    /* Lambda keeps filtering even when the other inputs are missing, so it
     * is already settled when they come back. During a fuel cut the sensor
     * reads "no fuel at all", which says nothing about the mixture, so the
     * average is frozen instead of being dragged lean. */
    float lambda_sample = 1.0f;
    bool lambda_measured = false;
    if (cfg->lambda_source == FUEL_LAMBDA_NARROWBAND && in->o2_valid) {
        lambda_sample = fuel_narrowband_lambda(in->o2_volts);
        lambda_measured = true;
    } else if (cfg->lambda_source == FUEL_LAMBDA_WIDEBAND && in->o2_valid &&
               in->lambda > 0.5f && in->lambda < 2.0f) {
        lambda_sample = in->lambda;
        lambda_measured = true;
    }
    if (!est->lambda_primed) {
        est->lambda_filtered = lambda_sample;
        est->lambda_primed = true;
    } else if (!out->fuel_cut) {
        est->lambda_filtered = smooth(est->lambda_filtered, lambda_sample, dt_ms,
                                      cfg->lambda_tau_ms);
    }
    out->lambda = est->lambda_filtered;
    out->lambda_measured = lambda_measured;

    /* Distance counts whenever speed is known, even if the fuel inputs
     * briefly drop out, so the average's denominator stays honest. */
    bool speed_ok = in->speed_valid && in->speed_kmh >= 0.0f;
    out->speed_valid = in->speed_valid;
    out->speed_kmh = in->speed_kmh;
    out->speed_missing = false;
    if (speed_ok) {
        est->total_distance_km += (double)in->speed_kmh * integrate_ms / 3.6e6;
        est->speed_filtered_kmh = smooth(est->speed_filtered_kmh, in->speed_kmh, dt_ms,
                                         cfg->output_tau_ms);
    }

    bool model_ok = in->map_valid && in->intake_temp_valid && in->map_kpa > 0.0f;
    if (!in->rpm_valid || (!measured && !model_ok)) {
        out->valid = false;
        est->output_primed = false;
        fill_averages(est, out);
        return false;
    }

    float air_mg = 0.0f;
    float density = 0.0f;
    if (model_ok) {
        float temp_k = in->intake_temp_c + FUEL_KELVIN_OFFSET;
        if (temp_k < 200.0f) temp_k = 200.0f;
        density = (in->map_kpa * 1000.0f) / (FUEL_R_AIR * temp_k);
        air_mg = density * est->displacement_m3 * cfg->volumetric_efficiency * 1e6f;
    }
    out->air_density_kg_m3 = density;
    out->air_mg_per_intake = air_mg;

    bool running = in->rpm >= FUEL_MIN_RPM;
    float cycles_per_s = running ? in->rpm / 60.0f / ((float)cfg->strokes_per_cycle / 2.0f)
                                 : 0.0f;
    float injections_per_s = cycles_per_s * (float)cfg->cylinders;
    float fuel_mg;
    float flow_lph;
    if (measured) {
        flow_lph = running ? in->measured_flow_lph : 0.0f;
        /* Back out the per-injection amount for display and logging. */
        fuel_mg = injections_per_s > 0.0f
                      ? flow_lph / 3600.0f / injections_per_s * 1e6f * cfg->fuel_density_g_per_ml
                      : 0.0f;
    } else {
        fuel_mg = (running && !out->fuel_cut)
                      ? air_mg / (cfg->stoich_afr * est->lambda_filtered) : 0.0f;
        /* g/ml == mg/ul, so mg / (g/ml) gives microlitres directly. */
        flow_lph = fuel_mg / cfg->fuel_density_g_per_ml * injections_per_s * 3600.0f * 1e-6f;
    }

    /* Totals use the unsmoothed flow so the average is exact. */
    est->total_fuel_l += (double)flow_lph * integrate_ms / 3.6e6;
    if (running) est->running_hours += (double)integrate_ms / 3.6e6;

    /* A fuel cut drops the reading at once rather than easing down. */
    if (!est->output_primed || out->fuel_cut) {
        est->fuel_mg_filtered = fuel_mg;
        est->flow_filtered_lph = flow_lph;
        est->output_primed = true;
    } else {
        est->fuel_mg_filtered = smooth(est->fuel_mg_filtered, fuel_mg, dt_ms, cfg->output_tau_ms);
        est->flow_filtered_lph = smooth(est->flow_filtered_lph, flow_lph, dt_ms, cfg->output_tau_ms);
    }
    out->fuel_mg_per_injection = est->fuel_mg_filtered;
    out->fuel_ul_per_injection = est->fuel_mg_filtered / cfg->fuel_density_g_per_ml;
    out->fuel_flow_l_per_h = est->flow_filtered_lph;

    /* Unit switching with a small hysteresis band so cruising right at the
     * threshold doesn't make the unit flicker. */
    if (in->rpm >= cfg->moving_rpm) {
        est->moving = true;
    } else if (in->rpm < cfg->moving_rpm - cfg->moving_hysteresis_rpm) {
        est->moving = false;
    }
    bool per_distance = est->moving && speed_ok && in->speed_kmh >= cfg->min_speed_kmh &&
                        est->speed_filtered_kmh > 0.0f;
    out->instant_unit = per_distance ? FUEL_UNIT_L_PER_100KM : FUEL_UNIT_L_PER_H;
    /* Riding (by RPM) yet no speed to divide by: tell the display why it
     * is still showing L/h. */
    out->speed_missing = est->moving && !per_distance;
    out->l_per_100km = per_distance ? est->flow_filtered_lph / est->speed_filtered_kmh * 100.0f
                                    : 0.0f;

    fill_averages(est, out);
    out->valid = true;
    return true;
}
