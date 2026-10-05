/* Host unit test for the bus-off recovery FSM. Build and run with:
 *   cc -std=c11 -Wall -Wextra -Werror -I main \
 *      test/host/test_can_bus_recovery_fsm.c main/can_bus_recovery_fsm.c -o /tmp/t && /tmp/t
 */
#include <stdio.h>
#include <stdlib.h>

#include "can_bus_recovery_fsm.h"

static int s_failures;

#define CHECK(cond) do { if (!(cond)) { \
    printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); s_failures++; } } while (0)

static can_recovery_fsm_t make(void)
{
    can_recovery_config_t cfg = CAN_RECOVERY_CONFIG_DEFAULT();
    can_recovery_fsm_t fsm;
    can_recovery_fsm_init(&fsm, &cfg);
    return fsm;
}

static void test_idle_waits_forever(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    CHECK(can_recovery_fsm_step(&fsm, 0, CAN_BUS_STATE_PASSIVE, 0, &wait) == CAN_RECOVERY_ACT_NONE);
    CHECK(wait == CAN_RECOVERY_WAIT_FOREVER);
}

static void test_basic_recovery(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    CHECK(can_recovery_fsm_step(&fsm, 1000, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_BUS_OFF);
    CHECK(wait == 100);
    CHECK(can_recovery_fsm_step(&fsm, 1050, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_NONE);
    CHECK(wait == 50);
    CHECK(can_recovery_fsm_step(&fsm, 1100, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_REQUEST);
    CHECK(wait == 1000);
    CHECK(can_recovery_fsm_step(&fsm, 1103, CAN_BUS_STATE_ACTIVE, 1, &wait) == CAN_RECOVERY_ACT_RECOVERED);
    CHECK(wait == CAN_RECOVERY_WAIT_FOREVER);
    CHECK(fsm.recoveries == 1 && fsm.recovery_requests == 1 && fsm.last_outage_ms == 103);
}

static void test_timeout_retries_with_growing_backoff(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    can_recovery_fsm_step(&fsm, 0, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    CHECK(can_recovery_fsm_step(&fsm, 100, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_REQUEST);
    CHECK(can_recovery_fsm_step(&fsm, 1100, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_TIMEOUT);
    CHECK(wait == 200);
    CHECK(can_recovery_fsm_step(&fsm, 1300, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_REQUEST);
    CHECK(fsm.recovery_requests == 2 && fsm.recovery_timeouts == 1);
    /* Back-off is capped. */
    uint32_t t = 1300;
    for (int i = 0; i < 20; ++i) {
        t += 1000;
        can_recovery_fsm_step(&fsm, t, CAN_BUS_STATE_BUS_OFF, 1, &wait);
        t += wait;
        can_recovery_fsm_step(&fsm, t, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    }
    CHECK(fsm.backoff_ms == 5000);
}

static void test_relapse_grows_then_stable_resets(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    can_recovery_fsm_step(&fsm, 0, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    can_recovery_fsm_step(&fsm, 100, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    can_recovery_fsm_step(&fsm, 105, CAN_BUS_STATE_ACTIVE, 1, &wait);
    /* Falls off again quickly: longer back-off. */
    CHECK(can_recovery_fsm_step(&fsm, 500, CAN_BUS_STATE_BUS_OFF, 2, &wait) == CAN_RECOVERY_ACT_BUS_OFF);
    CHECK(wait == 200);
    can_recovery_fsm_step(&fsm, 700, CAN_BUS_STATE_BUS_OFF, 2, &wait);
    can_recovery_fsm_step(&fsm, 705, CAN_BUS_STATE_ACTIVE, 2, &wait);
    /* After a long stable period, back to the minimum. */
    CHECK(can_recovery_fsm_step(&fsm, 60705, CAN_BUS_STATE_BUS_OFF, 3, &wait) == CAN_RECOVERY_ACT_BUS_OFF);
    CHECK(wait == 100);
    CHECK(fsm.bus_off_episodes == 3);
}

static void test_missed_recover_and_relapse(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    can_recovery_fsm_step(&fsm, 0, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    can_recovery_fsm_step(&fsm, 100, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    /* Recovered and fell off again between two steps. */
    CHECK(can_recovery_fsm_step(&fsm, 150, CAN_BUS_STATE_BUS_OFF, 2, &wait) == CAN_RECOVERY_ACT_BUS_OFF);
    CHECK(fsm.recoveries == 1 && fsm.bus_off_episodes == 2 && wait == 200);
}

static void test_clock_wraparound(void)
{
    can_recovery_fsm_t fsm = make();
    uint32_t wait;
    uint32_t t = UINT32_MAX - 30;
    can_recovery_fsm_step(&fsm, t, CAN_BUS_STATE_BUS_OFF, 1, &wait);
    CHECK(wait == 100);
    CHECK(can_recovery_fsm_step(&fsm, t + 50, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_NONE);
    CHECK(wait == 50);
    CHECK(can_recovery_fsm_step(&fsm, t + 100, CAN_BUS_STATE_BUS_OFF, 1, &wait) == CAN_RECOVERY_ACT_REQUEST);
}

int main(void)
{
    test_idle_waits_forever();
    test_basic_recovery();
    test_timeout_retries_with_growing_backoff();
    test_relapse_grows_then_stable_resets();
    test_missed_recover_and_relapse();
    test_clock_wraparound();
    if (s_failures) {
        printf("%d failure(s)\n", s_failures);
        return EXIT_FAILURE;
    }
    printf("all recovery FSM tests passed\n");
    return EXIT_SUCCESS;
}
