/* Host test for main/tank.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_tank.c main/tank.c
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "tank.h"

static int near(double a, double b, double tol) { return fabs(a - b) <= tol; }

int main(void)
{
    const float k = 0.18f;          /* L/h per unit */
    const double per_litre = 3600.0 / k; /* value_s per litre */
    tank_state_t t;
    tank_init(&t);
    assert(tank_is_valid(&t));
    tank_view_t v;

    /* Unknown level until a fill. */
    tank_add(&t, per_litre * 1.0, 40.0);
    tank_view(&t, k, 2.65f, &v);
    assert(!v.level_known && !v.range_valid);

    /* Fill to full (12 L tank): first reference, no suggestion yet. */
    float sk = 0.0f, est = 0.0f;
    assert(!tank_fill(&t, 7.0f, true, 12.0f, k, &sk, &est));
    tank_view(&t, k, 2.65f, &v);
    assert(v.level_known && near(v.remaining_l, 12.0, 1e-4));
    assert(v.range_valid && near(v.range_km, 12.0 / 2.65 * 100.0, 0.1)); /* baseline */

    /* 100 km on 2.5 L: since-fill average takes over. */
    for (int i = 0; i < 100; ++i) tank_add(&t, per_litre * 0.025, 1.0);
    tank_view(&t, k, 2.65f, &v);
    assert(near(v.used_since_fill_l, 2.5, 1e-3) && near(v.remaining_l, 9.5, 1e-3));
    assert(v.avg_valid && near(v.avg_l_per_100km, 2.5, 1e-3));
    assert(near(v.range_km, 380.0, 0.5));

    /* Partial fill of 2 L: level 11.5, but the full reference stays. */
    assert(!tank_fill(&t, 2.0f, false, 12.0f, k, &sk, &est));
    tank_view(&t, k, 2.65f, &v);
    assert(near(v.remaining_l, 11.5, 1e-3) && v.km_since_fill == 0.0f);

    /* Another 1.5 L, then fill to full with 4.4 L at the pump: the board
     * counted 4.0 L since the last full, so the constant should be 10 %
     * higher. */
    tank_add(&t, per_litre * 1.5, 60.0);
    assert(tank_fill(&t, 4.4f, true, 12.0f, k, &sk, &est));
    printf("estimated %.3f L, suggested k %.4f\n", est, sk);
    assert(near(est, 4.0, 1e-3) && near(sk, 0.198, 1e-4));
    tank_view(&t, k, 2.65f, &v);
    assert(near(v.remaining_l, 12.0, 1e-4));

    /* Remaining never goes negative; direct level setting works. */
    tank_add(&t, per_litre * 20.0, 10.0);
    tank_view(&t, k, 2.65f, &v);
    assert(v.remaining_l == 0.0f);
    tank_set_level(&t, 6.0f);
    tank_view(&t, k, 2.65f, &v);
    assert(near(v.remaining_l, 6.0, 1e-4));

    /* Garbage from storage is rejected. */
    tank_state_t bad = t;
    bad.magic = 0;
    assert(!tank_is_valid(&bad));

    printf("tank: all tests passed\n");
    return 0;
}
