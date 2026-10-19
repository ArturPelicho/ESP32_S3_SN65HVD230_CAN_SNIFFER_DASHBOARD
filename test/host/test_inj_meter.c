/* Host test for main/inj_meter.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_inj_meter.c main/inj_meter.c
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "inj_meter.h"

static int near(double a, double b, double tol)
{
    return fabs(a - b) <= tol;
}

int main(void)
{
    const inj_meter_config_t cfg = { .lph_per_unit = 0.18f, .cylinders = 1, .strokes_per_cycle = 4 };

    /* The value is a rate: 13.2 units -> 2.38 L/h whatever the rpm. */
    assert(near(inj_meter_flow_lph(&cfg, 8362.0f, 13.22f), 2.380, 0.001));
    assert(near(inj_meter_flow_lph(&cfg, 1550.0f, 1.25f), 0.225, 0.001));

    /* Fuel cut and engine stopped. */
    assert(inj_meter_flow_lph(&cfg, 4700.0f, 0.0f) == 0.0f);
    assert(inj_meter_flow_lph(&cfg, 0.0f, 2.2f) == 0.0f);

    /* One hour at 10 units in 9 ms frames: 1.8 L. */
    inj_meter_totals_t t = { 0 };
    for (int i = 0; i < 400000; ++i) inj_meter_add(&cfg, &t, 7400.0f, 10.0f, 9.0f);
    assert(near(t.running_s, 3600.0, 0.01));
    assert(near(t.value_seconds, 36000.0, 0.1));
    assert(near(t.injections, 7400.0 / 120.0 * 3600.0, 1.0));
    assert(near(inj_meter_litres(&cfg, &t), 1.8, 0.0001));

    /* Fuel cut frames add running time but no fuel; long gaps (bus loss,
     * key off) add nothing. */
    inj_meter_totals_t before = t;
    inj_meter_add(&cfg, &t, 4700.0f, 0.0f, 9.0f);
    assert(t.value_seconds == before.value_seconds && t.running_s > before.running_s);
    before = t;
    inj_meter_add(&cfg, &t, 7400.0f, 10.0f, 5000.0f);
    assert(t.value_seconds == before.value_seconds && t.running_s == before.running_s);

    /* Refitting: same sums, new constant, proportionally new litres. */
    inj_meter_config_t refit = cfg;
    refit.lph_per_unit = 0.198f;
    assert(near(inj_meter_litres(&refit, &t), inj_meter_litres(&cfg, &t) * 1.1, 0.0001));

    puts("inj_meter: all tests passed");
    return 0;
}
