#include "can_bus_twai.h"

static esp_err_t twai_start_recovery(void *ctx)
{
    /* Returns immediately; the controller rejoins after it sees 128 x 11
     * recessive bits and the driver then resumes any queued frames. */
    return twai_node_recover((twai_node_handle_t)ctx);
}

can_bus_port_t can_bus_twai_port(twai_node_handle_t node, const char *name)
{
    can_bus_port_t port = {
        .name = name,
        .start_recovery = twai_start_recovery,
        .ctx = node,
    };
    return port;
}

static can_bus_state_t map_state(twai_error_state_t state)
{
    switch (state) {
    case TWAI_ERROR_ACTIVE: return CAN_BUS_STATE_ACTIVE;
    case TWAI_ERROR_WARNING: return CAN_BUS_STATE_WARNING;
    case TWAI_ERROR_PASSIVE: return CAN_BUS_STATE_PASSIVE;
    case TWAI_ERROR_BUS_OFF:
    default: return CAN_BUS_STATE_BUS_OFF;
    }
}

bool can_bus_twai_on_state_change(twai_node_handle_t handle,
                                  const twai_state_change_event_data_t *event_data,
                                  void *user_ctx)
{
    (void)handle;
    return can_bus_supervisor_report_state_from_isr((can_bus_supervisor_handle_t)user_ctx,
                                                    map_state(event_data->new_sta));
}
