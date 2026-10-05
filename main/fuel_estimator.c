#include "fuel_estimator.h"

#include <stddef.h>

#define FUEL_PI 3.14159265f
#define FUEL_R_AIR 287.05f         /* J/(kg*K), dry air */
#define FUEL_KELVIN_OFFSET 273.15f
#define FUEL_MIN_RPM 200.0f        /* below this the engine is not running */

void fuel_estimator_init(fuel_estimator_t *est, const fuel_estimator_config_t *config)
{
    est->config = *config;
    float bore_m = config->bore_mm / 1000.0f;
    float stroke_m = config->stroke_mm / 1000.0f;
    est->displacement_m3 = FUEL_PI / 4.0f * bore_m * bore_m * stroke_m;
    est->lambda_filtered = 1.0f;
    est->fuel_mg_filtered = 0.0f;
    est->lambda_primed = false;
    est->output_primed = false;
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

bool fuel_estimator_update(fuel_estimator_t *est, const fuel_estimator_inputs_t *in,
                           uint32_t dt_ms, fuel_estimate_t *out)
{
    const fuel_estimator_config_t *cfg = &est->config;

    out->displacement_cc = est->displacement_m3 * 1e6f;

    /* Lambda keeps filtering even when the other inputs are missing, so it
     * is already settled when they come back. */
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
    } else {
        est->lambda_filtered = smooth(est->lambda_filtered, lambda_sample, dt_ms,
                                      cfg->lambda_tau_ms);
    }
    out->lambda = est->lambda_filtered;
    out->lambda_measured = lambda_measured;

    if (!in->map_valid || !in->intake_temp_valid || !in->rpm_valid ||
        in->map_kpa <= 0.0f) {
        out->valid = false;
        est->output_primed = false;
        return false;
    }

    float temp_k = in->intake_temp_c + FUEL_KELVIN_OFFSET;
    if (temp_k < 200.0f) temp_k = 200.0f;
    float density = (in->map_kpa * 1000.0f) / (FUEL_R_AIR * temp_k);
    float air_mg = density * est->displacement_m3 * cfg->volumetric_efficiency * 1e6f;
    out->air_density_kg_m3 = density;
    out->air_mg_per_intake = air_mg;

    bool running = in->rpm >= FUEL_MIN_RPM;
    float fuel_mg = running ? air_mg / (cfg->stoich_afr * est->lambda_filtered) : 0.0f;
    if (!est->output_primed) {
        est->fuel_mg_filtered = fuel_mg;
        est->output_primed = true;
    } else {
        est->fuel_mg_filtered = smooth(est->fuel_mg_filtered, fuel_mg, dt_ms,
                                       cfg->output_tau_ms);
    }

    /* g/ml == mg/ul, so mg / (g/ml) gives microlitres directly. */
    out->fuel_mg_per_injection = est->fuel_mg_filtered;
    out->fuel_ul_per_injection = est->fuel_mg_filtered / cfg->fuel_density_g_per_ml;

    float cycles_per_s = running ? in->rpm / 60.0f / ((float)cfg->strokes_per_cycle / 2.0f)
                                 : 0.0f;
    float injections_per_s = cycles_per_s * (float)cfg->cylinders;
    out->fuel_flow_l_per_h = out->fuel_ul_per_injection * injections_per_s * 3600.0f * 1e-6f;

    out->valid = true;
    return true;
}
