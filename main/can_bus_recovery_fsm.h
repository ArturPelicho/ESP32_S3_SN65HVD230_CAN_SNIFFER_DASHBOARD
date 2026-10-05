/*
 * Driver-agnostic CAN bus-off recovery state machine.
 *
 * Pure C with no RTOS or driver dependencies, so it can run on any MCU/CAN
 * controller and be unit-tested on the host (see test/host/). The caller
 * feeds it the current bus state and a monotonic millisecond clock; it says
 * when to ask the controller to start recovery and how long it may sleep
 * before the next step. It never blocks.
 *
 * Policy:
 *  - On bus-off, wait a short back-off, then request recovery.
 *  - If the controller has not rejoined within recovery_timeout_ms, request
 *    again after a longer back-off (the bus may be held dominant).
 *  - If the bus goes off again within stable_ms of the last recovery (wrong
 *    bitrate, wiring fault), double the back-off up to backoff_max_ms so a
 *    faulty node does not keep flooding the vehicle bus with error frames.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Controller-independent error state, ordered by severity. */
typedef enum {
    CAN_BUS_STATE_ACTIVE = 0,
    CAN_BUS_STATE_WARNING,
    CAN_BUS_STATE_PASSIVE,
    CAN_BUS_STATE_BUS_OFF,
} can_bus_state_t;

typedef struct {
    uint32_t backoff_min_ms;      /* Delay before the first recovery request */
    uint32_t backoff_max_ms;      /* Upper bound for the growing back-off */
    uint32_t recovery_timeout_ms; /* Re-request if not back online by then */
    uint32_t stable_ms;           /* Uptime after which the back-off resets */
} can_recovery_config_t;

#define CAN_RECOVERY_CONFIG_DEFAULT() { \
    .backoff_min_ms = 100,              \
    .backoff_max_ms = 5000,             \
    .recovery_timeout_ms = 1000,        \
    .stable_ms = 10000,                 \
}

typedef enum {
    CAN_RECOVERY_PHASE_IDLE = 0,    /* Bus usable, nothing to do */
    CAN_RECOVERY_PHASE_BACKOFF,     /* Bus-off, waiting before requesting */
    CAN_RECOVERY_PHASE_RECOVERING,  /* Recovery requested, waiting for bus */
} can_recovery_phase_t;

/* What the caller must do after a step. */
typedef enum {
    CAN_RECOVERY_ACT_NONE = 0,
    CAN_RECOVERY_ACT_BUS_OFF,        /* New bus-off episode (log only) */
    CAN_RECOVERY_ACT_REQUEST,        /* Ask the controller to start recovery */
    CAN_RECOVERY_ACT_TIMEOUT,        /* Recovery did not complete (log only) */
    CAN_RECOVERY_ACT_RECOVERED,      /* Bus usable again (log only) */
} can_recovery_action_t;

#define CAN_RECOVERY_WAIT_FOREVER UINT32_MAX

typedef struct {
    can_recovery_config_t cfg;
    can_recovery_phase_t phase;
    uint32_t backoff_ms;
    uint32_t deadline_ms;
    uint32_t episode_start_ms;
    uint32_t last_recovered_ms;
    bool has_recovered;
    uint32_t seen_bus_off_events;

    /* Statistics, monotonically increasing. */
    uint32_t bus_off_episodes;
    uint32_t recovery_requests;
    uint32_t recovery_timeouts;
    uint32_t recoveries;
    uint32_t last_outage_ms;  /* Duration of the last completed episode */
} can_recovery_fsm_t;

void can_recovery_fsm_init(can_recovery_fsm_t *fsm, const can_recovery_config_t *cfg);

/*
 * Advance the state machine.
 *
 * now_ms          monotonic clock in ms (wrap-around safe)
 * state           current controller state
 * bus_off_events  running count of transitions into bus-off reported by the
 *                 driver; lets the FSM notice a recover-then-fail-again that
 *                 happened entirely between two steps
 * wait_ms         out: how long the caller may sleep before the next step if
 *                 no new state change arrives (CAN_RECOVERY_WAIT_FOREVER when
 *                 idle)
 */
can_recovery_action_t can_recovery_fsm_step(can_recovery_fsm_t *fsm, uint32_t now_ms,
                                             can_bus_state_t state, uint32_t bus_off_events,
                                             uint32_t *wait_ms);

const char *can_bus_state_name(can_bus_state_t state);

#ifdef __cplusplus
}
#endif
