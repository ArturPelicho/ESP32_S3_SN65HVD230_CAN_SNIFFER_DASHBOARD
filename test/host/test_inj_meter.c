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
    const inj_meter_config_t cfg = {
        .flow_cc_per_min = 90.0f, .dead_time_ms = 0.6f, .cylinders = 1, .strokes_per_cycle = 4,
    };

    /* Artur's usual ride: ~8200 rpm, 6.3 ms pulse. 68.3 injections/s of
     * 5.7 ms effective at 1.5 ul/ms is 2.1 L/h, i.e. 2.5 L per 100 km at
     * 85 km/h. */
    float lph = inj_meter_flow_lph(&cfg, 8200.0f, 6.3f);
    assert(near(lph, 2.10, 0.02));

    /* Fuel cut, pulse shorter than dead time, engine stopped. */
    assert(inj_meter_flow_lph(&cfg, 4700.0f, 0.0f) == 0.0f);
    assert(inj_meter_flow_lph(&cfg, 1500.0f, 0.4f) == 0.0f);
    assert(inj_meter_flow_lph(&cfg, 0.0f, 2.2f) == 0.0f);

    /* One hour of that in 9 ms frames: totals give the same litres as the
     * instant flow, and keep the raw sums for refitting. */
    inj_meter_totals_t t = { 0 };
    for (int i = 0; i < 400000; ++i) inj_meter_add(&cfg, &t, 8200.0f, 6.3f, 9.0f);
    assert(near(t.running_s, 3600.0, 0.01));
    assert(near(t.injections, 8200.0 / 120.0 * 3600.0, 1.0));
    assert(near(t.open_ms, t.injections * 6.3, 10.0));
    assert(near(inj_meter_litres(&cfg, &t), lph, 0.001));

    /* Fuel cut frames add running time but no injections; long gaps
     * (bus loss, key off) add nothing. */
    inj_meter_totals_t before = t;
    inj_meter_add(&cfg, &t, 4700.0f, 0.0f, 9.0f);
    assert(t.injections == before.injections && t.running_s > before.running_s);
    before = t;
    inj_meter_add(&cfg, &t, 8200.0f, 6.3f, 5000.0f);
    assert(t.injections == before.injections && t.running_s == before.running_s);

    /* Refitting: same sums, new flow, proportionally new litres. */
    inj_meter_config_t refit = cfg;
    refit.flow_cc_per_min = 99.0f;
    assert(near(inj_meter_litres(&refit, &t), inj_meter_litres(&cfg, &t) * 1.1, 0.001));

    puts("inj_meter: all tests passed");
    return 0;
}
