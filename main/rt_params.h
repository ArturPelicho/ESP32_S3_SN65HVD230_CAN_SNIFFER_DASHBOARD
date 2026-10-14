#pragma once

/* Settings that can be changed while running (phone app, BLE "PARAM SET")
 * and are kept in NVS. menuconfig gives the defaults, so a board with an
 * empty NVS behaves exactly as the build was configured.
 *
 * Integer values with a fixed scale per parameter (named in the table),
 * so no float parsing or printing is needed. Reads are lock-free (aligned
 * 32-bit). */

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    RTP_FUEL_K = 0,     /* L/h per INJ unit x1000 */
    RTP_TANK_DL,        /* tank capacity, decilitres */
    RTP_SPD_SRC,        /* speed_src_id_t feeding the fuel totals */
    RTP_SPD_HOLD_S,     /* hold of the average speed through drop-outs */
    RTP_GEARS,          /* number of gears */
    RTP_BASE_L100,      /* range baseline, L/100km x100 */
    RTP_RPM_SHIFT,      /* shift light */
    RTP_COUNT
} rtp_id_t;

typedef struct {
    const char *name;   /* upper case: BLE commands are upper-cased */
    int32_t min, max, def;
    const char *unit;
} rtp_def_t;

/* Loads stored values (out-of-range ones fall back to the default). */
void rt_params_init(void);
int32_t rt_param(rtp_id_t id);
const rtp_def_t *rt_param_def(rtp_id_t id);
/* Case-sensitive name lookup, RTP_COUNT if unknown. */
rtp_id_t rt_param_find(const char *name);
/* Validates, stores to NVS and bumps the change counter. */
bool rt_param_set(rtp_id_t id, int32_t value);
/* Increments on every change, so owners can re-apply cheaply. */
uint32_t rt_params_generation(void);
