/* Host test for main/speed_source.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_speed_source.c main/speed_source.c
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "speed_source.h"

static int near(double a, double b, double tol) { return fabs(a - b) <= tol; }

int main(void)
{
    speed_sources_t s;
    speed_sources_init(&s);
    speed_select_t sel;
    speed_select_init(&sel);
    speed_select_config_t cfg = { .primary = SPEED_SRC_GPS_EXT, .hold_max_ms = 60000, .avg_tau_s = 20.0f };
    float v;
    speed_origin_t o;

    /* Nothing published. */
    assert(!speed_select_update(&sel, &cfg, &s, 0, 0.1f, true, &v, &o) && o == SPEED_ORIGIN_NONE);

    /* Phone GPS at 1 Hz, 90 km/h for a minute. */
    uint32_t t = 0;
    for (; t < 60000; t += 100) {
        if (t % 1000 == 0) speed_sources_publish(&s, SPEED_SRC_GPS_EXT, 90.0f, 0.5f, t);
        assert(speed_select_update(&sel, &cfg, &s, t, 0.1f, true, &v, &o));
        assert(o == SPEED_ORIGIN_SOURCE && v == 90.0f);
    }
    /* Other sources stay apart and visible. */
    speed_sources_publish(&s, SPEED_SRC_OBD, 0.0f, -1.0f, t);
    assert(speed_sources_fresh(&s, SPEED_SRC_OBD, t, &v) && v == 0.0f);
    assert(!speed_sources_fresh(&s, SPEED_SRC_WHEEL, t, &v));

    /* Tunnel: GPS gone, no gear estimate -> the held average. */
    uint32_t lost = t;
    for (t += 3000; t < lost + 60000; t += 100) {
        assert(speed_select_update(&sel, &cfg, &s, t, 0.1f, true, &v, &o));
        assert(o == SPEED_ORIGIN_HOLD && near(v, 90.0, 0.01));
    }
    /* Hold window over. */
    t = lost + 62000;
    assert(!speed_select_update(&sel, &cfg, &s, t, 0.1f, true, &v, &o));

    /* Gear estimate fresh: it stands in for the GPS. */
    speed_sources_publish(&s, SPEED_SRC_GEAR, 42.0f, -1.0f, t);
    assert(speed_select_update(&sel, &cfg, &s, t, 0.1f, true, &v, &o));
    assert(o == SPEED_ORIGIN_GEAR && v == 42.0f);

    /* Stopped: no hold, and the old average is dropped. */
    t += 5000;
    assert(!speed_select_update(&sel, &cfg, &s, t, 0.1f, false, &v, &o));
    assert(!speed_select_update(&sel, &cfg, &s, t + 100, 0.1f, true, &v, &o));

    /* The chosen source can be the gear estimate itself. */
    cfg.primary = SPEED_SRC_GEAR;
    speed_sources_publish(&s, SPEED_SRC_GEAR, 50.0f, -1.0f, t);
    assert(speed_select_update(&sel, &cfg, &s, t, 0.1f, true, &v, &o) && o == SPEED_ORIGIN_SOURCE);
    assert(!speed_sources_fresh(&s, SPEED_SRC_GEAR, t + SPEED_STALE_GEAR_MS + 1, &v));

    printf("speed_source: all tests passed\n");
    return 0;
}
