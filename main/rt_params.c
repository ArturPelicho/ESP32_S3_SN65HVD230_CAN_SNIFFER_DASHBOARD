#include "rt_params.h"

#include <string.h>

#include "esp_log.h"
#include "nvs.h"
#include "sdkconfig.h"
#include "fuel_estimator_config.h"
#include "speed_source.h"

#define RTP_NVS_NAMESPACE "params"

#ifndef CONFIG_SPEED_HOLD_S
#define CONFIG_SPEED_HOLD_S 120
#endif
#ifndef CONFIG_GEAR_COUNT
#define CONFIG_GEAR_COUNT 5
#endif
#ifndef CONFIG_TANK_CAPACITY_DL
#define CONFIG_TANK_CAPACITY_DL 120
#endif
#ifndef CONFIG_RANGE_BASELINE_X100
#define CONFIG_RANGE_BASELINE_X100 265
#endif
#ifndef CONFIG_DASH_RPM_SHIFT
#define CONFIG_DASH_RPM_SHIFT 10000
#endif

#if defined(CONFIG_SPEED_FUEL_SOURCE_WHEEL)
#define RTP_SPD_SRC_DEFAULT SPEED_SRC_WHEEL
#elif defined(CONFIG_SPEED_FUEL_SOURCE_GPS_INT)
#define RTP_SPD_SRC_DEFAULT SPEED_SRC_GPS_INT
#elif defined(CONFIG_SPEED_FUEL_SOURCE_GEAR)
#define RTP_SPD_SRC_DEFAULT SPEED_SRC_GEAR
#else
#define RTP_SPD_SRC_DEFAULT SPEED_SRC_GPS_EXT
#endif

static const char *TAG = "rt_params";

static const rtp_def_t s_defs[RTP_COUNT] = {
    [RTP_FUEL_K] = { "FUEL_K", 10, 2000, CONFIG_FUEL_INJ_LPH_PER_UNIT_X1000, "L/h x1000 per INJ" },
    [RTP_TANK_DL] = { "TANK_DL", 10, 500, CONFIG_TANK_CAPACITY_DL, "dL" },
    [RTP_SPD_SRC] = { "SPD_SRC", 0, SPEED_SRC_COUNT - 1, RTP_SPD_SRC_DEFAULT,
                      "0 GPS_EXT 1 WHEEL 2 GPS_INT 3 GEAR 4 OBD" },
    [RTP_SPD_HOLD_S] = { "SPD_HOLD_S", 0, 600, CONFIG_SPEED_HOLD_S, "s" },
    [RTP_GEARS] = { "GEARS", 1, 7, CONFIG_GEAR_COUNT, "" },
    [RTP_BASE_L100] = { "BASE_L100", 50, 3000, CONFIG_RANGE_BASELINE_X100, "L/100km x100" },
    [RTP_RPM_SHIFT] = { "RPM_SHIFT", 1000, 20000, CONFIG_DASH_RPM_SHIFT, "rpm" },
};

static volatile int32_t s_values[RTP_COUNT];
static volatile uint32_t s_generation;

/* NVS keys are at most 15 characters; the names are shorter. */
_Static_assert(sizeof("SPD_HOLD_S") <= 16, "NVS key length");

void rt_params_init(void)
{
    for (int i = 0; i < RTP_COUNT; ++i) s_values[i] = s_defs[i].def;
    nvs_handle_t nvs;
    if (nvs_open(RTP_NVS_NAMESPACE, NVS_READONLY, &nvs) != ESP_OK) return;
    for (int i = 0; i < RTP_COUNT; ++i) {
        int32_t v;
        if (nvs_get_i32(nvs, s_defs[i].name, &v) == ESP_OK) {
            if (v >= s_defs[i].min && v <= s_defs[i].max) {
                s_values[i] = v;
                ESP_LOGI(TAG, "%s=%ld (stored)", s_defs[i].name, (long)v);
            }
        }
    }
    nvs_close(nvs);
}

int32_t rt_param(rtp_id_t id)
{
    return (unsigned)id < RTP_COUNT ? s_values[id] : 0;
}

const rtp_def_t *rt_param_def(rtp_id_t id)
{
    return (unsigned)id < RTP_COUNT ? &s_defs[id] : NULL;
}

rtp_id_t rt_param_find(const char *name)
{
    for (int i = 0; i < RTP_COUNT; ++i) {
        if (!strcmp(name, s_defs[i].name)) return (rtp_id_t)i;
    }
    return RTP_COUNT;
}

bool rt_param_set(rtp_id_t id, int32_t value)
{
    if ((unsigned)id >= RTP_COUNT || value < s_defs[id].min || value > s_defs[id].max) return false;
    s_values[id] = value;
    ++s_generation;
    nvs_handle_t nvs;
    if (nvs_open(RTP_NVS_NAMESPACE, NVS_READWRITE, &nvs) == ESP_OK) {
        if (nvs_set_i32(nvs, s_defs[id].name, value) != ESP_OK || nvs_commit(nvs) != ESP_OK) {
            ESP_LOGW(TAG, "%s not saved", s_defs[id].name);
        }
        nvs_close(nvs);
    }
    ESP_LOGI(TAG, "%s=%ld", s_defs[id].name, (long)value);
    return true;
}

uint32_t rt_params_generation(void)
{
    return s_generation;
}
