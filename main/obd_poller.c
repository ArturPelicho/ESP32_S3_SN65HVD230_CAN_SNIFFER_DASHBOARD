#include "obd_poller.h"

#include <inttypes.h>
#include <string.h>

#include "esp_log.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "obd_poller";

#define EVENT_QUEUE_LENGTH 32
#define TX_RETRY_TICKS 1
#define MAX_IDLE_WAIT_MS 1000
#define DISCOVERY_TRIES 2
#define BACKOFF_MAX_SHIFT 16
#define PAD_BYTE 0x55

enum event_type { EVENT_FRAME, EVENT_QUERY };

struct obd_event {
    uint8_t type;
    uint8_t len;
    uint8_t data[8];
    uint32_t id;
};

struct query_result {
    uint8_t pid;
    bool ok;
    uint8_t len;
    uint8_t data[8];
};

enum link_state { LINK_OFFLINE, LINK_DISCOVERING, LINK_POLLING };

struct pid_state {
    TickType_t next_due;
    uint8_t misses;
};

static obd_poller_config_t s_cfg;
static obd_poll_entry_t s_entries[OBD_POLLER_MAX_PIDS];
static struct pid_state s_state[OBD_POLLER_MAX_PIDS];
static QueueHandle_t s_events;
static QueueHandle_t s_query_result;
static SemaphoreHandle_t s_query_lock;

/* Supported-PID bitmap from 0100/0120/...; only meaningful up to
 * s_support_known_upto, beyond that every PID is assumed supported. */
static uint8_t s_support[32];
static uint16_t s_support_known_upto;
static uint16_t s_support_needed_upto; /* highest polled PID */

static enum link_state s_link = LINK_OFFLINE;
static TickType_t s_last_reply;
static TickType_t s_next_probe;
static bool s_probe_bitmap_next = true;
static uint16_t s_discover_pid;
static uint8_t s_discover_tries;
static uint8_t s_consecutive_timeouts;
static TickType_t s_tx_retry_at;

/* At most one request is on the bus at a time; many ECUs drop overlapping
 * functional requests, and replies normally arrive within a few ms. */
static bool s_busy;
static uint8_t s_busy_pid;
static int s_busy_entry; /* index into s_entries, or -1 */
static bool s_busy_is_query;
static bool s_busy_is_discovery;
static TickType_t s_busy_deadline;

static int s_pending_query = -1;

static inline bool tick_reached(TickType_t now, TickType_t when)
{
    return (int32_t)(now - when) >= 0;
}

static inline TickType_t ticks_until(TickType_t now, TickType_t when)
{
    int32_t diff = (int32_t)(when - now);
    return diff > 0 ? (TickType_t)diff : 0;
}

static bool support_bit(uint8_t pid)
{
    return (s_support[pid >> 3] >> (7 - (pid & 7))) & 1;
}

bool obd_poller_pid_supported(uint8_t pid)
{
    if (!s_cfg.use_support_bitmaps || pid > s_support_known_upto) {
        return true;
    }
    return support_bit(pid);
}

static void log_support_summary(void)
{
    char skipped[3 * OBD_POLLER_MAX_PIDS + 1] = "";
    size_t used = 0, active = 0;
    for (size_t i = 0; i < s_cfg.entry_count; ++i) {
        if (obd_poller_pid_supported(s_entries[i].pid)) {
            ++active;
        } else if (used + 4 <= sizeof(skipped)) {
            used += (size_t)snprintf(skipped + used, sizeof(skipped) - used, " %02X",
                                     s_entries[i].pid);
        }
    }
    ESP_LOGI(TAG, "ECU supports %u of %u polled PIDs (known up to 0x%02X)%s%s",
             (unsigned)active, (unsigned)s_cfg.entry_count, s_support_known_upto,
             used ? "; skipping" : "", skipped);
}

static void finish_discovery(void)
{
    s_link = LINK_POLLING;
    log_support_summary();
}

static void enter_online(TickType_t now)
{
    for (size_t i = 0; i < s_cfg.entry_count; ++i) {
        s_state[i].next_due = now;
    }
    if (s_cfg.use_support_bitmaps) {
        memset(s_support, 0, sizeof(s_support));
        s_support_known_upto = 0;
        s_discover_pid = 0x00;
        s_discover_tries = 0;
        s_link = LINK_DISCOVERING;
        ESP_LOGI(TAG, "ECU answering; discovering supported PIDs");
    } else {
        s_link = LINK_POLLING;
        ESP_LOGI(TAG, "ECU answering; polling");
    }
}

