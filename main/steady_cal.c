#include "steady_cal.h"

#include <math.h>
#include <string.h>

#define STEADY_CAL_KELVIN 273.15f
#define STEADY_CAL_R_AIR 287.05f
#define STEADY_CAL_MIN_RPM 200.0f
#define STEADY_CAL_MAX_GAP_S 1.0f

void steady_cal_init(steady_cal_t *cal, const steady_cal_config_t *config)
{
    memset(cal, 0, sizeof(*cal));
    cal->config = *config;
    cal->totals.version = STEADY_CAL_VERSION;
}

void steady_cal_reset(steady_cal_t *cal)
{
    steady_cal_config_t config = cal->config;
    steady_cal_init(cal, &config);
}

/* Conditions every sample of a window must meet. */
static bool usable(const steady_cal_config_t *cfg, const steady_cal_sample_t *s)
{
    return s->rpm_valid && s->map_valid && s->intake_temp_valid && s->throttle_valid &&
           s->o2_valid && s->engine_temp_valid && s->battery_valid &&
           s->rpm >= STEADY_CAL_MIN_RPM && s->pulse_ms > 0.0f && s->map_kpa > 0.0f &&
           s->engine_temp_c >= cfg->min_engine_temp_c && s->battery_v >= cfg->min_battery_v;
}

static float mean(double sum, float seconds)
{
    return (float)(sum / seconds);
}

/* Within tolerance of the window's running mean. */
static bool steady(const steady_cal_config_t *cfg, const steady_cal_window_t *w,
                   const steady_cal_sample_t *s)
{
    if (!w->active || w->seconds <= 0.0f) return true;
    float rpm = mean(w->rpm, w->seconds);
    return fabsf(s->rpm - rpm) <= cfg->rpm_tol_frac * rpm &&
           fabsf(s->throttle_pct - mean(w->throttle, w->seconds)) <= cfg->throttle_tol_pct &&
           fabsf(s->map_kpa - mean(w->map, w->seconds)) <= cfg->map_tol_kpa;
}

static void start(steady_cal_window_t *w)
{
    memset(w, 0, sizeof(*w));
    w->active = true;
    w->o2_min = 10.0f;
    w->o2_max = -10.0f;
}

static void accumulate(steady_cal_window_t *w, const steady_cal_sample_t *s, float dt_s)
{
    float x = s->map_kpa / (s->intake_temp_c + STEADY_CAL_KELVIN);
    w->seconds += dt_s;
    w->rpm += (double)s->rpm * dt_s;
    w->pulse += (double)s->pulse_ms * dt_s;
    w->x += (double)x * dt_s;
    w->map += (double)s->map_kpa * dt_s;
    w->throttle += (double)s->throttle_pct * dt_s;
    w->intake_temp += (double)s->intake_temp_c * dt_s;
    w->stft += (double)(s->trims_valid ? s->stft_pct : 0.0f) * dt_s;
    w->ltft += (double)(s->trims_valid ? s->ltft_pct : 0.0f) * dt_s;
    w->o2 += (double)s->o2_volts * dt_s;
    if (s->o2_volts < w->o2_min) w->o2_min = s->o2_volts;
    if (s->o2_volts > w->o2_max) w->o2_max = s->o2_volts;
}

/* Adds the window to its band if it is long enough and closed loop. */
static int commit(steady_cal_t *cal)
{
    steady_cal_window_t *w = &cal->window;
    const steady_cal_config_t *cfg = &cal->config;
    w->active = false;
    if (w->seconds < cfg->min_window_s) return -1;
    if (w->o2_min > cfg->o2_low_v || w->o2_max < cfg->o2_high_v) return -1;

    float t = w->seconds;
    int bin = (int)(mean(w->rpm, t) / STEADY_CAL_BIN_RPM);
    if (bin < 0) bin = 0;
    if (bin >= STEADY_CAL_BINS) bin = STEADY_CAL_BINS - 1;
    double x = w->x / t;
    double y = w->pulse / t;
    steady_cal_bin_t *b = &cal->totals.bins[bin];
    b->seconds += t;
    b->windows += 1;
    b->sx += t * x;
    b->sy += t * y;
    b->sxx += t * x * x;
    b->sxy += t * x * y;
    b->rpm += w->rpm;
    b->throttle += w->throttle;
    b->map += w->map;
    b->intake_temp += w->intake_temp;
    b->stft += w->stft;
    b->ltft += w->ltft;
    b->o2 += w->o2;
    return bin;
}

