#pragma once

/* Fuel tank level, range and fill-up calibration.
 *
 * Fuel is kept as the integral of the ECU's injection value over time
 * (value_s, see inj_meter.h), not as litres, so a corrected fuel constant
 * applies retroactively: litres = constant * value_s / 3600.
 *
 * Two counters run side by side:
 *   - since the last fill of any kind: remaining = level at that fill -
 *     litres used since, and the since-fill average for the range;
 *   - since the last fill to full: at the next fill to full, the litres
 *     the pump put in are exactly the fuel used in between, which gives a
 *     corrected fuel constant (suggested, never applied on its own).
 *
 * Pure C with no ESP-IDF dependencies. The caller persists tank_state_t. */

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint32_t magic;            /* TANK_MAGIC when valid */
    bool level_known;          /* a fill has been recorded */
    bool full_ref;             /* a fill to full has been recorded */
    float level_at_fill_l;
    double value_s_since_fill;
    double km_since_fill;
    double value_s_since_full;
    double km_since_full;
} tank_state_t;

#define TANK_MAGIC 0x544E4B31u /* "TNK1" */

typedef struct {
    bool level_known;
    float remaining_l;
    float used_since_fill_l;
    float km_since_fill;
    bool avg_valid;            /* enough distance since the fill */
    float avg_l_per_100km;     /* since the fill */
    float range_l_per_100km;   /* what the range is computed with */
    bool range_valid;
    float range_km;
} tank_view_t;

/* Since-fill average is reported once this much distance is covered. */
#define TANK_AVG_MIN_KM 20.0f
/* The range consumption blends the baseline with the since-fill average,
 * the baseline weighing as much as this many km of riding:
 *   (baseline * TANK_BLEND_KM + litres since fill * 100) / (TANK_BLEND_KM + km)
 * so it follows the riding from the first metres without jumping, and is
 * mostly the real average after a couple of hundred km. */
#define TANK_BLEND_KM 50.0f
/* At a fill, the blended value becomes the new baseline when at least
 * this much was ridden since the previous fill. */
#define TANK_LEARN_MIN_KM 30.0f

void tank_init(tank_state_t *t);
bool tank_is_valid(const tank_state_t *t);

/* Each step: value_s fuel integral added and km travelled in it. */
void tank_add(tank_state_t *t, double value_s, double km);

/* A fill-up of litres. full: the tank is now full (the level becomes
 * capacity_l). With a previous fill to full, *suggested_k (when non-NULL)
 * gets the fuel constant that would have made the two match, and the
 * function returns true. */
bool tank_fill(tank_state_t *t, float litres, bool full, float capacity_l, float k_lph_per_unit,
               float *suggested_k, float *estimated_l);

/* Sets the level directly (e.g. read off the stock gauge). */
void tank_set_level(tank_state_t *t, float litres);

/* baseline_l_per_100km: see TANK_BLEND_KM. */
void tank_view(const tank_state_t *t, float k_lph_per_unit, float baseline_l_per_100km,
               tank_view_t *out);