static void enter_offline(void)
{
    s_link = LINK_OFFLINE;
    s_next_probe = xTaskGetTickCount();
    ESP_LOGI(TAG, "ECU silent; probing every %" PRIu32 " ms", s_cfg.offline_probe_ms);
}

/* Bitmap reply for base B: bit 7 of A is PID B+1, bit 0 of D is PID B+0x20,
 * which also says whether the next bitmap (B+0x20) exists. Replies from
 * every ECU are OR-ed together. */
static void handle_bitmap(uint8_t base, const uint8_t *data, uint8_t len)
{
    bool discovering = s_link == LINK_DISCOVERING && base == s_discover_pid;
    if (len < 4) {
        if (discovering) {
            finish_discovery(); /* malformed bitmap: fall back to miss backoff */
        }
        return;
    }
    for (int i = 0; i < 32; ++i) {
        if ((data[i >> 3] >> (7 - (i & 7))) & 1) {
            int pid = base + 1 + i;
            s_support[pid >> 3] |= (uint8_t)(0x80 >> (pid & 7));
        }
    }
    uint16_t covered = (uint16_t)(base + 0x20) > 0xFF ? 0xFF : (uint16_t)(base + 0x20);
    if (covered > s_support_known_upto) {
        s_support_known_upto = covered;
    }

    if (!discovering) {
        return;
    }
    bool next_exists = data[3] & 1;
    if (!next_exists) {
        s_support_known_upto = 0xFF; /* nothing beyond this range */
        finish_discovery();
    } else if (base + 0x20 < s_support_needed_upto && base + 0x20 <= 0xE0) {
        s_discover_pid = base + 0x20;
        s_discover_tries = 0;
    } else {
        finish_discovery();
    }
}

static void post_query_result(uint8_t pid, bool ok, const uint8_t *data, uint8_t len)
{
    struct query_result result = { .pid = pid, .ok = ok, .len = len };
    if (ok) {
        memcpy(result.data, data, len);
    }
    xQueueOverwrite(s_query_result, &result);
}

static void handle_frame(const struct obd_event *ev, TickType_t now)
{
    if (ev->len < 3 || (ev->data[0] & 0xF0) != 0) {
        return; /* not an ISO-TP single frame */
    }
    uint8_t payload_len = ev->data[0] & 0x0F;
    if (payload_len == 0 || payload_len > ev->len - 1) {
        payload_len = ev->len - 1;
    }

    bool negative = ev->data[1] == 0x7F && ev->data[2] == 0x01;
    bool positive = ev->data[1] == 0x41 && payload_len >= 2;
    if (!negative && !positive) {
        return;
    }

    s_last_reply = now;
    s_consecutive_timeouts = 0;
    if (s_link == LINK_OFFLINE) {
        enter_online(now);
    }

    if (negative) {
        /* Mode 01 negative responses don't name the PID; it can only be
         * about the request on the bus, so end it now rather than waiting
         * out the timeout. */
        if (s_busy) {
            s_busy_deadline = now;
        }
        return;
    }

    uint8_t pid = ev->data[2];
    const uint8_t *value = &ev->data[3];
    uint8_t value_len = payload_len - 2;

    if ((pid & 0x1F) == 0) {
        handle_bitmap(pid, value, value_len);
    }
    for (size_t i = 0; i < s_cfg.entry_count; ++i) {
        if (s_entries[i].pid == pid) {
            s_state[i].misses = 0;
            if (s_cfg.on_value) {
                s_cfg.on_value(pid, value, value_len, s_cfg.value_ctx);
            }
        }
    }
    if (s_busy && pid == s_busy_pid) {
        if (s_busy_is_query) {
            post_query_result(pid, true, &ev->data[1], payload_len);
        }
        s_busy = false;
    }
}

