#include "bike_can.h"

static uint16_t be16(const uint8_t *data, uint8_t at)
{
    return (uint16_t)((data[at] << 8) | data[at + 1]);
}

int bike_can_decode(const bike_can_config_t *cfg, uint32_t id, bool extended,
                    const uint8_t *data, uint8_t len, bike_can_data_t *out)
{
    if (extended) return BIKE_CAN_NONE;

    if (id == cfg->engine_id) {
        uint8_t need = cfg->rpm_byte + 2;
        if (cfg->temp_byte + 1 > need) need = cfg->temp_byte + 1;
        if (cfg->inj_byte + 2 > need) need = cfg->inj_byte + 2;
        if (cfg->status_byte + 1 > need) need = cfg->status_byte + 1;
        if (len < need) return BIKE_CAN_NONE;
        out->rpm = be16(data, cfg->rpm_byte) / 4;
        out->temp_raw = data[cfg->temp_byte];
        out->inj_raw = be16(data, cfg->inj_byte);
        out->status = data[cfg->status_byte];
        out->engine_valid = true;
        return BIKE_CAN_ENGINE;
    }

    if (id == cfg->power_id) {
        if (len < cfg->battery_byte + 1) return BIKE_CAN_NONE;
        out->battery_mv = (uint16_t)(data[cfg->battery_byte] * 100);
        /* For a few seconds after the engine stops the ECU keeps sending
         * this frame with a value decaying towards 0 V; nothing below a
         * few volts is a real battery reading. */
        out->battery_valid = out->battery_mv >= BIKE_CAN_BATTERY_MIN_MV;
        return BIKE_CAN_POWER;
    }

    return BIKE_CAN_NONE;
}
