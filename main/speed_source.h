#pragma once

/* Road speed from several independent sources, kept side by side.
 *
 * Every source publishes into its own slot and stays visible on its own,
 * so they can be compared and tuned against each other (phone GPS vs wheel
 * sensor vs the gear estimate). One of them is chosen as the speed that
 * feeds the fuel totals; that choice is a runtime parameter, not a code
 * change, and adding a source only needs a new id here.
 *
 * Fuel speed selection (speed_select_update), in order:
 *   1. the chosen source, if fresh;
 *   2. the gear estimate (RPM x learnt ratio), if fresh and not already
 *      the chosen one;
 *   3. while moving, the average of the chosen source over the last
 *      ~20 s, held for up to hold_max_ms (tunnels, GPS drop-outs);
 *   4. nothing: the fuel reading falls back to L/h.
 *
 * Pure C with no ESP-IDF dependencies. Not thread safe: callers lock. */

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    SPEED_SRC_GPS_EXT = 0, /* phone GPS over BLE ("Vel_GPS_Ext") */
    SPEED_SRC_WHEEL,       /* wheel sensor pulses (not wired yet) */
    SPEED_SRC_GPS_INT,     /* GPS module on the board (not fitted yet) */
    SPEED_SRC_GEAR,        /* RPM x learnt gear ratio (gear_learner.h) */
    SPEED_SRC_OBD,         /* OBD PID 0x0D (always 0 on the ZS125) */
    SPEED_SRC_COUNT
} speed_src_id_t;

typedef struct {
    float kmh;
    float accuracy_kmh; /* < 0 when the source doesn't say */
    uint32_t at_ms;     /* when it was published */
    bool ever;          /* published at least once */
} speed_reading_t;

typedef struct {
    speed_reading_t src[SPEED_SRC_COUNT];
    uint32_t stale_ms[SPEED_SRC_COUNT]; /* older than this = not fresh */
} speed_sources_t;

/* Staleness defaults: phone GPS arrives at ~1 Hz, so it may miss one fix. */
#define SPEED_STALE_GPS_EXT_MS 2500
#define SPEED_STALE_DEFAULT_MS 1000
#define SPEED_STALE_GEAR_MS 400

void speed_sources_init(speed_sources_t *s);
void speed_sources_publish(speed_sources_t *s, speed_src_id_t id, float kmh,
                           float accuracy_kmh, uint32_t now_ms);
/* True and *kmh set when the source published within its stale time. */
bool speed_sources_fresh(const speed_sources_t *s, speed_src_id_t id, uint32_t now_ms,
                         float *kmh);
const char *speed_src_name(speed_src_id_t id);

typedef enum {
    SPEED_ORIGIN_NONE = 0,
    SPEED_ORIGIN_SOURCE, /* the chosen source itself */
    SPEED_ORIGIN_GEAR,   /* gear estimate standing in */
    SPEED_ORIGIN_HOLD,   /* recent average held through a drop-out */
} speed_origin_t;

typedef struct {
    speed_src_id_t primary;
    uint32_t hold_max_ms; /* 0 disables holding */
    float avg_tau_s;      /* averaging time constant of the held value */
} speed_select_config_t;

typedef struct {
    float avg_kmh;
    bool have_avg;
    uint32_t lost_at_ms; /* when the last real speed was seen */
    bool holding;
} speed_select_t;

void speed_select_init(speed_select_t *sel);

/* dt_s drives the average; moving says the engine is pulling (by RPM),
 * which is the only time a held speed makes sense. Returns true with *kmh
 * and *origin set when some speed is available. */
bool speed_select_update(speed_select_t *sel, const speed_select_config_t *cfg,
                         const speed_sources_t *s, uint32_t now_ms, float dt_s, bool moving,
                         float *kmh, speed_origin_t *origin);
