/*
 * ESP-IDF TWAI (esp_twai node API) port for the CAN bus supervisor.
 * Swapping the CAN controller (MCP2515, another MCU's peripheral) means
 * writing an equivalent small port; the supervisor and FSM stay unchanged.
 */
#pragma once

#include <stdbool.h>

#include "esp_twai.h"
#include "can_bus_supervisor.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Build the supervisor port for a TWAI node. */
can_bus_port_t can_bus_twai_port(twai_node_handle_t node, const char *name);

/* Use as twai_event_callbacks_t.on_state_change, registering the supervisor
 * handle as the callbacks' user_data. */
bool can_bus_twai_on_state_change(twai_node_handle_t handle,
                                  const twai_state_change_event_data_t *event_data,
                                  void *user_ctx);

#ifdef __cplusplus
}
#endif
