#include "inj_meter.h"

#define INJ_METER_MIN_RPM 200.0f /* below this the engine is not running */

static float injections_per_s(const inj_meter_config_t *cfg, float rpm)
{
    if (rpm < INJ_METER_MIN_RPM || cfg->strokes_per_cycle == 0) return 0.0f;
    return rpm / 60.0f / ((float)cfg->strokes_per_cycle / 2.0f) * (float)cfg->cylinders;
}

/* cc/min is 1/60000 L per ms of open injector. */
static float litres_per_open_ms(const inj_meter_config_t *cfg)
{
    return cfg->flow_cc_per_min / 60000.0f / 1000.0f;
}

void inj_meter_add(const inj_meter_config_t *cfg, inj_meter_totals_t *totals,
                   float rpm, float pulse_ms, float dt_ms)
{
    if (dt_ms <= 0.0f || dt_ms > INJ_METER_MAX_GAP_MS) return;
    float per_s = injections_per_s(cfg, rpm);
    if (per_s <= 0.0f) return;
    double count = (double)per_s * dt_ms / 1000.0;
    totals->running_s += dt_ms / 1000.0;
    /* A fuel cut sends pulse 0: no injection happened, so it must not
     * count towards the dead-time correction either. */
    if (pulse_ms <= 0.0f) return;
    totals->injections += count;
    totals->open_ms += count * pulse_ms;
}

float inj_meter_flow_lph(const inj_meter_config_t *cfg, float rpm, float pulse_ms)
{
    float effective_ms = pulse_ms - cfg->dead_time_ms;
    if (pulse_ms <= 0.0f || effective_ms <= 0.0f) return 0.0f;
    return injections_per_s(cfg, rpm) * effective_ms * litres_per_open_ms(cfg) * 3600.0f;
}

double inj_meter_litres(const inj_meter_config_t *cfg, const inj_meter_totals_t *totals)
{
    double effective_ms = totals->open_ms - totals->injections * cfg->dead_time_ms;
    if (effective_ms < 0.0) effective_ms = 0.0;
    return effective_ms * litres_per_open_ms(cfg);
}
