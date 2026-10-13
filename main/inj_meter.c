#include "inj_meter.h"

#define INJ_METER_MIN_RPM 200.0f /* below this the engine is not running */

static float injections_per_s(const inj_meter_config_t *cfg, float rpm)
{
    if (rpm < INJ_METER_MIN_RPM || cfg->strokes_per_cycle == 0) return 0.0f;
    return rpm / 60.0f / ((float)cfg->strokes_per_cycle / 2.0f) * (float)cfg->cylinders;
}

void inj_meter_add(const inj_meter_config_t *cfg, inj_meter_totals_t *totals,
                   float rpm, float value, float dt_ms)
{
    if (dt_ms <= 0.0f || dt_ms > INJ_METER_MAX_GAP_MS) return;
    float per_s = injections_per_s(cfg, rpm);
    if (per_s <= 0.0f) return;
    double dt_s = dt_ms / 1000.0;
    totals->running_s += dt_s;
    if (value <= 0.0f) return; /* fuel cut */
    double count = (double)per_s * dt_s;
    totals->injections += count;
    totals->value_sum += count * value;
    totals->value_seconds += (double)value * dt_s;
}

float inj_meter_flow_lph(const inj_meter_config_t *cfg, float rpm, float value)
{
    if (rpm < INJ_METER_MIN_RPM || value <= 0.0f) return 0.0f;
    return cfg->lph_per_unit * value;
}

double inj_meter_litres(const inj_meter_config_t *cfg, const inj_meter_totals_t *totals)
{
    return cfg->lph_per_unit * totals->value_seconds / 3600.0;
}
