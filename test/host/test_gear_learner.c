/* Host test for main/gear_learner.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_gear_learner.c main/gear_learner.c -lm
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "gear_learner.h"

/* Made-up 5-speed box, km/h per 1000 rpm. */
static const float k_ratio[5] = { 4.6f, 6.9f, 8.8f, 10.3f, 11.6f };

static int near(double a, double b, double tol) { return fabs(a - b) <= tol; }

static float noise(float amp) { return amp * ((float)rand() / RAND_MAX * 2.0f - 1.0f); }

/* Rides `seconds` in `gear` at `rpm`, 100 ms steps, a GPS fix every 1 s. */
static void ride(gear_learner_t *g, int gear, float rpm, int seconds)
{
    for (int step = 0; step < seconds * 10; ++step) {
        float r = rpm + noise(40.0f);
        gear_learner_feed_rpm(g, r);
        float kmh;
        gear_learner_track(g, r, &kmh);
        if (step % 10 == 9) {
            gear_learner_on_speed(g, r / 1000.0f * k_ratio[gear - 1] + noise(0.3f), 0.5f, 1.0f);
        }
    }
}

int main(void)
{
    srand(1);
    gear_learner_config_t cfg = GEAR_LEARNER_DEFAULT_CONFIG(5);
    static gear_learner_t g;
    gear_learner_init(&g, &cfg);

    /* Nothing learnt: no gear, no speed. */
    float kmh = -1.0f;
    gear_learner_feed_rpm(&g, 5000.0f);
    assert(!gear_learner_track(&g, 5000.0f, &kmh));
    assert(g.found == 0);

    /* Only cruising in 4th and 5th: those two are found and numbered
     * from the top, so 5th is still 5th. */
    ride(&g, 5, 8200.0f, 60);
    ride(&g, 4, 7000.0f, 30);
    assert(g.found == 2 && !gear_learner_complete(&g));
    assert(g.gear_no[0] == 4 && g.gear_no[1] == 5);
    assert(near(g.ratio[1], 11.6, 0.15));

    /* A ride through all gears. */
    ride(&g, 1, 6000.0f, 15);
    ride(&g, 2, 6500.0f, 15);
    ride(&g, 3, 7000.0f, 20);
    assert(gear_learner_complete(&g));
    for (int i = 0; i < 5; ++i) {
        printf("gear %d ratio %.2f (true %.2f)\n", g.gear_no[i], g.ratio[i], k_ratio[i]);
        assert(g.gear_no[i] == i + 1);
        assert(near(g.ratio[i], k_ratio[i], k_ratio[i] * 0.02));
    }

    /* Accelerating hard (RPM sweeping) teaches nothing. */
    float before = gear_learner_total_s(&g);
    for (int step = 0; step < 50; ++step) {
        float r = 3000.0f + step * 120.0f;
        gear_learner_feed_rpm(&g, r);
        if (step % 10 == 9) gear_learner_on_speed(&g, r / 1000.0f * k_ratio[0], 0.5f, 1.0f);
    }
    assert(gear_learner_total_s(&g) == before);

    /* Tracking: cruising in 5th at 80 km/h. */
    ride(&g, 5, 80.0f / 11.6f * 1000.0f, 3);
    assert(g.gear == 5);
    assert(gear_learner_track(&g, 80.0f / 11.6f * 1000.0f, &kmh) && near(kmh, 80.0, 1.0));

    /* No GPS from here on (tunnel). Brake to 40 km/h in 5th... */
    for (float v = 80.0f; v >= 40.0f; v -= 0.5f) {
        float r = v / 11.6f * 1000.0f;
        gear_learner_feed_rpm(&g, r);
        assert(gear_learner_track(&g, r, &kmh) && g.gear == 5);
    }
    /* ...then straight to 2nd: clutch in for 0.3 s (RPM drops to idle),
     * then 40 km/h in 2nd. */
    for (int i = 0; i < 3; ++i) {
        gear_learner_feed_rpm(&g, 1400.0f);
        assert(!gear_learner_track(&g, 1400.0f, &kmh) && g.gear == 0);
    }
    float r2 = 40.0f / 6.9f * 1000.0f;
    gear_learner_feed_rpm(&g, r2);
    assert(gear_learner_track(&g, r2, &kmh));
    printf("after 5->2 in the tunnel: gear %u, %.1f km/h\n", g.gear, kmh);
    assert(g.gear == 2 && near(kmh, 40.0, 1.0));

    /* Quick upshift 2 -> 3 without the RPM going near idle. */
    float r3 = 40.0f / 8.8f * 1000.0f;
    gear_learner_feed_rpm(&g, r3);
    assert(gear_learner_track(&g, r3, &kmh) && g.gear == 3 && near(kmh, 40.0, 1.0));

    /* Clutch in and revving in neutral: no gear fits. */
    for (int i = 0; i < 30; ++i) {
        gear_learner_feed_rpm(&g, 7000.0f);
        gear_learner_track(&g, 7000.0f, &kmh);
    }
    assert(g.gear == 0);

    /* Saved histogram reloads to the same gears. */
    static gear_learner_t g2;
    gear_learner_init(&g2, &cfg);
    gear_learner_load(&g2, &g.hist);
    assert(g2.found == 5);
    for (int i = 0; i < 5; ++i) assert(g2.ratio[i] == g.ratio[i]);

    gear_learner_reset(&g2);
    assert(g2.found == 0 && gear_learner_total_s(&g2) == 0.0f);

    printf("gear_learner: all tests passed\n");
    return 0;
}
