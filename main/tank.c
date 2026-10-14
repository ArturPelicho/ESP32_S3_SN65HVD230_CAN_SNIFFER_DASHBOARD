#include "tank.h"

#include <string.h>

static double litres(double value_s, float k)
{
    return (double)k * value_s / 3600.0;
}

void tank_init(tank_state_t *t)
{
    memset(t, 0, sizeof(*t));
    t->magic = TANK_MAGIC;
}

bool tank_is_valid(const tank_state_t *t)
{
    return t->magic == TANK_MAGIC && t->value_s_since_fill >= 0.0 && t->km_since_fill >= 0.0 &&
           t->level_at_fill_l >= 0.0f && t->level_at_fill_l < 1000.0f;
}

void tank_add(tank_state_t *t, double value_s, double km)
{
    if (value_s > 0.0) {
        t->value_s_since_fill += value_s;
        t->value_s_since_full += value_s;
    }
    if (km > 0.0) {
        t->km_since_fill += km;
        t->km_since_full += km;
    }
}

bool tank_fill(tank_state_t *t, float litres_in, bool full, float capacity_l, float k_lph_per_unit,
               float *suggested_k, float *estimated_l)
{
    bool suggested = false;
    if (full && t->full_ref) {
        double est = litres(t->value_s_since_full, k_lph_per_unit);
        if (estimated_l != NULL) *estimated_l = (float)est;
        /* Below half a litre the pump's rounding dominates. */
        if (est >= 0.5 && litres_in > 0.0f) {
            if (suggested_k != NULL) *suggested_k = (float)(k_lph_per_unit * litres_in / est);
            suggested = true;
        }
    }
    float remaining = 0.0f;
    if (t->level_known) {
        remaining = t->level_at_fill_l - (float)litres(t->value_s_since_fill, k_lph_per_unit);
        if (remaining < 0.0f) remaining = 0.0f;
    }
    float level = full ? capacity_l : remaining + litres_in;
    if (capacity_l > 0.0f && level > capacity_l) level = capacity_l;
    t->level_at_fill_l = level;
    t->level_known = full || t->level_known;
    t->value_s_since_fill = 0.0;
    t->km_since_fill = 0.0;
    if (full) {
        t->full_ref = true;
        t->value_s_since_full = 0.0;
        t->km_since_full = 0.0;
    }
    return suggested;
}

void tank_set_level(tank_state_t *t, float level)
{
    if (level < 0.0f) level = 0.0f;
    t->level_at_fill_l = level;
    t->level_known = true;
    t->value_s_since_fill = 0.0;
    t->km_since_fill = 0.0;
}

void tank_view(const tank_state_t *t, float k, float baseline_l_per_100km, tank_view_t *out)
{
    memset(out, 0, sizeof(*out));
    out->used_since_fill_l = (float)litres(t->value_s_since_fill, k);
    out->km_since_fill = (float)t->km_since_fill;
    out->avg_valid = t->km_since_fill >= TANK_AVG_MIN_KM;
    if (out->avg_valid) {
        out->avg_l_per_100km = out->used_since_fill_l / out->km_since_fill * 100.0f;
    }
    out->level_known = t->level_known;
    if (!t->level_known) return;
    out->remaining_l = t->level_at_fill_l - out->used_since_fill_l;
    if (out->remaining_l < 0.0f) out->remaining_l = 0.0f;
    float base = baseline_l_per_100km > 0.0f ? baseline_l_per_100km : 0.0f;
    float per100 = (base * TANK_BLEND_KM + out->used_since_fill_l * 100.0f) /
                   (TANK_BLEND_KM + out->km_since_fill);
    out->range_l_per_100km = per100;
    if (per100 > 0.0f) {
        out->range_valid = true;
        out->range_km = out->remaining_l / per100 * 100.0f;
    }
}
