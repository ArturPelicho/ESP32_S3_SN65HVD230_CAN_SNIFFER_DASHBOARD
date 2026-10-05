#pragma once

/* Speed-density fuel consumption estimate.
 *
 * Pure C with no ESP-IDF, CAN or display dependencies, so it can be unit
 * tested on a PC and reused unchanged if the MCU, sensors or screen change.
 * Feed it whatever MAP / intake temperature / RPM / O2 / throttle / speed
 * source the bike has; engine geometry and fuel properties come in through
 * the config struct.
 *
 * Model, per cylinder and per engine cycle:
 *
 *   swept volume   Vd     = pi/4 * bore^2 * stroke
 *   air density    rho    = MAP / (R_air * T_intake)            (ideal gas)
 *   air per intake m_air  = rho * Vd * VE
 *   fuel per inj.  m_fuel = m_air / (AFR_stoich * lambda)
 *   fuel flow             = m_fuel / rho_fuel * injections per second
 *   consumption           = flow / road speed                    (L/100km)
 *
 * Assumptions, all estimates rather than measurements:
 *   - VE (volumetric efficiency) is a single constant. The real value moves
 *     with RPM and throttle; this is the largest error source, and also the
 *     calibration knob: raise it if the reading is low, lower it if high.
 *   - One injection per cylinder per cycle (port injection). Fuel rail
 *     pressure does not enter the model: it sets how long the injector must
 *     open, not how much fuel the air can burn.
 *   - Lambda comes from a narrowband O2 sensor, which is really a rich/lean
 *     switch (about 0.1 V lean, 0.45 V stoichiometric, 0.8 V rich). Its
 *     time-averaged voltage gives only a coarse lambda (0.9 to 1.1); with
 *     no valid O2 reading lambda is assumed to be 1.0.
 *   - Deceleration fuel cut is inferred: closed throttle, RPM above the
 *     cut threshold and an O2 sensor reading fully lean together mean the
 *     ECU has shut the injector, so fuel is taken as zero. */

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    FUEL_LAMBDA_ASSUME_STOICH = 0, /* ignore O2, lambda = 1.0 */
    FUEL_LAMBDA_NARROWBAND,        /* 0-1 V switching sensor, coarse */
    FUEL_LAMBDA_WIDEBAND,          /* caller passes lambda directly */
} fuel_lambda_source_t;

/* Which unit the instant reading should be shown in. Decided here, not in
 * the UI, so every display or log channel switches the same way. */
typedef enum {
    FUEL_UNIT_L_PER_H = 0,   /* stopped / idling, or no speed source */
    FUEL_UNIT_L_PER_100KM,   /* moving */
} fuel_unit_t;

typedef struct {
    /* Engine geometry. */
    float bore_mm;
    float stroke_mm;
    uint8_t cylinders;
    uint8_t strokes_per_cycle; /* 4 for four-stroke, 2 for two-stroke */

    /* Model constants. */
    float volumetric_efficiency; /* 0.0 - 1.2, also the calibration factor */
    float stoich_afr;            /* 14.7 for petrol, about 14.1 for E10 */
    float fuel_density_g_per_ml; /* about 0.745 for petrol */
    fuel_lambda_source_t lambda_source;

    /* First-order smoothing time constants. The narrowband O2 switches
     * rich/lean about once a second, so it needs a much longer average. */
    uint32_t output_tau_ms;
    uint32_t lambda_tau_ms;

    /* Unit switching: L/100km at or above moving_rpm (with a valid speed of
     * at least min_speed_kmh), L/h below moving_rpm - hysteresis_rpm. */
    float moving_rpm;
    float moving_hysteresis_rpm;
    float min_speed_kmh;

    /* Deceleration fuel cut detection. */
    float cut_min_rpm;      /* above idle */
    float cut_max_throttle; /* percent, "throttle closed" */
    float cut_max_o2_volts; /* narrowband fully lean */

    /* The since-start average switches from L/h to L/100km once this much
     * distance has been covered, so it doesn't start from a noisy value. */
    float avg_min_distance_km;
} fuel_estimator_config_t;

typedef struct {
    float map_kpa;       /* manifold absolute pressure */
    float intake_temp_c;
    float rpm;
    float o2_volts;      /* narrowband sensor voltage */
    float lambda;        /* wideband reading, FUEL_LAMBDA_WIDEBAND only */
    float throttle_pct;
    float speed_kmh;
    bool map_valid;
    bool intake_temp_valid;
    bool rpm_valid;
    bool o2_valid;
    bool throttle_valid;
    bool speed_valid;
} fuel_estimator_inputs_t;

typedef struct {
    bool valid;            /* false until MAP, IAT and RPM are all valid */
    bool lambda_measured;  /* false when lambda is the assumed 1.0 */
    bool fuel_cut;         /* ECU judged to be cutting fuel (engine braking) */
    float displacement_cc; /* per cylinder */
    float air_density_kg_m3;
    float air_mg_per_intake;
    float lambda;
    float fuel_mg_per_injection;
    float fuel_ul_per_injection; /* microlitres = mm^3 */

    /* Instant consumption, smoothed. instant_unit says which to show. */
    fuel_unit_t instant_unit;
    bool speed_missing;          /* moving by RPM, but no usable road speed */
    bool speed_valid;            /* last speed input, echoed for logging */
    float speed_kmh;
    float fuel_flow_l_per_h;     /* whole engine */
    float l_per_100km;           /* only meaningful when unit is L/100km */

    /* Since start-up (since fuel_estimator_init). avg_unit is L/100km once
     * avg_min_distance_km has been covered with a speed source. */
    fuel_unit_t avg_unit;
    float avg_l_per_h;           /* over engine running time */
    float avg_l_per_100km;
    float total_fuel_l;
    float total_distance_km;
} fuel_estimate_t;

typedef struct {
    fuel_estimator_config_t config;
    float displacement_m3;
    float lambda_filtered;
    float fuel_mg_filtered;
    float flow_filtered_lph;
    float speed_filtered_kmh;
    bool lambda_primed;
    bool output_primed;
    bool moving;
    double total_fuel_l;
    double total_distance_km;
    double running_hours;
} fuel_estimator_t;

void fuel_estimator_init(fuel_estimator_t *est, const fuel_estimator_config_t *config);

/* Feeds the latest inputs; dt_ms is the time since the previous call and
 * drives the smoothing and the since-start totals. Never blocks. Returns
 * out->valid. */
bool fuel_estimator_update(fuel_estimator_t *est, const fuel_estimator_inputs_t *in,
                           uint32_t dt_ms, fuel_estimate_t *out);

/* Coarse lambda from a time-averaged narrowband O2 voltage. Exposed for
 * testing and for other consumers (e.g. a future BLE/log channel). */
float fuel_narrowband_lambda(float avg_volts);
