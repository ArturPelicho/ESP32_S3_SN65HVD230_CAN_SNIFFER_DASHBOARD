#pragma once

/* Speed-density fuel injection estimate.
 *
 * Pure C with no ESP-IDF, CAN or display dependencies, so it can be unit
 * tested on a PC and reused unchanged if the MCU, sensors or screen change.
 * Feed it whatever MAP / intake temperature / RPM / O2 source the bike has;
 * engine geometry and fuel properties come in through the config struct.
 *
 * Model, per cylinder and per engine cycle:
 *
 *   swept volume   Vd     = pi/4 * bore^2 * stroke
 *   air density    rho    = MAP / (R_air * T_intake)            (ideal gas)
 *   air per intake m_air  = rho * Vd * VE
 *   fuel per inj.  m_fuel = m_air / (AFR_stoich * lambda)
 *   fuel volume    V_fuel = m_fuel / rho_fuel
 *   fuel flow             = V_fuel * injections per second
 *
 * Assumptions, all estimates rather than measurements:
 *   - VE (volumetric efficiency) is a single constant. The real value moves
 *     with RPM and throttle; this is the largest error source.
 *   - One injection per cylinder per cycle (port injection).
 *   - Lambda comes from a narrowband O2 sensor, which is really a rich/lean
 *     switch. Its time-averaged voltage gives only a coarse lambda (about
 *     0.9 to 1.1); with no valid O2 reading lambda is assumed to be 1.0.
 *   - Deceleration fuel cut is not detected, so the estimate keeps showing
 *     fuel while the ECU has the injector shut. */

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    FUEL_LAMBDA_ASSUME_STOICH = 0, /* ignore O2, lambda = 1.0 */
    FUEL_LAMBDA_NARROWBAND,        /* 0-1 V switching sensor, coarse */
    FUEL_LAMBDA_WIDEBAND,          /* caller passes lambda directly */
} fuel_lambda_source_t;

typedef struct {
    /* Engine geometry. */
    float bore_mm;
    float stroke_mm;
    uint8_t cylinders;
    uint8_t strokes_per_cycle; /* 4 for four-stroke, 2 for two-stroke */

    /* Model constants. */
    float volumetric_efficiency; /* 0.0 - 1.2 */
    float stoich_afr;            /* 14.7 for petrol, about 14.1 for E10 */
    float fuel_density_g_per_ml; /* about 0.745 for petrol */
    fuel_lambda_source_t lambda_source;

    /* First-order smoothing time constants. The narrowband O2 switches
     * rich/lean about once a second, so it needs a much longer average. */
    uint32_t output_tau_ms;
    uint32_t lambda_tau_ms;
} fuel_estimator_config_t;

typedef struct {
    float map_kpa;       /* manifold absolute pressure */
    float intake_temp_c;
    float rpm;
    float o2_volts;      /* narrowband sensor voltage */
    float lambda;        /* wideband reading, FUEL_LAMBDA_WIDEBAND only */
    bool map_valid;
    bool intake_temp_valid;
    bool rpm_valid;
    bool o2_valid;
} fuel_estimator_inputs_t;

typedef struct {
    bool valid;            /* false until MAP, IAT and RPM are all valid */
    bool lambda_measured;  /* false when lambda is the assumed 1.0 */
    float displacement_cc; /* per cylinder */
    float air_density_kg_m3;
    float air_mg_per_intake;
    float lambda;
    float fuel_mg_per_injection;
    float fuel_ul_per_injection; /* microlitres = mm^3 */
    float fuel_flow_l_per_h;     /* whole engine */
} fuel_estimate_t;

typedef struct {
    fuel_estimator_config_t config;
    float displacement_m3;
    float lambda_filtered;
    float fuel_mg_filtered;
    bool lambda_primed;
    bool output_primed;
} fuel_estimator_t;

void fuel_estimator_init(fuel_estimator_t *est, const fuel_estimator_config_t *config);

/* Feeds the latest inputs; dt_ms is the time since the previous call and
 * drives the smoothing. Never blocks. Returns out->valid. */
bool fuel_estimator_update(fuel_estimator_t *est, const fuel_estimator_inputs_t *in,
                           uint32_t dt_ms, fuel_estimate_t *out);

/* Coarse lambda from a time-averaged narrowband O2 voltage. Exposed for
 * testing and for other consumers (e.g. a future BLE/log channel). */
float fuel_narrowband_lambda(float avg_volts);
