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
        .map_valid = true, .intake_temp_valid = true, .rpm_valid = true, .o2_valid = true,
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

    printf(failures ? "\n%d FAILED\n" : "\nall passed\n", failures);
    return failures ? 1 : 0;
}
