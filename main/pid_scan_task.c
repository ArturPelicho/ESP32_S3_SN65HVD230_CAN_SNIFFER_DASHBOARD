#include "pid_scan_task.h"

#include <string.h>

#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "pid_scan";

#define EVENT_QUEUE_LENGTH 128
#define SWEEP_PASSES 2

enum event_type { EV_FRAME, EV_ASK, EV_DONE, EV_RESET, EV_PAUSE, EV_SWEEP_DONE, EV_WATCH_DONE };

struct scan_event {
    uint8_t type;
    uint8_t pid;
    bool flag; /* EV_DONE: answered. EV_FRAME: extended ID */
    uint8_t len;
    uint8_t data[8];
    uint32_t id;
    uint32_t ms;
};

static pid_scan_task_config_t s_cfg;
static QueueHandle_t s_events;
static SemaphoreHandle_t s_paused;
static SemaphoreHandle_t s_resume;
static TaskHandle_t s_sweep_task;
static pid_scan_t *s_scan; /* owned by the consumer task, lent to the sweep task for reports */
static volatile bool s_running;
static volatile uint32_t s_generation;
static volatile uint32_t s_dropped;
static volatile bool s_report_requested;

static uint32_t now_ms(void)
{
    return (uint32_t)pdTICKS_TO_MS(xTaskGetTickCount());
}

static void post(uint8_t type, uint8_t pid, bool flag)
{
    struct scan_event ev = { .type = type, .pid = pid, .flag = flag, .ms = now_ms() };
    /* The consumer always drains the queue, so waiting here only ever
     * delays the scan, and keeps ASK/DONE/PAUSE from being lost. */
    xQueueSend(s_events, &ev, portMAX_DELAY);
}

/* Owns s_scan: every change to the statistics happens here, in arrival
 * order, so an ASK, its reply frames and its DONE are seen in sequence. */
static void consumer_task(void *arg)
{
    (void)arg;
    struct scan_event ev;
    while (xQueueReceive(s_events, &ev, portMAX_DELAY) == pdTRUE) {
        switch (ev.type) {
        case EV_FRAME:
            pid_scan_on_frame(s_scan, ev.id, ev.flag, ev.data, ev.len, ev.ms);
            break;
        case EV_ASK:
            pid_scan_on_ask(s_scan, ev.pid);
            break;
        case EV_DONE:
            pid_scan_on_done(s_scan, ev.pid, ev.flag);
            break;
        case EV_RESET:
            pid_scan_init(s_scan, &s_cfg.ids, ev.ms);
            s_dropped = 0;
            break;
        case EV_SWEEP_DONE:
            ++s_scan->sweeps;
            break;
        case EV_WATCH_DONE:
            ++s_scan->watch_loops;
            break;
        case EV_PAUSE:
            /* Hand s_scan to the sweep task while it prints the report;
             * frames arriving meanwhile wait in the queue (or count as
             * lost), which costs less RAM than a second copy. */
            s_scan->frames_dropped = s_dropped;
            xSemaphoreGive(s_paused);
            xSemaphoreTake(s_resume, portMAX_DELAY);
            break;
        default:
            break;
        }
    }
}

static void print_report(void)
{
    post(EV_PAUSE, 0, false);
    xSemaphoreTake(s_paused, portMAX_DELAY);
    pid_scan_report(s_scan, now_ms(), s_cfg.line_out, s_cfg.line_ctx);
    xSemaphoreGive(s_resume);
}

static bool still_current(uint32_t generation)
{
    return s_running && s_generation == generation;
}

static void ask(uint8_t pid)
{
    post(EV_ASK, pid, false);
    bool answered = s_cfg.query(pid, s_cfg.query_timeout_ms);
    post(EV_DONE, pid, answered);
    vTaskDelay(pdMS_TO_TICKS(s_cfg.gap_ms));
}

static void maybe_report(TickType_t *next_report)
{
    if (s_report_requested || (int32_t)(xTaskGetTickCount() - *next_report) >= 0) {
        s_report_requested = false;
        print_report();
        *next_report = xTaskGetTickCount() + pdMS_TO_TICKS(s_cfg.report_period_ms);
    }
}

/* Reads s_scan's answered/negative counters while the consumer may be
 * updating them. That is benign: they only ever grow, and a PID read one
 * pass late is simply asked once more. */
