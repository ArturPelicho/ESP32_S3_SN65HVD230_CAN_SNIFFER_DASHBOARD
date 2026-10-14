#include "gear_learner.h"

#include <math.h>
#include <string.h>

/* Neighbouring gears differ by far more than this ratio factor. */
#define GEAR_MIN_SEPARATION 1.12f
/* Re-acquire a gear after a shift from the last speed for this long. */
#define GEAR_REACQUIRE_STEPS 20
/* A step change in gear speed above this is not acceleration but a shift
 * (or the clutch): 6 % in 100 ms is ~1 g at 60 km/h. */
#define GEAR_JUMP 0.06f

void gear_learner_init(gear_learner_t *g, const gear_learner_config_t *config)
{
    memset(g, 0, sizeof(*g));
    g->config = *config;
    if (g->config.gears == 0) g->config.gears = 1;
    if (g->config.gears > GEAR_MAX) g->config.gears = GEAR_MAX;
}

void gear_learner_load(gear_learner_t *g, const gear_histogram_t *hist)
{
    g->hist = *hist;
    for (int i = 0; i < GEAR_BINS; ++i) {
        if (!(g->hist.seconds[i] >= 0.0f) || g->hist.seconds[i] > 1e7f) g->hist.seconds[i] = 0.0f;
    }
    gear_learner_find(g);
}

void gear_learner_reset(gear_learner_t *g)
{
    memset(&g->hist, 0, sizeof(g->hist));
    g->found = 0;
    g->gear = 0;
    g->dirty = true;
}

void gear_learner_set_gears(gear_learner_t *g, uint8_t gears)
{
    if (gears == 0) gears = 1;
    if (gears > GEAR_MAX) gears = GEAR_MAX;
    g->config.gears = gears;
    gear_learner_find(g);
}

void gear_learner_feed_rpm(gear_learner_t *g, float rpm)
{
    g->rpm_ring[g->rpm_next] = rpm;
    g->rpm_next = (uint8_t)((g->rpm_next + 1) % GEAR_RPM_RING);
    if (g->rpm_count < GEAR_RPM_RING) ++g->rpm_count;
}

static bool rpm_steady(const gear_learner_t *g, float *mean)
{
    if (g->rpm_count < GEAR_RPM_RING) return false;
    float lo = g->rpm_ring[0], hi = lo, sum = 0.0f;
    for (int i = 0; i < GEAR_RPM_RING; ++i) {
        float r = g->rpm_ring[i];
        if (r < lo) lo = r;
        if (r > hi) hi = r;
        sum += r;
    }
    *mean = sum / GEAR_RPM_RING;
    return *mean >= g->config.min_rpm && (hi - lo) <= g->config.max_rpm_spread * *mean;
}

static float latest_rpm(const gear_learner_t *g)
{
    if (g->rpm_count == 0) return 0.0f;
    return g->rpm_ring[(g->rpm_next + GEAR_RPM_RING - 1) % GEAR_RPM_RING];
}

/* Index of the learnt ratio closest to ratio, and its relative error. */
static int nearest(const gear_learner_t *g, float ratio, float *err)
{
    int best = -1;
    float best_err = 1e9f;
    for (int i = 0; i < g->found; ++i) {
        float e = fabsf(ratio / g->ratio[i] - 1.0f);
        if (e < best_err) {
            best_err = e;
            best = i;
        }
    }
    *err = best_err;
    return best;
}

static void engage(gear_learner_t *g, int idx, float rpm)
{
    g->idx = (uint8_t)idx;
    g->gear = g->gear_no[idx];
    g->speed_kmh = rpm / 1000.0f * g->ratio[idx];
    g->last_kmh = g->speed_kmh;
    g->lost_steps = 0;
}

void gear_learner_on_speed(gear_learner_t *g, float speed_kmh, float accuracy_kmh, float dt_s)
{
    float mean;
    bool steady = rpm_steady(g, &mean);
    bool accurate = accuracy_kmh < 0.0f || accuracy_kmh <= g->config.max_speed_acc_kmh;
    if (steady && accurate && speed_kmh >= g->config.min_learn_kmh && dt_s > 0.0f) {
        float ratio = speed_kmh / (mean / 1000.0f);
        int bin = (int)((ratio - GEAR_RATIO_MIN) / GEAR_RATIO_STEP);
        if (bin >= 0 && bin < GEAR_BINS) {
            g->hist.seconds[bin] += dt_s;
            g->dirty = true;
            gear_learner_find(g);
        }
    }
    /* Check the tracked gear against the measurement. Only on steady
     * riding: the phone's speed lags by up to a second, so while
     * accelerating hard it would point at the wrong gear. */
    float rpm = latest_rpm(g);
    if (g->found == 0 || rpm < g->config.min_rpm || speed_kmh < g->config.min_learn_kmh) return;
    if (!steady && g->gear != 0) return;
    float err;
    int idx = nearest(g, speed_kmh / (rpm / 1000.0f), &err);
    if (idx >= 0 && err <= g->config.match_tol) engage(g, idx, rpm);
}

