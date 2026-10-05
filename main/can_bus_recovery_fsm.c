#include "can_bus_recovery_fsm.h"

#include <string.h>

static bool time_reached(uint32_t now_ms, uint32_t deadline_ms)
{
    return (int32_t)(now_ms - deadline_ms) >= 0;
}

static uint32_t grow_backoff(const can_recovery_fsm_t *fsm)
{
    uint32_t next = fsm->backoff_ms * 2;
    if (next < fsm->backoff_ms || next > fsm->cfg.backoff_max_ms) {
        next = fsm->cfg.backoff_max_ms;
    }
    return next;
}

void can_recovery_fsm_init(can_recovery_fsm_t *fsm, const can_recovery_config_t *cfg)
{
    memset(fsm, 0, sizeof(*fsm));
    fsm->cfg = *cfg;
    if (fsm->cfg.backoff_max_ms < fsm->cfg.backoff_min_ms) {
        fsm->cfg.backoff_max_ms = fsm->cfg.backoff_min_ms;
    }
    fsm->backoff_ms = fsm->cfg.backoff_min_ms;
}

static void begin_episode(can_recovery_fsm_t *fsm, uint32_t now_ms, bool relapse)
{
    fsm->backoff_ms = relapse ? grow_backoff(fsm) : fsm->cfg.backoff_min_ms;
    fsm->phase = CAN_RECOVERY_PHASE_BACKOFF;
    fsm->deadline_ms = now_ms + fsm->backoff_ms;
    fsm->episode_start_ms = now_ms;
    fsm->bus_off_episodes++;
}

can_recovery_action_t can_recovery_fsm_step(can_recovery_fsm_t *fsm, uint32_t now_ms,
                                             can_bus_state_t state, uint32_t bus_off_events,
                                             uint32_t *wait_ms)
{
    can_recovery_action_t action = CAN_RECOVERY_ACT_NONE;
    bool new_bus_off = bus_off_events != fsm->seen_bus_off_events;
    fsm->seen_bus_off_events = bus_off_events;

    if (state == CAN_BUS_STATE_BUS_OFF) {
        if (fsm->phase == CAN_RECOVERY_PHASE_IDLE) {
            bool relapse = fsm->has_recovered &&
                           (uint32_t)(now_ms - fsm->last_recovered_ms) < fsm->cfg.stable_ms;
            begin_episode(fsm, now_ms, relapse);
            action = CAN_RECOVERY_ACT_BUS_OFF;
        } else if (fsm->phase == CAN_RECOVERY_PHASE_RECOVERING && new_bus_off) {
            /* Recovered and fell off again before we got to look. */
            fsm->recoveries++;
            fsm->last_outage_ms = now_ms - fsm->episode_start_ms;
            fsm->last_recovered_ms = now_ms;
            fsm->has_recovered = true;
            begin_episode(fsm, now_ms, true);
            action = CAN_RECOVERY_ACT_BUS_OFF;
        } else if (fsm->phase == CAN_RECOVERY_PHASE_BACKOFF &&
                   time_reached(now_ms, fsm->deadline_ms)) {
            fsm->phase = CAN_RECOVERY_PHASE_RECOVERING;
            fsm->deadline_ms = now_ms + fsm->cfg.recovery_timeout_ms;
            fsm->recovery_requests++;
            action = CAN_RECOVERY_ACT_REQUEST;
        } else if (fsm->phase == CAN_RECOVERY_PHASE_RECOVERING &&
                   time_reached(now_ms, fsm->deadline_ms)) {
            fsm->recovery_timeouts++;
            fsm->backoff_ms = grow_backoff(fsm);
            fsm->phase = CAN_RECOVERY_PHASE_BACKOFF;
            fsm->deadline_ms = now_ms + fsm->backoff_ms;
            action = CAN_RECOVERY_ACT_TIMEOUT;
        }
    } else if (fsm->phase != CAN_RECOVERY_PHASE_IDLE) {
        fsm->phase = CAN_RECOVERY_PHASE_IDLE;
        fsm->recoveries++;
        fsm->last_outage_ms = now_ms - fsm->episode_start_ms;
        fsm->last_recovered_ms = now_ms;
        fsm->has_recovered = true;
        action = CAN_RECOVERY_ACT_RECOVERED;
    }

    if (wait_ms != NULL) {
        if (fsm->phase == CAN_RECOVERY_PHASE_IDLE) {
            *wait_ms = CAN_RECOVERY_WAIT_FOREVER;
        } else if (time_reached(now_ms, fsm->deadline_ms)) {
            *wait_ms = 0;
        } else {
            *wait_ms = fsm->deadline_ms - now_ms;
        }
    }
    return action;
}

const char *can_bus_state_name(can_bus_state_t state)
{
    switch (state) {
    case CAN_BUS_STATE_ACTIVE: return "ACTIVE";
    case CAN_BUS_STATE_WARNING: return "WARNING";
    case CAN_BUS_STATE_PASSIVE: return "PASSIVE";
    case CAN_BUS_STATE_BUS_OFF: return "BUS_OFF";
    default: return "UNKNOWN";
    }
}
