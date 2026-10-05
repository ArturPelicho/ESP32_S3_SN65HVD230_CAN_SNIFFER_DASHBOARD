/* Host-side check of the fuel estimator maths, no ESP-IDF needed:
 *   gcc -std=c11 -Wall -Wextra -I main test/host/test_fuel_estimator.c main/fuel_estimator.c -lm -o /tmp/fuel_test && /tmp/fuel_test
 */
#include <math.h>
#include <stdio.h>

#include "fuel_estimator.h"

static int failures;

static void expect_near(const char *what, float got, float want, float tol)
{
    bool ok = fabsf(got - want) <= tol;
    printf("%-34s %10.4f (want %.4f +/- %.4f) %s\n", what, got, want, tol, ok ? "ok" : "FAIL");
    if (!ok) failures++;
}

static fuel_estimator_config_t carrera_125(void)
{
    fuel_estimator_config_t cfg = {
        .bore_mm = 57.3f, .stroke_mm = 48.4f, .cylinders = 1, .strokes_per_cycle = 4,
        .volumetric_efficiency = 0.80f, .stoich_afr = 14.7f, .fuel_density_g_per_ml = 0.745f,
        .lambda_source = FUEL_LAMBDA_NARROWBAND, .output_tau_ms = 0, .lambda_tau_ms = 0,
        .moving_rpm = 2500, .moving_hysteresis_rpm = 150, .min_speed_kmh = 5,
        .cut_min_rpm = 1800, .cut_max_throttle = 2, .cut_max_o2_volts = 0.15f,
        .avg_min_distance_km = 0.5f,
    };
    return cfg;
}

