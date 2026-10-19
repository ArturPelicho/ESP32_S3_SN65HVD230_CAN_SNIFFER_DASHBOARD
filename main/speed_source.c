#include "speed_source.h"

#include <string.h>

void speed_sources_init(speed_sources_t *s)
{
    memset(s, 0, sizeof(*s));
    for (int i = 0; i < SPEED_SRC_COUNT; ++i) s->stale_ms[i] = SPEED_STALE_DEFAULT_MS;
    s->stale_ms[SPEED_SRC_GPS_EXT] = SPEED_STALE_GPS_EXT_MS;
    s->stale_ms[SPEED_SRC_GEAR] = SPEED_STALE_GEAR_MS;
}

void speed_sources_publish(speed_sources_t *s, speed_src_id_t id, float kmh,
                           float accuracy_kmh, uint32_t now_ms)
{
    if ((unsigned)id >= SPEED_SRC_COUNT) return;
    if (kmh < 0.0f) kmh = 0.0f;
    s->src[id] = (speed_reading_t) {
        .kmh = kmh, .accuracy_kmh = accuracy_kmh, .at_ms = now_ms, .ever = true,
    };
}

bool speed_sources_fresh(const speed_sources_t *s, speed_src_id_t id, uint32_t now_ms,
                         float *kmh)
{
    if ((unsigned)id >= SPEED_SRC_COUNT) return false;
    const speed_reading_t *r = &s->src[id];
    if (!r->ever || (uint32_t)(now_ms - r->at_ms) > s->stale_ms[id]) return false;
    if (kmh != NULL) *kmh = r->kmh;
    return true;
}

const char *speed_src_name(speed_src_id_t id)
{
    switch (id) {
    case SPEED_SRC_GPS_EXT: return "GPS_EXT";
    case SPEED_SRC_WHEEL: return "WHEEL";
    case SPEED_SRC_GPS_INT: return "GPS_INT";
    case SPEED_SRC_GEAR: return "GEAR";
    case SPEED_SRC_OBD: return "OBD";
    default: return "?";
    }
}

void speed_select_init(speed_select_t *sel)
{
    memset(sel, 0, sizeof(*sel));
}

bool speed_select_update(speed_select_t *sel, const speed_select_config_t *cfg,
                         const speed_sources_t *s, uint32_t now_ms, float dt_s, bool moving,
                         float *kmh, speed_origin_t *origin)
{
    float v;
    if (speed_sources_fresh(s, cfg->primary, now_ms, &v)) {
        if (!sel->have_avg) {
            sel->avg_kmh = v;
            sel->have_avg = true;
        } else if (cfg->avg_tau_s > 0.0f) {
            float a = dt_s / (cfg->avg_tau_s + dt_s);
            sel->avg_kmh += a * (v - sel->avg_kmh);
        } else {
            sel->avg_kmh = v;
        }
        sel->lost_at_ms = now_ms;
        sel->holding = false;
        *kmh = v;
        *origin = SPEED_ORIGIN_SOURCE;
        return true;
    }
    if (cfg->primary != SPEED_SRC_GEAR && speed_sources_fresh(s, SPEED_SRC_GEAR, now_ms, &v)) {
        /* The gear estimate is a real measurement: it keeps the hold
         * window from running out and refreshes the held average. */
        sel->lost_at_ms = now_ms;
        sel->holding = false;
        *kmh = v;
        *origin = SPEED_ORIGIN_GEAR;
        return true;
    }
    if (moving && sel->have_avg && cfg->hold_max_ms > 0 &&
        (uint32_t)(now_ms - sel->lost_at_ms) <= cfg->hold_max_ms) {
        sel->holding = true;
        *kmh = sel->avg_kmh;
        *origin = SPEED_ORIGIN_HOLD;
        return true;
    }
    if (!moving) {
        /* Stopped or idling: a held cruising speed would be wrong once
         * riding resumes, so start the next hold from fresh data. */
        sel->have_avg = false;
    }
    sel->holding = false;
    *kmh = 0.0f;
    *origin = SPEED_ORIGIN_NONE;
    return false;
}
