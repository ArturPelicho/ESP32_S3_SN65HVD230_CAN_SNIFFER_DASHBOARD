#pragma once

/* Fuel measured from the injector pulse width the ECU broadcasts
 * (bike_can.h, 0x110 B5-B6), instead of estimated from MAP and a guessed
 * volumetric efficiency.
 *
 *   injections per second = rpm / 60 / (strokes / 2) * cylinders
 *   fuel per injection    = flow * (pulse - dead time)
 *
 * The injector's static flow and its dead time (the part of each pulse
 * spent opening, which delivers almost nothing) are the two calibration
 * constants. The totals keep the raw sums (injections and summed pulse
 * time) rather than litres, so a better flow or dead time found later
 * from fill-ups applies retroactively:
 *
 *   litres = flow * (open_ms - injections * dead_ms)
 *
 * Two fill-ups with different riding give two equations, enough to solve
 * for both constants.
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    float flow_cc_per_min;   /* injector static flow at the bike's rail pressure */
    float dead_time_ms;      /* per pulse, subtracted before applying flow */
    uint8_t cylinders;
    uint8_t strokes_per_cycle;
} inj_meter_config_t;

/* Running sums since the last reset; persisted as is. */
typedef struct {
    double injections;
    double open_ms;          /* sum of pulse widths, dead time included */
    double running_s;        /* time with the engine turning */
} inj_meter_totals_t;

/* Gaps longer than this between two engine frames (bus loss, key off)
 * are not integrated. */
#define INJ_METER_MAX_GAP_MS 200.0f

/* Adds dt_ms of running at this rpm and pulse width to the totals. */
void inj_meter_add(const inj_meter_config_t *config, inj_meter_totals_t *totals,
                   float rpm, float pulse_ms, float dt_ms);

/* Instant fuel flow for the whole engine, L/h. 0 on fuel cut (pulse 0)
 * and with the engine stopped. */
float inj_meter_flow_lph(const inj_meter_config_t *config, float rpm, float pulse_ms);

/* Litres the totals represent with this config's flow and dead time. */
double inj_meter_litres(const inj_meter_config_t *config, const inj_meter_totals_t *totals);
