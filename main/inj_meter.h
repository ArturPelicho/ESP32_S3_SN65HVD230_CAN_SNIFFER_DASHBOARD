#pragma once

/* Fuel measured from the ECU's injection value (bike_can.h, 0x110 B5-B6,
 * rebuilt past its 16-bit wrap).
 *
 * On the ZS125 this value is NOT a pulse time, although it moves with the
 * injection: a week of riding (2026-10-06..13) showed it rising ~15x from
 * 1600 to 8400 rpm while air per cycle only doubles, and read as ms it
 * would hold the injector open 92 % of the cycle at half throttle. Read as
 * a fuel *rate* it is consistent: the volumetric efficiency it implies at
 * lambda 1 is a smooth curve (about 0.6 at 1600 rpm rising to a plateau
 * from 6500 rpm), and fuel cut sends 0. So:
 *
 *   fuel flow (L/h) = lph_per_unit * value
 *
 * value is in the units the dashboard shows (raw / 10000). The constant is
 * calibrated from fill-ups: the trip keeps the integral of value over time,
 * so litres = lph_per_unit * value_seconds / 3600 for any corrected
 * constant, retroactively.
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    float lph_per_unit;       /* L/h of fuel per unit of the ECU's value */
    uint8_t cylinders;        /* only for counting injections */
    uint8_t strokes_per_cycle;
} inj_meter_config_t;

/* Running sums since the last reset; persisted as is. */
typedef struct {
    double injections;        /* engine cycles with fuel, for reference */
    double value_sum;         /* sum of the value per injection, for reference */
    double running_s;         /* time with the engine turning */
    double value_seconds;     /* integral of the value over time: the fuel */
} inj_meter_totals_t;

/* Gaps longer than this between two engine frames (bus loss, key off)
 * are not integrated. */
#define INJ_METER_MAX_GAP_MS 200.0f

/* Adds dt_ms of running at this rpm and value to the totals. */
void inj_meter_add(const inj_meter_config_t *config, inj_meter_totals_t *totals,
                   float rpm, float value, float dt_ms);

/* Instant fuel flow, L/h. 0 on fuel cut (value 0) and with the engine
 * stopped. */
float inj_meter_flow_lph(const inj_meter_config_t *config, float rpm, float value);

/* Litres the totals represent with this config's constant. */
double inj_meter_litres(const inj_meter_config_t *config, const inj_meter_totals_t *totals);
