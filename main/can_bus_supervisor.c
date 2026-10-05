#include "can_bus_supervisor.h"

#include <inttypes.h>
#include <stdatomic.h>
#include <stdlib.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/task.h"

static const char *TAG = "can_bus";

struct can_bus_supervisor {
    can_bus_port_t port;
    TaskHandle_t task;
    portMUX_TYPE lock;          /* Guards fsm for cross-core status reads */
    can_recovery_fsm_t fsm;
    _Atomic int state;          /* Written from ISR */
    _Atomic uint32_t bus_off_events;
};

static uint32_t now_ms(void)
{
    /* Truncation is fine: the FSM compares times wrap-around safe. */
    return (uint32_t)(esp_timer_get_time() / 1000);
}

static void supervisor_task(void *arg)
{
    struct can_bus_supervisor *sup = arg;
    uint32_t wait_ms = CAN_RECOVERY_WAIT_FOREVER;

    while (true) {
        ulTaskNotifyTake(pdTRUE, wait_ms == CAN_RECOVERY_WAIT_FOREVER
                                     ? portMAX_DELAY
                                     : pdMS_TO_TICKS(wait_ms) + 1);

        can_bus_state_t state = (can_bus_state_t)atomic_load(&sup->state);
        uint32_t events = atomic_load(&sup->bus_off_events);
        uint32_t now = now_ms();

        taskENTER_CRITICAL(&sup->lock);
        can_recovery_action_t action = can_recovery_fsm_step(&sup->fsm, now, state, events, &wait_ms);
        can_recovery_fsm_t snap = sup->fsm;
        taskEXIT_CRITICAL(&sup->lock);

        switch (action) {
        case CAN_RECOVERY_ACT_BUS_OFF:
            ESP_LOGW(TAG, "%s bus-off (#%" PRIu32 "), recovering in %" PRIu32 " ms",
                     sup->port.name, snap.bus_off_episodes, snap.backoff_ms);
            break;
        case CAN_RECOVERY_ACT_REQUEST: {
            esp_err_t err = sup->port.start_recovery(sup->port.ctx);
            /* INVALID_STATE means the bus came back on its own; the state
             * callback will report it. */
            if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
                ESP_LOGE(TAG, "%s recovery request failed: %s",
                         sup->port.name, esp_err_to_name(err));
            }
            break;
        }
        case CAN_RECOVERY_ACT_TIMEOUT:
            ESP_LOGW(TAG, "%s still bus-off, retrying in %" PRIu32 " ms",
                     sup->port.name, snap.backoff_ms);
            break;
        case CAN_RECOVERY_ACT_RECOVERED:
            ESP_LOGI(TAG, "%s back on bus after %" PRIu32 " ms (%s)",
                     sup->port.name, snap.last_outage_ms, can_bus_state_name(state));
            break;
        case CAN_RECOVERY_ACT_NONE:
        default:
            break;
        }
    }
}

esp_err_t can_bus_supervisor_create(const can_bus_port_t *port,
                                    const can_bus_supervisor_config_t *cfg,
                                    can_bus_supervisor_handle_t *out)
{
    if (port == NULL || port->start_recovery == NULL || cfg == NULL || out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    struct can_bus_supervisor *sup = calloc(1, sizeof(*sup));
    if (sup == NULL) {
        return ESP_ERR_NO_MEM;
    }
    sup->port = *port;
    if (sup->port.name == NULL) {
        sup->port.name = "can";
    }
    portMUX_INITIALIZE(&sup->lock);
    can_recovery_fsm_init(&sup->fsm, &cfg->timing);
    atomic_store(&sup->state, CAN_BUS_STATE_ACTIVE);
    atomic_store(&sup->bus_off_events, 0);

    if (xTaskCreatePinnedToCore(supervisor_task, "can_bus_sup", cfg->stack_size, sup,
                                cfg->priority, &sup->task, cfg->core_id) != pdPASS) {
        free(sup);
        return ESP_ERR_NO_MEM;
    }
    *out = sup;
    return ESP_OK;
}

bool can_bus_supervisor_report_state_from_isr(can_bus_supervisor_handle_t sup,
                                              can_bus_state_t new_state)
{
    if (sup == NULL) {
        return false;
    }
    if (new_state == CAN_BUS_STATE_BUS_OFF &&
        atomic_load(&sup->state) != CAN_BUS_STATE_BUS_OFF) {
        atomic_fetch_add(&sup->bus_off_events, 1);
    }
    atomic_store(&sup->state, new_state);
    BaseType_t woken = pdFALSE;
    vTaskNotifyGiveFromISR(sup->task, &woken);
    return woken == pdTRUE;
}

bool can_bus_supervisor_is_online(can_bus_supervisor_handle_t sup)
{
    return sup == NULL || atomic_load(&sup->state) != CAN_BUS_STATE_BUS_OFF;
}

void can_bus_supervisor_get_status(can_bus_supervisor_handle_t sup, can_bus_status_t *out)
{
    if (sup == NULL || out == NULL) {
        return;
    }
    taskENTER_CRITICAL(&sup->lock);
    out->state = (can_bus_state_t)atomic_load(&sup->state);
    out->recovering = sup->fsm.phase != CAN_RECOVERY_PHASE_IDLE;
    out->bus_off_episodes = sup->fsm.bus_off_episodes;
    out->recovery_requests = sup->fsm.recovery_requests;
    out->recovery_timeouts = sup->fsm.recovery_timeouts;
    out->recoveries = sup->fsm.recoveries;
    out->last_outage_ms = sup->fsm.last_outage_ms;
    taskEXIT_CRITICAL(&sup->lock);
}
