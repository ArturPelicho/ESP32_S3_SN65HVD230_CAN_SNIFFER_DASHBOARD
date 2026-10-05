#pragma once

/* Decoder for the frames the bike's ECU broadcasts on its own, without
 * being asked (ZongShen ZS125GY-13-E55, found with the PID scan):
 *
 *   0x110, every ~9 ms  B2-B3  engine speed, RPM x 4 (same raw as PID 0C)
 *                       B4     an engine temperature (scale not yet known)
 *                       B5-B6  injection pulse width, 0.1 us per bit, low
 *                              16 bits only (see bike_can_unwrap_inj);
 *                              0 on fuel cut and engine off
 *                       B7     status bits
 *   0x111, every ~9 ms  B6     battery voltage, 0.1 V per bit (decays to
 *                              0 for a few seconds after engine stop)
 *
 * These arrive ten times faster than a polled PID and cost no bus time.
 * Pure C with no ESP-IDF dependencies; IDs and byte positions are in the
 * config so another bike only needs a different table. Fields whose
 * meaning is still a guess are exposed raw and marked so in the name. */

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint32_t engine_id;   /* 0x110 */
    uint32_t power_id;    /* 0x111 */
    uint8_t rpm_byte;     /* first (high) byte of the big-endian RPM x 4 */
    uint8_t temp_byte;
    uint8_t inj_byte;     /* first (high) byte of the big-endian 16-bit value */
    uint8_t status_byte;
    uint8_t battery_byte;
} bike_can_config_t;

#define BIKE_CAN_ZS125_CONFIG() {   \
    .engine_id = 0x110,             \
    .power_id = 0x111,              \
    .rpm_byte = 2,                  \
    .temp_byte = 4,                 \
    .inj_byte = 5,                  \
    .status_byte = 7,               \
    .battery_byte = 6,              \
}

typedef struct {
    bool engine_valid;      /* an engine frame has been decoded */
    uint16_t rpm;
    uint8_t temp_raw;
    uint16_t inj_raw;       /* as sent: low 16 bits of the pulse, 0.1 us per bit */
    uint32_t inj_tenth_us;  /* pulse width rebuilt from inj_raw, 0.1 us per bit */
    uint8_t inj_wraps;      /* times it went past 16 bits, BIKE_CAN_INJ_UNKNOWN if
                             * inj_raw fits no known pattern (then = inj_raw) */
    uint8_t status;
    bool battery_valid;
    uint16_t battery_mv;
} bike_can_data_t;

/* The ECU computes the pulse in 0.1 ms steps but sends it in 0.1 us units
 * truncated to 16 bits, so it wraps every 6.5536 ms (seen on the road:
 * the value climbs to ~6.5 ms, restarts near 0 and keeps climbing).
 * True values are multiples of 1000; 65536 is not, so the last three
 * digits of what arrives tell how many times it wrapped:
 *   ...000 -> 0, ...464 -> 1, ...928 -> 2, ...392 -> 3 (up to 26.2 ms). */
#define BIKE_CAN_INJ_STEP 1000U
#define BIKE_CAN_INJ_MAX_WRAPS 3
#define BIKE_CAN_INJ_UNKNOWN 0xFF
uint32_t bike_can_unwrap_inj(uint16_t raw, uint8_t *wraps);

/* Battery readings below this are the ECU powering down, not a battery. */
#define BIKE_CAN_BATTERY_MIN_MV 5000

enum {
    BIKE_CAN_NONE = 0,
    BIKE_CAN_ENGINE = 1 << 0,
    BIKE_CAN_POWER = 1 << 1,
};

/* Decodes one received frame into data. Returns which group it updated
 * (BIKE_CAN_ENGINE / BIKE_CAN_POWER), or BIKE_CAN_NONE for other frames
 * and frames too short to hold the fields. */
int bike_can_decode(const bike_can_config_t *config, uint32_t id, bool extended,
                    const uint8_t *data, uint8_t len, bike_can_data_t *out);
