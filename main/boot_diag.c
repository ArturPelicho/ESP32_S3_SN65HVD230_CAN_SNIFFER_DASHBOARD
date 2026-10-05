#include "boot_diag.h"

#include "esp_log.h"
#include "esp_system.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"

static const char *TAG = "boot_diag";
static const char *NVS_NS = "boot_diag";

static boot_diag_info_t s_info;
static uint8_t s_stage;
static nvs_handle_t s_nvs;
static bool s_nvs_ok;
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;

static void put_u8(const char *key, uint8_t v)
{
    if (!s_nvs_ok) return;
    if (nvs_set_u8(s_nvs, key, v) != ESP_OK || nvs_commit(s_nvs) != ESP_OK) {
        ESP_LOGW(TAG, "write %s failed", key);
    }
}

void boot_diag_init(void)
{
    s_info.reason = (uint8_t)esp_reset_reason();
    s_nvs_ok = nvs_open(NVS_NS, NVS_READWRITE, &s_nvs) == ESP_OK;

    uint32_t count = 0, failed = 0;
    uint8_t prev_stage = BOOT_DIAG_STAGE_NONE, prev_reason = ESP_RST_UNKNOWN;
    if (s_nvs_ok) {
        nvs_get_u32(s_nvs, "count", &count);
        nvs_get_u32(s_nvs, "failed", &failed);
        nvs_get_u8(s_nvs, "stage", &prev_stage);
        nvs_get_u8(s_nvs, "reason", &prev_reason);
    }
    bool have_prev = count > 0;
    if (have_prev && prev_stage < BOOT_DIAG_STAGE_RUNNING) failed++;

    s_info.boot_count = count + 1;
    s_info.failed_boots = failed;
    s_info.prev_stage = have_prev ? prev_stage : BOOT_DIAG_STAGE_RUNNING;
    s_info.prev_reason = prev_reason;

    if (s_nvs_ok) {
        nvs_set_u32(s_nvs, "count", s_info.boot_count);
        nvs_set_u32(s_nvs, "failed", s_info.failed_boots);
        nvs_set_u8(s_nvs, "reason", s_info.reason);
    }
    s_stage = BOOT_DIAG_STAGE_NONE;
    boot_diag_mark(BOOT_DIAG_STAGE_APP_START);

    ESP_LOGI(TAG, "boot %lu, reset %s; previous boot reached stage %u (reset %s); %lu failed boots",
             (unsigned long)s_info.boot_count, boot_diag_reason_name(s_info.reason),
             s_info.prev_stage, boot_diag_reason_name(s_info.prev_reason),
             (unsigned long)s_info.failed_boots);
}

void boot_diag_mark(boot_diag_stage_t stage)
{
    /* Marked from tasks on both cores; only the caller that advances the
     * stage writes it. */
    taskENTER_CRITICAL(&s_lock);
    bool advance = (uint8_t)stage > s_stage;
    if (advance) s_stage = (uint8_t)stage;
    taskEXIT_CRITICAL(&s_lock);
    if (!advance) return;
    put_u8("stage", (uint8_t)stage);
    ESP_LOGI(TAG, "stage %u", (unsigned)stage);
}

const boot_diag_info_t *boot_diag_info(void)
{
    return &s_info;
}

bool boot_diag_noteworthy(void)
{
    return s_info.prev_stage < BOOT_DIAG_STAGE_RUNNING ||
           (s_info.reason != ESP_RST_POWERON && s_info.reason != ESP_RST_SW &&
            s_info.reason != ESP_RST_USB);
}

const char *boot_diag_reason_name(uint8_t reason)
{
    switch ((esp_reset_reason_t)reason) {
    case ESP_RST_POWERON:   return "POWERON";
    case ESP_RST_EXT:       return "EXT PIN";
    case ESP_RST_SW:        return "SOFTWARE";
    case ESP_RST_PANIC:     return "PANIC";
    case ESP_RST_INT_WDT:   return "INT WDT";
    case ESP_RST_TASK_WDT:  return "TASK WDT";
    case ESP_RST_WDT:       return "WDT";
    case ESP_RST_DEEPSLEEP: return "DEEPSLEEP";
    case ESP_RST_BROWNOUT:  return "BROWNOUT";
    case ESP_RST_SDIO:      return "SDIO";
    case ESP_RST_USB:       return "USB";
    case ESP_RST_JTAG:      return "JTAG";
    case ESP_RST_EFUSE:     return "EFUSE";
    case ESP_RST_PWR_GLITCH:return "PWR GLITCH";
    case ESP_RST_CPU_LOCKUP:return "CPU LOCKUP";
    default:                return "UNKNOWN";
    }
}