int main(void)
{
    fuel_estimator_t est;
    fuel_estimate_t out;
    fuel_estimator_config_t cfg = carrera_125();
    fuel_estimator_init(&est, &cfg);

    /* Wide open throttle, 100 kPa, 25 C, 8000 rpm, O2 at 0.45 V (stoich). */
    fuel_estimator_inputs_t wot = {
        .map_kpa = 100, .intake_temp_c = 25, .rpm = 8000, .o2_volts = 0.45f,
        .throttle_pct = 100,
        .map_valid = true, .intake_temp_valid = true, .rpm_valid = true, .o2_valid = true,
        .throttle_valid = true,
    };
    fuel_estimator_update(&est, &wot, 20, &out);
    expect_near("displacement cc", out.displacement_cc, 124.81f, 0.05f);
    expect_near("air density kg/m3", out.air_density_kg_m3, 1.1687f, 0.001f);
    expect_near("air mg per intake", out.air_mg_per_intake, 116.69f, 0.2f);
    expect_near("lambda", out.lambda, 1.0f, 0.001f);
    expect_near("fuel mg per injection", out.fuel_mg_per_injection, 7.938f, 0.02f);
    expect_near("fuel ul per injection", out.fuel_ul_per_injection, 10.655f, 0.03f);
    /* 8000 rpm four-stroke = 66.67 injections/s. */
    expect_near("fuel flow L/h", out.fuel_flow_l_per_h, 2.557f, 0.01f);

    /* Idle: 35 kPa, 40 C, 1500 rpm. */
    fuel_estimator_inputs_t idle = wot;
    idle.map_kpa = 35; idle.intake_temp_c = 40; idle.rpm = 1500;
    fuel_estimator_update(&est, &idle, 20, &out);
    expect_near("idle fuel ul", out.fuel_ul_per_injection, 3.551f, 0.02f);
    expect_near("idle flow L/h", out.fuel_flow_l_per_h, 0.160f, 0.005f);

    /* Narrowband mapping and clamps. */
    expect_near("lambda at 0.80 V (rich)", fuel_narrowband_lambda(0.80f), 0.90f, 0.001f);
    expect_near("lambda at 0.10 V (lean)", fuel_narrowband_lambda(0.10f), 1.10f, 0.001f);
    expect_near("lambda clamp at 1.0 V", fuel_narrowband_lambda(1.0f), 0.90f, 0.001f);

    /* Missing MAP invalidates the estimate. */
    fuel_estimator_inputs_t no_map = idle;
    no_map.map_valid = false;
    expect_near("valid without MAP", fuel_estimator_update(&est, &no_map, 20, &out), 0, 0);

    /* Engine stopped: zero fuel. */
    fuel_estimator_inputs_t stopped = idle;
    stopped.rpm = 0;
    fuel_estimator_update(&est, &stopped, 20, &out);
    expect_near("stopped flow", out.fuel_flow_l_per_h, 0.0f, 0.0001f);

    /* Smoothing: tau 300 ms, 300 ms step moves halfway. */
    cfg.output_tau_ms = 300;
    fuel_estimator_init(&est, &cfg);
    fuel_estimator_update(&est, &idle, 20, &out);
    float idle_mg = out.fuel_mg_per_injection;
    fuel_estimator_update(&est, &wot, 300, &out);
    expect_near("smoothed step halfway", out.fuel_mg_per_injection,
                idle_mg + (7.938f - idle_mg) / 2.0f, 0.02f);

    /* Units: no speed source means L/h even at high RPM. */
    cfg = carrera_125();
    fuel_estimator_init(&est, &cfg);
    fuel_estimator_update(&est, &wot, 100, &out);
    expect_near("no speed -> L/h", out.instant_unit, FUEL_UNIT_L_PER_H, 0);
    expect_near("no speed while riding -> flagged", out.speed_missing, 1, 0);
    fuel_estimator_inputs_t zero_speed = wot;
    zero_speed.speed_kmh = 0; zero_speed.speed_valid = true; /* ECU answers 0 km/h */
    fuel_estimator_update(&est, &zero_speed, 100, &out);
    expect_near("speed 0 while riding -> L/h", out.instant_unit, FUEL_UNIT_L_PER_H, 0);
    expect_near("speed 0 while riding -> flagged", out.speed_missing, 1, 0);

    /* With speed: 2.557 L/h at 100 km/h is 2.557 L/100km. */
    fuel_estimator_inputs_t cruise = wot;
    cruise.speed_kmh = 100; cruise.speed_valid = true;
    fuel_estimator_update(&est, &cruise, 100, &out);
    expect_near("moving -> L/100km", out.instant_unit, FUEL_UNIT_L_PER_100KM, 0);
    expect_near("speed ok -> not flagged", out.speed_missing, 0, 0);
    expect_near("instant L/100km", out.l_per_100km, 2.557f, 0.01f);

    /* Hysteresis: 2400 rpm stays L/100km, 2300 switches to L/h. */
    cruise.rpm = 2400;
    fuel_estimator_update(&est, &cruise, 100, &out);
    expect_near("2400 rpm keeps L/100km", out.instant_unit, FUEL_UNIT_L_PER_100KM, 0);
    cruise.rpm = 2300;
    fuel_estimator_update(&est, &cruise, 100, &out);
    expect_near("2300 rpm -> L/h", out.instant_unit, FUEL_UNIT_L_PER_H, 0);
    expect_near("idling is not 'no speed'", out.speed_missing, 0, 0);
    cruise.rpm = 2500;
    fuel_estimator_update(&est, &cruise, 100, &out);
    expect_near("2500 rpm -> L/100km", out.instant_unit, FUEL_UNIT_L_PER_100KM, 0);

    /* Fuel cut: closed throttle, 4000 rpm, O2 at 0.05 V. */
    fuel_estimator_inputs_t brake = cruise;
    brake.rpm = 4000; brake.throttle_pct = 0; brake.o2_volts = 0.05f; brake.map_kpa = 25;
    fuel_estimator_update(&est, &brake, 100, &out);
    expect_near("fuel cut detected", out.fuel_cut, 1, 0);
    expect_near("fuel cut flow", out.fuel_flow_l_per_h, 0.0f, 0.0001f);
    expect_near("lambda frozen in cut", out.lambda, 1.0f, 0.001f);
    brake.throttle_pct = 5;
    fuel_estimator_update(&est, &brake, 100, &out);
    expect_near("throttle open -> no cut", out.fuel_cut, 0, 0);
    brake.throttle_pct = 0; brake.rpm = 1500;
    fuel_estimator_update(&est, &brake, 100, &out);
    expect_near("idle rpm -> no cut", out.fuel_cut, 0, 0);

    /* Averages: 1 h steady at 2.557 L/h and 50 km/h. */
    fuel_estimator_init(&est, &cfg);
    fuel_estimator_inputs_t steady = wot;
    steady.speed_kmh = 50; steady.speed_valid = true;
    fuel_estimator_update(&est, &steady, 100, &out);
    expect_near("avg L/h before 500 m", out.avg_unit, FUEL_UNIT_L_PER_H, 0);
    for (int i = 0; i < 36000; ++i) fuel_estimator_update(&est, &steady, 100, &out);
    expect_near("total km after 1 h", out.total_distance_km, 50.0f, 0.05f);
    expect_near("total fuel after 1 h", out.total_fuel_l, 2.557f, 0.01f);
    expect_near("avg unit L/100km", out.avg_unit, FUEL_UNIT_L_PER_100KM, 0);
    expect_near("avg L/100km", out.avg_l_per_100km, 5.113f, 0.02f);
    expect_near("avg L/h", out.avg_l_per_h, 2.557f, 0.01f);

    printf(failures ? "\n%d FAILED\n" : "\nall passed\n", failures);
    return failures ? 1 : 0;
}
