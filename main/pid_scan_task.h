#pragma once

/* Runs the PID discovery test (see pid_scan.h) alongside normal polling.
 *
 * Once started it:
 *   1. asks every Mode 01 PID from 0x00 to 0xFF, ignoring what the ECU's
 *      bitmaps claim, and asks the silent ones a second time;
 *   2. prints a full report;
 *   3. keeps re-asking only the PIDs that answered, so a ride or a push of
 *      the bike shows which bytes move, and prints the report again every
 *      report_period_ms.
 * Every received CAN frame is tallied as well, so broadcast traffic
 * between the ECU and the stock dash shows up next to the OBD answers.
 *
 * Requests go through the caller's query function (normally
 * obd_poller_query, which slots them in between the dashboard's own
 * polls), so the dashboard keeps updating. Nothing is allocated until the
 * first start; the scan costs nothing when it is never used. */

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "pid_scan.h"

/* Ask one Mode 01 PID and wait for the reply; true if one arrived.
 * Called from the scan task only, so it may block. */
typedef bool (*pid_scan_query_fn)(uint8_t pid, uint32_t timeout_ms);

typedef struct {
    pid_scan_config_t ids;
    pid_scan_query_fn query;
    uint32_t query_timeout_ms;
    uint32_t gap_ms;            /* pause between requests, leaves bus time for polling */
    uint32_t report_period_ms;
    pid_scan_line_fn line_out;  /* called from the scan task */
    void *line_ctx;
    int core;
    UBaseType_t priority;
} pid_scan_task_config_t;

/* Stores the config; allocates nothing. */
void pid_scan_task_init(const pid_scan_task_config_t *config);

/* Starts a fresh scan (restarts one already running). */
esp_err_t pid_scan_start(void);
void pid_scan_stop(void);
/* Prints the report now, from the scan task. No-op when never started. */
void pid_scan_request_report(void);
bool pid_scan_running(void);

/* Call from the CAN RX ISR for every frame. Returns true if a higher
 * priority task was woken. Costs one flag check while no scan runs. */
bool pid_scan_feed_frame_from_isr(uint32_t id, bool extended, const uint8_t *data, uint8_t len);
