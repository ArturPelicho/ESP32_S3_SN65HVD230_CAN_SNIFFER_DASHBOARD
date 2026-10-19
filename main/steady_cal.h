#pragma once

/* Fuel model check from steady riding.
 *
 * Nothing on the bike measures fuel or air mass directly. What steady
 * riding with the ECU in closed loop (lambda ~1) does give, per 1000 rpm
 * band: the ECU's injection value, MAP, intake temperature, trims and O2
 * at that operating point. With the fuel the injection value stands for
 * (inj_meter.h) and air = VE * MAP/(R*T) * displacement, lambda 1 gives the
 * VE each band implies. A smooth, plausible VE curve across bands says the
 * fuel model has the right shape; its overall level says whether the fuel
 * constant is high or low (VE scales 1:1 with it).
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
    float inj_value;     /* ECU injection value (inj_meter.h), 0 on fuel cut */
    float map_kpa;
    float intake_temp_c;
    float throttle_pct;
    float o2_volts;
    float stft_pct;
    float ltft_pct;
    float engine_temp_c;
    float battery_v;
    bool rpm_valid;      /* rpm and inj_value come together from the broadcast */
    bool map_valid;
    bool intake_temp_valid;
    bool throttle_valid;
    bool o2_valid;       /* false while the sensor is cold (0xCA) */
    bool trims_valid;
    bool engine_temp_valid;
    bool battery_valid;
} steady_cal_sample_t;

/* One RPM band. Weighted sums over windows, weight = seconds;
 * x = MAP / T_intake in kPa/K, y = injection value. */
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
    double rpm, inj, x, map, throttle, intake_temp, stft, ltft, o2;
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
    float rpm, inj_value, map_kpa, intake_temp_c, throttle_pct, stft_pct, ltft_pct, o2_volts;
    float x;              /* mean MAP / T_intake, kPa/K */
} steady_cal_band_t;
bool steady_cal_band(const steady_cal_totals_t *totals, int bin, steady_cal_band_t *out);

/* Volumetric efficiency the band implies at lambda 1: air needed for the
 * band's fuel, over the air the swept volume holds at its MAP and intake
 * temperature. Returns false when there is no fuel or no air data. */
typedef struct {
    float displacement_cc;
    float stoich_afr;
    float fuel_density_g_per_ml;
    float lph_per_unit;       /* fuel per unit of injection value, inj_meter.h */
    uint8_t cylinders;
    uint8_t strokes_per_cycle;
} steady_cal_engine_t;
bool steady_cal_band_ve(const steady_cal_band_t *band, const steady_cal_engine_t *engine,
                        float *ve);
