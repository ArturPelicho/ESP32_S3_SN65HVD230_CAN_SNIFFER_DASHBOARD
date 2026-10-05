/*
 * CAN bus health supervisor: runs the bus-off recovery state machine in its
 * own small task so recovery never stalls the OBD polling, BLE or display
 * tasks. It knows nothing about the CAN controller; a port (see
 * can_bus_twai.h) supplies the one controller operation it needs and feeds
 * state changes in from the driver's ISR.
 *
 * One supervisor per CAN controller, so boards with several controllers
 * (e.g. ESP32-P4) just create one each.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "can_bus_recovery_fsm.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Controller-specific hooks. Must not block. */
typedef struct {
    const char *name;                         /* For logs, e.g. "twai0" */
    esp_err_t (*start_recovery)(void *ctx);   /* Begin bus-off recovery */
    void *ctx;
} can_bus_port_t;

typedef struct {
    can_recovery_config_t timing;
    BaseType_t core_id;        /* tskNO_AFFINITY or a core number */
    UBaseType_t priority;
    uint32_t stack_size;
} can_bus_supervisor_config_t;

/* Core 0 with the CAN/BLE stacks; one above telemetry so a request goes out
 * promptly, which is cheap because the task only wakes on state changes. */
#define CAN_BUS_SUPERVISOR_CONFIG_DEFAULT() { \
    .timing = CAN_RECOVERY_CONFIG_DEFAULT(),  \
    .core_id = 0,                             \
    .priority = 6,                            \
    .stack_size = 3072,                       \
}

typedef struct {
    can_bus_state_t state;
    bool recovering;              /* Bus-off and recovery in progress */
    uint32_t bus_off_episodes;
    uint32_t recovery_requests;
    uint32_t recovery_timeouts;
    uint32_t recoveries;
    uint32_t last_outage_ms;
} can_bus_status_t;

typedef struct can_bus_supervisor *can_bus_supervisor_handle_t;

/* Create the supervisor and its task. Call before enabling the controller so
 * no early state change is lost. */
esp_err_t can_bus_supervisor_create(const can_bus_port_t *port,
                                    const can_bus_supervisor_config_t *cfg,
                                    can_bus_supervisor_handle_t *out);

/* Report a controller state change. ISR-safe and non-blocking; returns true
 * if a higher priority task was woken (return it from the driver callback). */
bool can_bus_supervisor_report_state_from_isr(can_bus_supervisor_handle_t sup,
                                              can_bus_state_t new_state);

/* Fast check for callers that want to skip bus work while it is down. */
bool can_bus_supervisor_is_online(can_bus_supervisor_handle_t sup);

/* Thread-safe snapshot for UI, BLE or logging on any core. */
void can_bus_supervisor_get_status(can_bus_supervisor_handle_t sup, can_bus_status_t *out);

#ifdef __cplusplus
}
#endif