static void handle_timeout(TickType_t now)
{
    s_busy = false;
    if (s_consecutive_timeouts < UINT8_MAX) {
        ++s_consecutive_timeouts;
    }
    /* Two misses in a row with nothing heard for offline_after_ms; a single
     * miss on a slow PID schedule is not enough. */
    bool ecu_silent = s_consecutive_timeouts >= 2 &&
                      (TickType_t)(now - s_last_reply) > pdMS_TO_TICKS(s_cfg.offline_after_ms);

    if (s_busy_is_query) {
        post_query_result(s_busy_pid, false, NULL, 0);
    }

    if (s_link != LINK_OFFLINE && ecu_silent) {
        enter_offline();
        return; /* a silent ECU says nothing about individual PIDs */
    }

    if (s_busy_is_discovery && s_link == LINK_DISCOVERING &&
        ++s_discover_tries >= DISCOVERY_TRIES) {
        ESP_LOGW(TAG, "No answer to 01%02X; PIDs above 0x%02X assumed supported",
                 (unsigned)s_discover_pid, s_support_known_upto);
        finish_discovery();
        return;
    }

    if (s_busy_entry >= 0 && s_link == LINK_POLLING) {
        struct pid_state *st = &s_state[s_busy_entry];
        const obd_poll_entry_t *entry = &s_entries[s_busy_entry];
        if (st->misses < UINT8_MAX) {
            ++st->misses;
        }
        if (st->misses >= s_cfg.miss_limit) {
            int shift = st->misses - s_cfg.miss_limit + 1;
            if (shift > BACKOFF_MAX_SHIFT) {
                shift = BACKOFF_MAX_SHIFT;
            }
            uint64_t interval = (uint64_t)entry->period_ms << shift;
            if (interval > s_cfg.backoff_max_ms) {
                interval = s_cfg.backoff_max_ms;
            }
            st->next_due = now + pdMS_TO_TICKS((uint32_t)interval);
            if (st->misses == s_cfg.miss_limit) {
                ESP_LOGW(TAG, "PID %02X unanswered %u times; backing off",
                         entry->pid, (unsigned)st->misses);
            }
        } else {
            ESP_LOGD(TAG, "PID %02X: no reply", entry->pid);
        }
    }
}

static bool send_request(uint8_t pid, TickType_t now)
{
    uint8_t data[8] = { 0x02, 0x01, pid, PAD_BYTE, PAD_BYTE, PAD_BYTE, PAD_BYTE, PAD_BYTE };
    if (!s_cfg.send(s_cfg.request_id, s_cfg.extended_id, data, s_cfg.send_ctx)) {
        s_tx_retry_at = now + TX_RETRY_TICKS;
        return false;
    }
    ESP_LOGD(TAG, "TX 01%02X", pid);
    s_busy = true;
    s_busy_pid = pid;
    s_busy_entry = -1;
    s_busy_is_query = false;
    s_busy_is_discovery = false;
    s_busy_deadline = now + pdMS_TO_TICKS(s_cfg.response_timeout_ms);
    if (s_busy_deadline == now) {
        ++s_busy_deadline;
    }
    return true;
}

/* Picks and sends the next request. Returns how long the task may sleep
 * before something else becomes due. */
static TickType_t schedule(TickType_t now)
{
    if (s_busy) {
        return ticks_until(now, s_busy_deadline);
    }
    if (!tick_reached(now, s_tx_retry_at)) {
        return ticks_until(now, s_tx_retry_at);
    }

    if (s_pending_query >= 0) {
        uint8_t pid = (uint8_t)s_pending_query;
        if (send_request(pid, now)) {
            s_pending_query = -1;
            s_busy_is_query = true;
            return ticks_until(now, s_busy_deadline);
        }
        return TX_RETRY_TICKS;
    }

    switch (s_link) {
    case LINK_OFFLINE: {
        if (!tick_reached(now, s_next_probe)) {
            return ticks_until(now, s_next_probe);
        }
        /* Alternate the bitmap request with a real PID, so an ECU that
         * ignores 0100 is still noticed. */
        bool bitmap = s_cfg.use_support_bitmaps && (s_probe_bitmap_next || s_cfg.entry_count == 0);
        s_probe_bitmap_next = !s_probe_bitmap_next;
        if (!bitmap && s_cfg.entry_count == 0) {
            s_next_probe = now + pdMS_TO_TICKS(s_cfg.offline_probe_ms);
            return pdMS_TO_TICKS(s_cfg.offline_probe_ms);
        }
        if (send_request(bitmap ? 0x00 : s_entries[0].pid, now)) {
            s_next_probe = now + pdMS_TO_TICKS(s_cfg.offline_probe_ms);
            return ticks_until(now, s_busy_deadline);
        }
        return TX_RETRY_TICKS;
    }
    case LINK_DISCOVERING:
        if (send_request((uint8_t)s_discover_pid, now)) {
            s_busy_is_discovery = true;
            return ticks_until(now, s_busy_deadline);
        }
        return TX_RETRY_TICKS;
    case LINK_POLLING:
    default:
        break;
    }

    int best = -1;
    for (size_t i = 0; i < s_cfg.entry_count; ++i) {
        if (!obd_poller_pid_supported(s_entries[i].pid)) {
            continue;
        }
        if (best < 0 || (int32_t)(s_state[i].next_due - s_state[best].next_due) < 0) {
            best = (int)i;
        }
    }
    if (best < 0) {
        return pdMS_TO_TICKS(MAX_IDLE_WAIT_MS);
    }
    if (!tick_reached(now, s_state[best].next_due)) {
        return ticks_until(now, s_state[best].next_due);
    }
    if (!send_request(s_entries[best].pid, now)) {
        return TX_RETRY_TICKS;
    }
    s_busy_entry = best;
    s_state[best].next_due = now + pdMS_TO_TICKS(s_entries[best].period_ms);
    return ticks_until(now, s_busy_deadline);
}

