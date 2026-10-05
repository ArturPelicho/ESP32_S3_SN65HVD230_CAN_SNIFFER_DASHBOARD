#pragma once

/* PID discovery test: bookkeeping and report.
 *
 * Answers "which Mode 01 PIDs does this ECU really answer, which bytes move,
 * and what else is on the bus?" without trusting the ECU's own supported-PID
 * bitmaps (0100, 0120, ...), which can claim PIDs the ECU never fills in.
 *
 * Pure C, no ESP-IDF or FreeRTOS: the caller says which PID it is asking
 * (pid_scan_on_ask / pid_scan_on_done) and hands over every received CAN
 * frame (pid_scan_on_frame). From that it keeps, per PID:
 *   - whether the bitmaps claim it, and whether it actually answered,
 *     stayed silent or got a negative response (with the NRC);
 *   - which ECU address answered (0x7E8..0x7EF), in case a second module
 *     is the one that has the speed;
 *   - every data byte's latest, min and max value and how often it
 *     changed, so a ride shows which PID moves with road speed;
 * and per non-OBD CAN ID on the bus (broadcast traffic between the ECU
 * and the stock dash, where speed, odometer and gear may live): frame
 * count, rate, and the same per-byte statistics.
 *
 * Not thread safe: one task owns a pid_scan_t. */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PID_SCAN_MAX_VALUE_BYTES 5 /* single-frame Mode 01 reply: 8 - PCI - 0x41 - PID */
#define PID_SCAN_MAX_IDS 64

typedef struct {
    uint32_t request_id;      /* our own requests, never counted as traffic */
    uint32_t response_id_min; /* OBD replies, 0x7E8..0x7EF for 11-bit */
    uint32_t response_id_max;
} pid_scan_config_t;

typedef struct {
    uint8_t len;
    uint8_t last[8];
    uint8_t min[8];
    uint8_t max[8];
    uint16_t changes[8];
} pid_scan_bytes_t;

typedef struct {
    uint16_t asks;
    uint16_t replies;
    uint16_t silent;
    uint16_t negative;
    uint8_t last_nrc;
    uint8_t responders; /* bit n = response_id_min + n answered */
    pid_scan_bytes_t bytes;
} pid_scan_pid_t;

typedef struct {
    uint32_t id;
    bool extended;
    uint32_t count;
    uint32_t first_ms;
    uint32_t last_ms;
    pid_scan_bytes_t bytes;
} pid_scan_id_t;

typedef struct {
    pid_scan_config_t config;
    pid_scan_pid_t pids[256];
    uint8_t claimed[32];      /* bitmap answers, bit set = claimed supported */
    bool bitmap_answered[8];  /* which of 0100, 0120, ... 01E0 replied */
    pid_scan_id_t ids[PID_SCAN_MAX_IDS];
    uint16_t id_count;
    uint32_t ids_overflow;    /* frames from IDs beyond PID_SCAN_MAX_IDS */
    uint32_t frames_dropped;  /* set by the caller, e.g. a full queue */
    int asking;               /* PID currently on the bus, or -1 */
    bool negative_this_ask;
    uint32_t start_ms;
    uint32_t sweeps;          /* full 0x00..0xFF passes done */
    uint32_t watch_loops;     /* passes over the answering PIDs */
} pid_scan_t;

typedef void (*pid_scan_line_fn)(const char *line, void *ctx);

void pid_scan_init(pid_scan_t *scan, const pid_scan_config_t *config, uint32_t now_ms);

/* The request for pid goes out now / is over. answered is what the request
 * mechanism saw; the reply itself arrives through pid_scan_on_frame. */
void pid_scan_on_ask(pid_scan_t *scan, uint8_t pid);
void pid_scan_on_done(pid_scan_t *scan, uint8_t pid, bool answered);

/* Every received CAN frame, OBD replies and broadcast traffic alike. */
void pid_scan_on_frame(pid_scan_t *scan, uint32_t id, bool extended, const uint8_t *data,
                       uint8_t len, uint32_t now_ms);

bool pid_scan_pid_answered(const pid_scan_t *scan, uint8_t pid);
bool pid_scan_pid_claimed(const pid_scan_t *scan, uint8_t pid);

/* Short SAE J1979 name for a Mode 01 PID, or NULL when not in the table. */
const char *pid_scan_pid_name(uint8_t pid);

/* Writes the report one line at a time (no line endings), each under
 * 160 characters. */
void pid_scan_report(const pid_scan_t *scan, uint32_t now_ms, pid_scan_line_fn out, void *ctx);
