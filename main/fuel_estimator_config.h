#pragma once

/* Builds a fuel_estimator_config_t from menuconfig ("Fuel injection
 * estimate" menu, main/Kconfig.projbuild). Kept apart from fuel_estimator.c
 * so the estimator itself stays free of ESP-IDF headers. To support another
 * engine, change the values in menuconfig; no code changes needed. */

#include "sdkconfig.h"
#include "fuel_estimator.h"

#ifndef CONFIG_FUEL_BORE_UM
#define CONFIG_FUEL_BORE_UM 57300
#endif
#ifndef CONFIG_FUEL_STROKE_UM
#define CONFIG_FUEL_STROKE_UM 48400
#endif
#ifndef CONFIG_FUEL_CYLINDERS
#define CONFIG_FUEL_CYLINDERS 1
#endif
#ifndef CONFIG_FUEL_STROKES_PER_CYCLE
#define CONFIG_FUEL_STROKES_PER_CYCLE 4
#endif
#ifndef CONFIG_FUEL_VE_PERCENT
#define CONFIG_FUEL_VE_PERCENT 80
#endif
#ifndef CONFIG_FUEL_STOICH_AFR_X100
#define CONFIG_FUEL_STOICH_AFR_X100 1470
#endif
#ifndef CONFIG_FUEL_DENSITY_G_PER_L
#define CONFIG_FUEL_DENSITY_G_PER_L 745
#endif
#ifndef CONFIG_FUEL_OUTPUT_TAU_MS
#define CONFIG_FUEL_OUTPUT_TAU_MS 300
#endif
#ifndef CONFIG_FUEL_LAMBDA_TAU_MS
#define CONFIG_FUEL_LAMBDA_TAU_MS 3000
#endif
#ifndef CONFIG_FUEL_MOVING_RPM
#define CONFIG_FUEL_MOVING_RPM 2500
#endif
#ifndef CONFIG_FUEL_MOVING_HYSTERESIS_RPM
#define CONFIG_FUEL_MOVING_HYSTERESIS_RPM 150
#endif
#ifndef CONFIG_FUEL_MIN_SPEED_KMH
#define CONFIG_FUEL_MIN_SPEED_KMH 5
#endif
#ifndef CONFIG_FUEL_AVG_MIN_DISTANCE_M
#define CONFIG_FUEL_AVG_MIN_DISTANCE_M 500
#endif
#ifndef CONFIG_FUEL_CUT_MIN_RPM
#define CONFIG_FUEL_CUT_MIN_RPM 1800
#endif
#ifndef CONFIG_FUEL_CUT_MAX_THROTTLE_PCT
#define CONFIG_FUEL_CUT_MAX_THROTTLE_PCT 2
#endif
#ifndef CONFIG_FUEL_CUT_MAX_O2_MV
#define CONFIG_FUEL_CUT_MAX_O2_MV 150
#endif
#if !defined(CONFIG_FUEL_LAMBDA_NARROWBAND) && !defined(CONFIG_FUEL_LAMBDA_ASSUME_STOICH)
#define CONFIG_FUEL_LAMBDA_NARROWBAND 1
#endif

static inline fuel_estimator_config_t fuel_estimator_config_from_kconfig(void)
{
    fuel_estimator_config_t cfg = {
        .bore_mm = CONFIG_FUEL_BORE_UM / 1000.0f,
        .stroke_mm = CONFIG_FUEL_STROKE_UM / 1000.0f,
        .cylinders = CONFIG_FUEL_CYLINDERS,
        .strokes_per_cycle = CONFIG_FUEL_STROKES_PER_CYCLE,
        .volumetric_efficiency = CONFIG_FUEL_VE_PERCENT / 100.0f,
        .stoich_afr = CONFIG_FUEL_STOICH_AFR_X100 / 100.0f,
        .fuel_density_g_per_ml = CONFIG_FUEL_DENSITY_G_PER_L / 1000.0f,
#ifdef CONFIG_FUEL_LAMBDA_NARROWBAND
        .lambda_source = FUEL_LAMBDA_NARROWBAND,
#else
        .lambda_source = FUEL_LAMBDA_ASSUME_STOICH,
#endif
        .output_tau_ms = CONFIG_FUEL_OUTPUT_TAU_MS,
        .lambda_tau_ms = CONFIG_FUEL_LAMBDA_TAU_MS,
        .moving_rpm = CONFIG_FUEL_MOVING_RPM,
        .moving_hysteresis_rpm = CONFIG_FUEL_MOVING_HYSTERESIS_RPM,
        .min_speed_kmh = CONFIG_FUEL_MIN_SPEED_KMH,
        .cut_min_rpm = CONFIG_FUEL_CUT_MIN_RPM,
        .cut_max_throttle = CONFIG_FUEL_CUT_MAX_THROTTLE_PCT,
        .cut_max_o2_volts = CONFIG_FUEL_CUT_MAX_O2_MV / 1000.0f,
        .avg_min_distance_km = CONFIG_FUEL_AVG_MIN_DISTANCE_M / 1000.0f,
    };
    return cfg;
}