static void poller_task(void *arg)
{
    (void)arg;
    s_next_probe = xTaskGetTickCount();
    s_tx_retry_at = s_next_probe;
    while (true) {
        TickType_t now = xTaskGetTickCount();
        if (s_busy && tick_reached(now, s_busy_deadline)) {
            handle_timeout(now);
        }
        TickType_t wait = schedule(now);
        if (wait > pdMS_TO_TICKS(MAX_IDLE_WAIT_MS)) {
            wait = pdMS_TO_TICKS(MAX_IDLE_WAIT_MS);
        }

        struct obd_event ev;
        if (xQueueReceive(s_events, &ev, wait) != pdTRUE) {
            continue;
        }
        do {
            if (ev.type == EVENT_QUERY) {
                s_pending_query = ev.data[0];
            } else {
                handle_frame(&ev, xTaskGetTickCount());
            }
        } while (xQueueReceive(s_events, &ev, 0) == pdTRUE);
    }
}

esp_err_t obd_poller_start(const obd_poller_config_t *config)
{
    if (config == NULL || config->send == NULL || config->entry_count > OBD_POLLER_MAX_PIDS ||
        (config->entry_count > 0 && config->entries == NULL)) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_events != NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    s_cfg = *config;
    if (s_cfg.miss_limit == 0) {
        s_cfg.miss_limit = 1;
    }
    s_support_needed_upto = 0;
    for (size_t i = 0; i < s_cfg.entry_count; ++i) {
        s_entries[i] = config->entries[i];
        if (s_entries[i].pid > s_support_needed_upto) {
            s_support_needed_upto = s_entries[i].pid;
        }
    }
    s_cfg.entries = s_entries;

    s_query_lock = xSemaphoreCreateMutex();
    s_query_result = xQueueCreate(1, sizeof(struct query_result));
    QueueHandle_t events = xQueueCreate(EVENT_QUEUE_LENGTH, sizeof(struct obd_event));
    if (s_query_lock == NULL || s_query_result == NULL || events == NULL) {
        return ESP_ERR_NO_MEM;
    }
    s_events = events;

    BaseType_t created = config->core < 0
        ? xTaskCreate(poller_task, "obd_poller", s_cfg.stack_size, NULL, s_cfg.priority, NULL)
        : xTaskCreatePinnedToCore(poller_task, "obd_poller", s_cfg.stack_size, NULL,
                                  s_cfg.priority, NULL, config->core);
    return created == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}

bool obd_poller_feed_frame_from_isr(uint32_t id, const uint8_t *data, uint8_t len)
{
    if (s_events == NULL || id < s_cfg.response_id_min || id > s_cfg.response_id_max) {
        return false;
    }
    struct obd_event ev = { .type = EVENT_FRAME, .id = id, .len = len > 8 ? 8 : len };
    memcpy(ev.data, data, ev.len);
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(s_events, &ev, &woken);
    return woken == pdTRUE;
}

bool obd_poller_query(uint8_t pid, uint8_t *response, uint8_t *response_len,
                      uint32_t timeout_ms)
{
    if (s_events == NULL) {
        return false;
    }
    TickType_t start = xTaskGetTickCount();
    TickType_t timeout = pdMS_TO_TICKS(timeout_ms);
    if (xSemaphoreTake(s_query_lock, timeout) != pdTRUE) {
        return false;
    }
    xQueueReset(s_query_result);

    bool ok = false;
    struct obd_event ev = { .type = EVENT_QUERY, .data = { pid } };
    if (xQueueSend(s_events, &ev, ticks_until(xTaskGetTickCount(), start + timeout)) == pdTRUE) {
        struct query_result result;
        while (xQueueReceive(s_query_result, &result,
                             ticks_until(xTaskGetTickCount(), start + timeout)) == pdTRUE) {
            if (result.pid == pid) {
                ok = result.ok;
                if (ok) {
                    memcpy(response, result.data, result.len);
                    *response_len = result.len;
                }
                break;
            }
        }
    }
    xSemaphoreGive(s_query_lock);
    return ok;
}
