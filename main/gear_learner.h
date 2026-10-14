#pragma once

/* Gear detection from RPM and road speed, learnt while riding.
 *
 * In a given gear, road speed per 1000 rpm is fixed by the gearbox, final
 * drive and tyre. Every steady stretch of riding with a road speed (phone
 * GPS for now) adds its ratio (km/h per 1000 rpm) to a histogram; each gear
 * shows up as a peak. One ride through all gears is normally enough, and
 * the histogram keeps growing across rides (saved by the caller).
 *
 * Once learnt, the tracker gives:
 *   - the gear engaged (1..N, 0 = unknown, clutch in or neutral);
 *   - a speed from RPM alone (rpm x ratio), at the CAN rate, which bridges
 *     the gaps between GPS fixes and keeps working with no GPS at all.
 *     A gear change shows as an RPM jump while the speed is continuous, so
 *     the tracker re-picks the gear that keeps the speed continuous.
 *
 * Pure C with no ESP-IDF dependencies. Not thread safe. */

#include <stdbool.h>
#include <stdint.h>

#define GEAR_MAX 7
#define GEAR_RATIO_MIN 2.0f   /* km/h per 1000 rpm, histogram range */
#define GEAR_RATIO_STEP 0.1f
#define GEAR_BINS 200         /* 2.0 .. 22.0 */
#define GEAR_RPM_RING 20      /* 2 s of RPM at the 100 ms step */

typedef struct {
    uint8_t gears;            /* how many gears the bike has */
    float min_rpm;            /* below: clutch in / idling, no gear */
    float min_learn_kmh;      /* below: clutch slip at take-off */
    float max_rpm_spread;     /* steady = (max-min)/mean RPM over 2 s below this */
    float max_speed_acc_kmh;  /* GPS speed accuracy needed to learn (if known) */
    float min_peak_s;         /* seconds of data for a peak to count */
    float match_tol;          /* |ratio/peak - 1| to accept a gear */
} gear_learner_config_t;

#define GEAR_LEARNER_DEFAULT_CONFIG(n) { \
    .gears = (n),                      \
    .min_rpm = 2000.0f,                \
    .min_learn_kmh = 12.0f,            \
    .max_rpm_spread = 0.04f,           \
    .max_speed_acc_kmh = 3.0f,         \
    .min_peak_s = 8.0f,                \
    .match_tol = 0.08f,                \
}

/* What is saved between rides. */
typedef struct {
    float seconds[GEAR_BINS];
} gear_histogram_t;

typedef struct {
    gear_learner_config_t config;
    gear_histogram_t hist;
    /* Learnt ratios, ascending. With fewer peaks than gears the missing
     * ones are taken to be the lowest (1st is rarely ridden steadily), so
     * the highest peak is always the top gear: gear_no[i] says which. */
    float ratio[GEAR_MAX];
    uint8_t gear_no[GEAR_MAX];
    uint8_t found;            /* peaks found, at most config.gears */
    /* RPM over the last 2 s, for the steadiness check. */
    float rpm_ring[GEAR_RPM_RING];
    uint8_t rpm_count;
    uint8_t rpm_next;
    /* Tracker. */
    uint8_t gear;             /* 0 = unknown */
    uint8_t idx;              /* index into ratio[] of the tracked gear */
    float speed_kmh;          /* rpm x ratio, valid when gear != 0 */
    float last_kmh;           /* last known speed, to re-acquire after a shift */
    uint16_t lost_steps;      /* steps since the gear was lost */
    bool dirty;               /* histogram changed since gear_learner_take_dirty */
} gear_learner_t;

void gear_learner_init(gear_learner_t *g, const gear_learner_config_t *config);
/* Replaces the histogram (loaded from storage) and re-finds the gears. */
void gear_learner_load(gear_learner_t *g, const gear_histogram_t *hist);
void gear_learner_reset(gear_learner_t *g);
void gear_learner_set_gears(gear_learner_t *g, uint8_t gears);

/* Every step (100 ms) with the engine RPM, 0 when unknown. */
void gear_learner_feed_rpm(gear_learner_t *g, float rpm);

/* On each new road speed measurement (one GPS fix). dt_s is the time it
 * stands for (time since the previous fix, capped by the caller).
 * accuracy_kmh < 0 = unknown. Learns when the riding is steady, then
 * re-checks the tracked gear against the measurement. */
void gear_learner_on_speed(gear_learner_t *g, float speed_kmh, float accuracy_kmh, float dt_s);

/* Every step after feed_rpm: follows gear changes from RPM alone. Returns
 * true with the gear speed in *kmh when a gear is engaged. */
bool gear_learner_track(gear_learner_t *g, float rpm, float *kmh);

/* Re-finds the gear peaks in the histogram. Cheap (one pass). */
void gear_learner_find(gear_learner_t *g);

/* Total seconds learnt. */
float gear_learner_total_s(const gear_learner_t *g);
/* All gears found. */
bool gear_learner_complete(const gear_learner_t *g);
bool gear_learner_take_dirty(gear_learner_t *g);