bool gear_learner_track(gear_learner_t *g, float rpm, float *kmh)
{
    if (g->found == 0 || rpm < g->config.min_rpm) {
        if (g->gear != 0) g->lost_steps = 0;
        g->gear = 0;
        if (g->lost_steps < UINT16_MAX) ++g->lost_steps;
        return false;
    }
    if (g->gear != 0) {
        float v = rpm / 1000.0f * g->ratio[g->idx];
        if (g->speed_kmh <= 0.0f || fabsf(v / g->speed_kmh - 1.0f) <= GEAR_JUMP) {
            g->speed_kmh = v;
            g->last_kmh = v;
            *kmh = v;
            return true;
        }
        /* RPM jumped: a shift or the clutch. Lose the gear and look for
         * the one that keeps the speed continuous, below. */
        g->gear = 0;
        g->lost_steps = 0;
    }
    if (g->lost_steps < GEAR_REACQUIRE_STEPS && g->last_kmh >= g->config.min_learn_kmh) {
        float err;
        int idx = nearest(g, g->last_kmh / (rpm / 1000.0f), &err);
        if (idx >= 0 && err <= g->config.match_tol) {
            engage(g, idx, rpm);
            *kmh = g->speed_kmh;
            return true;
        }
    }
    if (g->lost_steps < UINT16_MAX) ++g->lost_steps;
    return false;
}

void gear_learner_find(gear_learner_t *g)
{
    float smooth[GEAR_BINS];
    for (int i = 0; i < GEAR_BINS; ++i) {
        float s = g->hist.seconds[i];
        if (i > 0) s += g->hist.seconds[i - 1];
        if (i + 1 < GEAR_BINS) s += g->hist.seconds[i + 1];
        smooth[i] = s;
    }
    /* Candidate peaks: local maxima with enough data. */
    float cand_w[GEAR_BINS / 2];
    float cand_r[GEAR_BINS / 2];
    int n = 0;
    for (int i = 0; i < GEAR_BINS && n < GEAR_BINS / 2; ++i) {
        float left = i > 0 ? smooth[i - 1] : 0.0f;
        float right = i + 1 < GEAR_BINS ? smooth[i + 1] : 0.0f;
        if (smooth[i] < g->config.min_peak_s || smooth[i] < left || smooth[i] <= right) continue;
        float w = 0.0f, wr = 0.0f;
        for (int j = i - 2; j <= i + 2; ++j) {
            if (j < 0 || j >= GEAR_BINS) continue;
            float h = g->hist.seconds[j];
            w += h;
            wr += h * (GEAR_RATIO_MIN + (j + 0.5f) * GEAR_RATIO_STEP);
        }
        if (w <= 0.0f) continue;
        cand_w[n] = smooth[i];
        cand_r[n] = wr / w;
        ++n;
    }
    /* Strongest first, keeping only peaks well apart from those taken. */
    float ratio[GEAR_MAX];
    int found = 0;
    while (found < g->config.gears) {
        int best = -1;
        for (int i = 0; i < n; ++i) {
            if (cand_w[i] > 0.0f && (best < 0 || cand_w[i] > cand_w[best])) best = i;
        }
        if (best < 0) break;
        bool apart = true;
        for (int k = 0; k < found; ++k) {
            float f = cand_r[best] > ratio[k] ? cand_r[best] / ratio[k] : ratio[k] / cand_r[best];
            if (f < GEAR_MIN_SEPARATION) apart = false;
        }
        if (apart) ratio[found++] = cand_r[best];
        cand_w[best] = 0.0f;
    }
    /* Ascending. */
    for (int i = 1; i < found; ++i) {
        float r = ratio[i];
        int j = i - 1;
        while (j >= 0 && ratio[j] > r) {
            ratio[j + 1] = ratio[j];
            --j;
        }
        ratio[j + 1] = r;
    }
    bool changed = found != g->found;
    for (int i = 0; i < found; ++i) {
        if (g->ratio[i] != ratio[i]) changed = true;
        g->ratio[i] = ratio[i];
        g->gear_no[i] = (uint8_t)(g->config.gears - found + 1 + i);
    }
    g->found = (uint8_t)found;
    if (changed && g->gear != 0) {
        /* Peaks moved: keep the tracked gear only if it still exists. */
        if (g->idx >= found) g->gear = 0;
        else g->gear = g->gear_no[g->idx];
    }
}

float gear_learner_total_s(const gear_learner_t *g)
{
    float sum = 0.0f;
    for (int i = 0; i < GEAR_BINS; ++i) sum += g->hist.seconds[i];
    return sum;
}

bool gear_learner_complete(const gear_learner_t *g)
{
    return g->found >= g->config.gears;
}

bool gear_learner_take_dirty(gear_learner_t *g)
{
    bool d = g->dirty;
    g->dirty = false;
    return d;
}