int steady_cal_add(steady_cal_t *cal, const steady_cal_sample_t *s, float dt_s)
{
    const steady_cal_config_t *cfg = &cal->config;
    steady_cal_window_t *w = &cal->window;
    int kept = -1;

    bool ok = usable(cfg, s) && dt_s > 0.0f && dt_s <= STEADY_CAL_MAX_GAP_S;
    if (w->active && (!ok || !steady(cfg, w, s))) {
        kept = commit(cal);
    }
    if (!ok) return kept;
    if (!w->active) start(w);
    accumulate(w, s, dt_s);
    if (w->seconds >= cfg->max_window_s) {
        int bin = commit(cal);
        if (bin >= 0) kept = bin;
    }
    return kept;
}

bool steady_cal_band(const steady_cal_totals_t *totals, int bin, steady_cal_band_t *out)
{
    if (bin < 0 || bin >= STEADY_CAL_BINS) return false;
    const steady_cal_bin_t *b = &totals->bins[bin];
    if (b->seconds <= 0.0) return false;
    float t = (float)b->seconds;
    out->seconds = t;
    out->windows = b->windows;
    out->rpm = mean(b->rpm, t);
    out->pulse_ms = mean(b->sy, t);
    out->x = mean(b->sx, t);
    out->map_kpa = mean(b->map, t);
    out->intake_temp_c = mean(b->intake_temp, t);
    out->throttle_pct = mean(b->throttle, t);
    out->stft_pct = mean(b->stft, t);
    out->ltft_pct = mean(b->ltft, t);
    out->o2_volts = mean(b->o2, t);
    return true;
}

/* Least squares with one slope per band and a shared intercept d.
 * Minimising each band's residual over its slope leaves, per band,
 *   RSS(d) = Syy - 2 d Sy + d^2 W - (Sxy - d Sx)^2 / Sxx
 * and summing d/dRSS = 0 over bands gives
 *   d = sum(Sy - Sx Sxy / Sxx) / sum(W - Sx^2 / Sxx). */
bool steady_cal_dead_time(const steady_cal_totals_t *totals, float *dead_ms, float *basis)
{
    double num = 0.0, den = 0.0;
    for (int i = 0; i < STEADY_CAL_BINS; ++i) {
        const steady_cal_bin_t *b = &totals->bins[i];
        if (b->windows < 2 || b->sxx <= 0.0) continue;
        num += b->sy - b->sx * b->sxy / b->sxx;
        den += b->seconds - b->sx * b->sx / b->sxx;
    }
    *basis = (float)den;
    if (den <= 1e-9) return false;
    *dead_ms = (float)(num / den);
    return true;
}

bool steady_cal_band_flow(const steady_cal_band_t *band, const steady_cal_engine_t *e,
                          float *flow_cc_per_min)
{
    float effective_ms = band->pulse_ms - e->dead_time_ms;
    if (effective_ms <= 0.0f || e->stoich_afr <= 0.0f || e->fuel_density_g_per_ml <= 0.0f) {
        return false;
    }
    /* x is kPa/K: density = x * 1000 / R, kg/m^3 == mg/cc. */
    float air_mg = band->x * 1000.0f / STEADY_CAL_R_AIR * e->displacement_cc *
                   e->volumetric_efficiency;
    float fuel_ul = air_mg / e->stoich_afr / e->fuel_density_g_per_ml;
    /* ul per ms of open injector = ml/s; x 60 = cc/min. */
    *flow_cc_per_min = fuel_ul / effective_ms * 60.0f;
    return true;
}
