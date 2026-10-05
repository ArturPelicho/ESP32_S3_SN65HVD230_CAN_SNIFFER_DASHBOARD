#pragma once

/* Non-blocking OBD-II Mode 01 poller.
 *
 * Owns the request/response cycle so nothing else has to wait on the bus:
 *  - Asks the ECU which PIDs it supports (Mode 01 PIDs 0x00, 0x20, ...) and
 *    never polls the ones it says it does not have.
 *  - PIDs that still go unanswered are backed off after a few misses instead
 *    of costing a full timeout every cycle.
 *  - When the ECU goes silent (ignition off) it only sends a slow presence
 *    probe, and rediscovers support when the ECU comes back.
 *  - A negative response ends a request immediately instead of timing out.
 *  - The task sleeps until the next reply, timeout or due PID; there are no
 *    fixed delays and no mutex around the bus.
 *
 * The poller knows nothing about TWAI, the display or ELM327: frames go out
 * through a send callback and come in through obd_poller_feed_frame_from_isr(),
 * and decoded payloads are handed to a value callback. Request/response IDs,
 * timings and the PID list are all configuration, so another bike, ECU or
 * CAN controller only needs a different config. */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "freertos/FreeRTOS.h"

#define OBD_POLLER_MAX_PIDS 32

typedef struct {
    uint8_t pid;
    uint32_t period_ms;
} obd_poll_entry_t;

/* Transmit one 8-byte CAN frame. Must not block; return false when the
 * frame could not be queued (the poller retries shortly). */
typedef bool (*obd_send_fn)(uint32_t id, bool extended, const uint8_t data[8], void *ctx);

/* A Mode 01 reply for a polled PID. data/len are the bytes after the PID
 * (A, B, C...). Runs in the poller task. */
typedef void (*obd_value_fn)(uint8_t pid, const uint8_t *data, uint8_t len, void *ctx);

typedef struct {
    /* Protocol: functional request ID and accepted response ID range. */
    uint32_t request_id;
    bool extended_id;
    uint32_t response_id_min;
    uint32_t response_id_max;

    /* Timing. */
    uint32_t response_timeout_ms;  /* wait for one reply before counting a miss */
    uint8_t miss_limit;            /* consecutive misses before a PID is backed off */
    uint32_t backoff_max_ms;       /* longest retry interval for a backed-off PID */
    uint32_t offline_after_ms;     /* no reply for this long -> ECU considered off */
    uint32_t offline_probe_ms;     /* presence probe interval while ECU is off */
    bool use_support_bitmaps;      /* query 0100/0120/... and skip unsupported PIDs */

    const obd_poll_entry_t *entries;
    size_t entry_count;

    obd_send_fn send;
    void *send_ctx;
    obd_value_fn on_value;
    void *value_ctx;

    /* Task placement. */
    int core;
    UBaseType_t priority;
    uint32_t stack_size;
} obd_poller_config_t;

/* ISO 15765-4, 11-bit IDs, 500 or 250 kbit/s: functional request on 0x7DF,
 * ECU replies on 0x7E8-0x7EF. For 29-bit ECUs use request 0x18DB33F1 and
 * responses 0x18DAF100-0x18DAF1FF with extended_id = true. */
#define OBD_POLLER_DEFAULT_CONFIG() {          \
    .request_id = 0x7DF,                        \
    .extended_id = false,                       \
    .response_id_min = 0x7E8,                   \
    .response_id_max = 0x7EF,                   \
    .response_timeout_ms = 150,                 \
    .miss_limit = 3,                            \
    .backoff_max_ms = 30000,                    \
    .offline_after_ms = 1000,                   \
    .offline_probe_ms = 500,                    \
    .use_support_bitmaps = true,                \
    .core = 0,                                  \
    .priority = 5,                              \
    .stack_size = 4096,                         \
}

/* Copies the config (entries included) and starts the poller task. */
esp_err_t obd_poller_start(const obd_poller_config_t *config);

/* Call from the CAN RX ISR for every received frame; frames outside the
 * response ID range are ignored cheaply. Returns true if a higher priority
 * task was woken. */
bool obd_poller_feed_frame_from_isr(uint32_t id, const uint8_t *data, uint8_t len);

/* One-off Mode 01 request on behalf of another task (e.g. a BLE ELM327
 * client). Served ahead of scheduled polling; blocks only the caller.
 * On success, response holds the reply from the service byte on
 * (41 PID A B ...) and *response_len its length. */
bool obd_poller_query(uint8_t pid, uint8_t *response, uint8_t *response_len,
                      uint32_t timeout_ms);

/* Whether the last support discovery says the ECU has this PID (true when
 * unknown). */
bool obd_poller_pid_supported(uint8_t pid);
