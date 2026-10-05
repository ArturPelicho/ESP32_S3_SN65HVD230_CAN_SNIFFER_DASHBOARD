#pragma once

/* Boot diagnostics that survive a power cycle.
 *
 * Each boot records, in NVS, a boot counter, the reset reason and how far
 * start-up got (a stage number). The next boot reads the previous record,
 * so a failed start can be diagnosed afterwards without a laptop at the
 * bike: if the previous boot never reached BOOT_DIAG_STAGE_RUNNING it is
 * counted as failed and its last stage is kept.
 *
 *   counter did not move     -> the firmware never ran (power / EN / boot)
 *   reason BROWNOUT, counter jumps by several -> brown-out reset loop
 *   previous stage < RUNNING -> start-up hung or reset at that stage
 *
 * Writes happen only on stage changes (a handful per boot). Not display or
 * CAN specific: any module can mark its own stage. */

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    BOOT_DIAG_STAGE_NONE = 0,
    BOOT_DIAG_STAGE_APP_START = 1,    /* app_main entered, NVS up */
    BOOT_DIAG_STAGE_TASKS_STARTED = 2,/* CAN, telemetry, display tasks created */
    BOOT_DIAG_STAGE_PANEL_INIT = 3,   /* first ST7789 init done, splash sent */
    BOOT_DIAG_STAGE_SELF_TEST_DONE = 4,
    BOOT_DIAG_STAGE_RUNNING = 5,      /* main display loop, 10 s after boot */
} boot_diag_stage_t;

typedef struct {
    uint32_t boot_count;       /* including this boot */
    uint32_t failed_boots;     /* boots that never reached RUNNING, total */
    uint8_t prev_stage;        /* how far the previous boot got */
    uint8_t prev_reason;       /* esp_reset_reason_t of the previous boot */
    uint8_t reason;            /* esp_reset_reason_t of this boot */
} boot_diag_info_t;

/* Call once from app_main after nvs_flash_init(). */
void boot_diag_init(void);

/* Records progress; stages only move forward. Safe from any task. */
void boot_diag_mark(boot_diag_stage_t stage);

const boot_diag_info_t *boot_diag_info(void);

/* True when the previous boot failed to reach RUNNING or this boot was not
 * a clean power-on, i.e. worth showing on screen. */
bool boot_diag_noteworthy(void);

/* Short upper-case name ("POWERON", "BROWNOUT", ...) for display fonts
 * that only have A-Z, 0-9. */
const char *boot_diag_reason_name(uint8_t reason);