static void sweep_task(void *arg)
{
    (void)arg;
    while (true) {
        if (!s_running) {
            if (s_report_requested) {
                s_report_requested = false;
                print_report();
            }
            ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
            continue;
        }
        uint32_t generation = s_generation;
        ulTaskNotifyTake(pdTRUE, 0);
        ESP_LOGI(TAG, "scan started: every Mode 01 PID, then watching the ones that answer "
                      "(%u bytes of statistics, %u bytes heap left)",
                 (unsigned)sizeof(pid_scan_t), (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));

        TickType_t next_report = xTaskGetTickCount() + pdMS_TO_TICKS(s_cfg.report_period_ms);
        while (still_current(generation)) {
            for (int pass = 0; pass < SWEEP_PASSES && still_current(generation); ++pass) {
                for (int pid = 0; pid <= 0xFF && still_current(generation); ++pid) {
                    const pid_scan_pid_t *p = &s_scan->pids[pid];
                    if (pass > 0 && (p->replies > 0 || p->negative > 0)) continue;
                    ask((uint8_t)pid);
                    if (s_report_requested) maybe_report(&next_report);
                }
                if (still_current(generation)) post(EV_SWEEP_DONE, 0, false);
            }
            if (!still_current(generation)) break;
            print_report();
            next_report = xTaskGetTickCount() + pdMS_TO_TICKS(s_cfg.report_period_ms);

            bool any = true;
            while (any && still_current(generation)) {
                any = false;
                for (int pid = 1; pid <= 0xFF && still_current(generation); ++pid) {
                    if ((pid & 0x1F) == 0 || s_scan->pids[pid].replies == 0) continue;
                    any = true;
                    ask((uint8_t)pid);
                    maybe_report(&next_report);
                }
                if (any && still_current(generation)) post(EV_WATCH_DONE, 0, false);
            }
            if (!any && still_current(generation)) {
                /* Nothing answers (ignition off?): keep tallying bus
                 * traffic for a while, then sweep again. */
                ESP_LOGW(TAG, "no PID answered; sweeping again in %lu ms",
                         (unsigned long)s_cfg.report_period_ms);
                vTaskDelay(pdMS_TO_TICKS(s_cfg.report_period_ms));
            }
        }
        if (!s_running) {
            ESP_LOGI(TAG, "scan stopped");
            print_report();
        }
    }
}

void pid_scan_task_init(const pid_scan_task_config_t *config)
{
    s_cfg = *config;
}

static esp_err_t allocate(void)
{
    if (s_sweep_task != NULL) return ESP_OK;
    if (s_cfg.query == NULL || s_cfg.line_out == NULL) return ESP_ERR_INVALID_STATE;
    s_scan = heap_caps_malloc(sizeof(pid_scan_t), MALLOC_CAP_8BIT);
    s_paused = xSemaphoreCreateBinary();
    s_resume = xSemaphoreCreateBinary();
    QueueHandle_t events = xQueueCreate(EVENT_QUEUE_LENGTH, sizeof(struct scan_event));
    if (s_scan == NULL || s_paused == NULL || s_resume == NULL || events == NULL) {
        ESP_LOGE(TAG, "not enough memory for the PID scan (%u bytes)", (unsigned)sizeof(pid_scan_t));
        return ESP_ERR_NO_MEM;
    }
    pid_scan_init(s_scan, &s_cfg.ids, now_ms());
    s_events = events;

    TaskHandle_t consumer = NULL;
    if (xTaskCreatePinnedToCore(consumer_task, "pid_scan_rx", 3072, NULL, s_cfg.priority, &consumer,
                                s_cfg.core) != pdPASS ||
        xTaskCreatePinnedToCore(sweep_task, "pid_scan", 4096, NULL, s_cfg.priority, &s_sweep_task,
                                s_cfg.core) != pdPASS) {
        if (consumer != NULL) vTaskDelete(consumer);
        s_events = NULL;
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

esp_err_t pid_scan_start(void)
{
    esp_err_t err = allocate();
    if (err != ESP_OK) return err;
    post(EV_RESET, 0, false);
    ++s_generation;
    s_running = true;
    xTaskNotifyGive(s_sweep_task);
    return ESP_OK;
}

void pid_scan_stop(void)
{
    s_running = false;
}

void pid_scan_request_report(void)
{
    if (s_sweep_task == NULL) return;
    s_report_requested = true;
    xTaskNotifyGive(s_sweep_task);
}

bool pid_scan_running(void)
{
    return s_running;
}

bool pid_scan_feed_frame_from_isr(uint32_t id, bool extended, const uint8_t *data, uint8_t len)
{
    if (!s_running || s_events == NULL) return false;
    struct scan_event ev = {
        .type = EV_FRAME,
        .flag = extended,
        .len = len > 8 ? 8 : len,
        .id = id,
        .ms = (uint32_t)pdTICKS_TO_MS(xTaskGetTickCountFromISR()),
    };
    memcpy(ev.data, data, ev.len);
    BaseType_t woken = pdFALSE;
    if (xQueueSendFromISR(s_events, &ev, &woken) != pdTRUE) {
        ++s_dropped;
    }
    return woken == pdTRUE;
}
