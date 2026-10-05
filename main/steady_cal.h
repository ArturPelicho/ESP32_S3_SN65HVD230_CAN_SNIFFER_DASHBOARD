#pragma once

/* Injector calibration from steady riding.
 *
 * Nothing on the bike measures fuel or air mass directly, so the injector
 * flow cannot be found from sensors alone. What steady riding does give,
 * with the ECU in closed loop (lambda ~1):
 *
 *   fuel per injection  ~  air per intake  ~  VE * MAP / T_intake
 *   pulse = dead + fuel / flow  =  dead + k * MAP / T_intake,  k ~ VE / flow
 *
 * So, per 1000 rpm band (where VE changes little):
 *   - the pulse actually needed at each steady operating point;
 *   - the dead time, from where pulse vs MAP/T crosses zero, pooled over
 *     the bands (needs some load variation within a band: hills help);
 *   - a flow estimate per band assuming a VE (the same constant as the
 *     speed-density model), which a known fuel figure can later replace.
 *
 * A "window" is a stretch of at least min_window_s where RPM, throttle
 * and MAP stay within tolerance of their running mean, the engine is
 * warm, the battery is charging, the injector never cut and the O2 sensor
 * switched across stoichiometry (closed loop). Each window adds one
 * weighted point (weight = its duration) to its RPM band. Only sums are
 * kept, so the state is small, fixed and can be persisted as is.
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stdbool.h>
#include <stdint.h>

#define STEADY_CAL_BINS 11          /* 1000 rpm each: 0-999 ... 10000+ */
#define STEADY_CAL_BIN_RPM 1000
#define STEADY_CAL_VERSION 1

typedef struct {
    float rpm_tol_frac;      /* e.g. 0.05: +-5 % of the running mean */
    float throttle_tol_pct;  /* percentage points */
    float map_tol_kpa;
    float min_window_s;
    float max_window_s;      /* long cruises are cut into several points */
    float min_engine_temp_c;
    float min_battery_v;
    float o2_low_v;          /* the window must see the O2 below this ... */
    float o2_high_v;         /* ... and above this: closed-loop switching */
} steady_cal_config_t;

#define STEADY_CAL_DEFAULT_CONFIG() { \
    .rpm_tol_frac = 0.05f,            \
    .throttle_tol_pct = 2.0f,         \
    .map_tol_kpa = 3.0f,              \
    .min_window_s = 10.0f,            \
    .max_window_s = 30.0f,            \
    .min_engine_temp_c = 70.0f,       \
    .min_battery_v = 12.5f,           \
    .o2_low_v = 0.35f,                \
    .o2_high_v = 0.55f,               \
}

typedef struct {
    float rpm;
    float pulse_ms;
    float map_kpa;
    float intake_temp_c;
    float throttle_pct;
    float o2_volts;
    float stft_pct;
    float ltft_pct;
    float engine_temp_c;
    float battery_v;
    bool rpm_valid;      /* rpm and pulse come together from the broadcast */
    bool map_valid;
    bool intake_temp_valid;
    bool throttle_valid;
    bool o2_valid;       /* false while the sensor is cold (0xCA) */
    bool trims_valid;
    bool engine_temp_valid;
    bool battery_valid;
} steady_cal_sample_t;

/* One RPM band. Weighted sums over windows, weight = seconds;
 * x = MAP / T_intake in kPa/K, y = pulse in ms. */
typedef struct {
    double seconds;
    uint32_t windows;
    uint32_t reserved;
    double sx, sy, sxx, sxy;
    double rpm, throttle, map, intake_temp, stft, ltft, o2;
} steady_cal_bin_t;

/* Everything that is persisted. */
typedef struct {
    uint32_t version;
    uint32_t reserved;
    steady_cal_bin_t bins[STEADY_CAL_BINS];
} steady_cal_totals_t;

/* The window being built. */
typedef struct {
    bool active;
    float seconds;
    double rpm, pulse, x, map, throttle, intake_temp, stft, ltft, o2;
    float o2_min, o2_max;
} steady_cal_window_t;

typedef struct {
    steady_cal_config_t config;
    steady_cal_totals_t totals;
    steady_cal_window_t window;
} steady_cal_t;

void steady_cal_init(steady_cal_t *cal, const steady_cal_config_t *config);

/* Clears the totals (keeps the config). */
void steady_cal_reset(steady_cal_t *cal);

/* Feeds one sample taken dt_s after the previous one. Returns the band
 * index when this sample closed a window that was kept, otherwise -1. */
int steady_cal_add(steady_cal_t *cal, const steady_cal_sample_t *sample, float dt_s);

/* Per-band means. Returns false for an empty band. */
typedef struct {
    float seconds;
    uint32_t windows;
    float rpm, pulse_ms, map_kpa, intake_temp_c, throttle_pct, stft_pct, ltft_pct, o2_volts;
    float x;              /* mean MAP / T_intake, kPa/K */
} steady_cal_band_t;
bool steady_cal_band(const steady_cal_totals_t *totals, int bin, steady_cal_band_t *out);

/* Dead time where pulse vs MAP/T crosses zero, with one slope per band and
 * one shared intercept. *basis is the weight of the load variation it
 * rests on (seconds x relative variance of MAP/T); below a few units the
 * value means little. Returns false when there is no variation at all. */
bool steady_cal_dead_time(const steady_cal_totals_t *totals, float *dead_ms, float *basis);

/* Injector flow (cc/min) the band implies, given the swept volume, an
 * assumed VE, the stoichiometric AFR, fuel density and dead time. */
typedef struct {
    float displacement_cc;
    float volumetric_efficiency;
    float stoich_afr;
    float fuel_density_g_per_ml;
    float dead_time_ms;
} steady_cal_engine_t;
bool steady_cal_band_flow(const steady_cal_band_t *band, const steady_cal_engine_t *engine,
                          float *flow_cc_per_min);
