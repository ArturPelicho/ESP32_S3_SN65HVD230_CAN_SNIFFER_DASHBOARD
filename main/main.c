#include <ctype.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_err.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_twai.h"
#include "esp_twai_onchip.h"
#include "can_bus_twai.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "mota_can";

#define CAN_TX_GPIO 17
#define CAN_RX_GPIO 18
#define CAN_BITRATE 500000
#define CAN_QUEUE_LENGTH 32
#define OBD_QUEUE_LENGTH 16
#define BLE_COMMAND_QUEUE_LENGTH 8
#define BLE_COMMAND_LENGTH 96

/* GMT020-02-7P, ST7789 240x320 SPI panel, mounted landscape (rotated 90
 * degrees) in the new case. */
#define TFT_MOSI_GPIO 11
#define TFT_SCLK_GPIO 12
#define TFT_CS_GPIO 10
#define TFT_DC_GPIO 38
#define TFT_RST_GPIO 40
#define TFT_WIDTH 320
#define TFT_HEIGHT 240
#define TFT_PIXEL_CLOCK_HZ (20 * 1000 * 1000)
#define TFT_REFRESH_PERIOD_MS 100
#define TFT_TEMP_PERIOD_MS 100
#define TFT_FAST_PERIOD_MS 100
#define TFT_SLOW_PERIOD_MS 500
#define OBD_RESPONSE_TIMEOUT_MS 150
#define TFT_DATA_TIMEOUT_MS 1200

static twai_node_handle_t s_twai;
static can_bus_supervisor_handle_t s_can_bus;
static QueueHandle_t s_can_queue;
static QueueHandle_t s_obd_queue;
static SemaphoreHandle_t s_can_mutex;
static uint16_t s_ble_conn = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_ble_notify_handle;
static uint16_t s_ble_hm10_handle;
static bool s_monitor_mode;
static bool s_echo = true;
static uint8_t s_own_addr_type;
static bool s_ble_notify_ready;
static QueueHandle_t s_ble_command_queue;
static char s_command_buffer[BLE_COMMAND_LENGTH];
static size_t s_command_length;
static esp_lcd_panel_handle_t s_tft_panel;
static esp_lcd_panel_io_handle_t s_tft_io;
static bool s_tft_ready;

#define TFT_TELEMETRY_ROWS 18

struct telemetry_row {
    uint8_t pid;
    const char *label;
    uint32_t period_ms;
    uint16_t color;
    bool hidden; /* documents rows tft_render() currently has no place for; still polled either way */
};

/* Index 0 is the engine coolant temperature, rendered as a standalone hero
 * row. The rest of the visible rows are hand-placed into named sections in
 * tft_render() (TRIMS, LAMBDAS, ADMISSION) rather than a generic grid, so
 * this table's order no longer determines on-screen layout - tft_render()
 * looks rows up by PID via row_index_for_pid().
 *
 * SPD, FRT, VLT, OIL, FUL and AMB are hidden because this ECU never
 * returns data for them. LOD is hidden because the current screen layout
 * has no place for it. All are still polled every cycle and left in this
 * table (not deleted) so they're one flag away from coming back. */
static const struct telemetry_row s_rows[TFT_TELEMETRY_ROWS] = {
    { 0x05, "TEMP", 100,  0xFFFF, false },
    { 0x0C, "RPM",  100,  0x07E0, false },
    { 0x0D, "SPD",  200,  0xFFFF, true },
    { 0x11, "THR",  100,  0xFFFF, false },
    { 0x0E, "SPK",  100,  0xF81F, false },
    { 0x06, "SHORT", 100, 0xF81F, false },
    { 0x07, "LONG",  100, 0xF81F, false },
    { 0x14, "O2-1", 200,  0xFFFF, false },
    { 0x15, "O2-2", 200,  0xFFFF, false },
    { 0x04, "LOD",  300,  0x07FF, true },
    { 0x0B, "MAP",  200,  0xFFFF, false },
    { 0x33, "BAR",  2000, 0xFFFF, false },
    { 0x0F, "AIR",  500,  0xFFE0, false },
    { 0x5C, "OIL",  500,  0xFD20, true },
    { 0x5E, "FRT",  500,  0xFFE0, true },
    { 0x2F, "FUL",  1000, 0xFFE0, true },
    { 0x42, "VLT",  1000, 0x07E0, true },
    { 0x46, "AMB",  2000, 0x07FF, true },
};

static int s_row_values[TFT_TELEMETRY_ROWS];
static bool s_row_valid[TFT_TELEMETRY_ROWS];
static TickType_t s_last_response;
/* Blocks telemetry_task until display_task's boot self-test (which forces
 * a fake critical reading to visually confirm the alert flash) is done
 * writing to s_row_values/s_row_valid, so real CAN data doesn't race with
 * and immediately overwrite the self-test values. */
static volatile bool s_self_test_active = true;

static int gap_event(struct ble_gap_event *event, void *arg);
static bool obd_query_pid(uint8_t pid, char *response, size_t response_size);

struct can_packet {
    uint32_t id;
    uint32_t extended;
    uint32_t rtr;
    uint8_t len;
    uint8_t data[8];
};

static uint16_t s_tft_pixels[TFT_WIDTH * TFT_HEIGHT];

static const uint8_t *glyph_for(char c)
{
    static const uint8_t blank[5] = {0, 0, 0, 0, 0};
    static const uint8_t digits[][5] = {
        {0x3E,0x51,0x49,0x45,0x3E}, {0x00,0x42,0x7F,0x40,0x00},
        {0x42,0x61,0x51,0x49,0x46}, {0x21,0x41,0x45,0x4B,0x31},
        {0x18,0x14,0x12,0x7F,0x10}, {0x27,0x45,0x45,0x45,0x39},
        {0x3C,0x4A,0x49,0x49,0x30}, {0x01,0x71,0x09,0x05,0x03},
        {0x36,0x49,0x49,0x49,0x36}, {0x06,0x49,0x49,0x29,0x1E}
    };
    static const uint8_t letters[][5] = {
        {0x7E,0x11,0x11,0x11,0x7E}, {0x7F,0x49,0x49,0x49,0x36},
        {0x3E,0x41,0x41,0x41,0x22}, {0x7F,0x41,0x41,0x22,0x1C},
        {0x7F,0x49,0x49,0x49,0x41}, {0x7F,0x09,0x09,0x09,0x01},
        {0x3E,0x41,0x49,0x49,0x7A}, {0x7F,0x08,0x08,0x08,0x7F},
        {0x00,0x41,0x7F,0x41,0x00}, {0x20,0x40,0x41,0x3F,0x01},
        {0x7F,0x08,0x14,0x22,0x41}, {0x7F,0x40,0x40,0x40,0x40},
        {0x7F,0x02,0x0C,0x02,0x7F}, {0x7F,0x04,0x08,0x10,0x7F},
        {0x3E,0x41,0x41,0x41,0x3E}, {0x7F,0x09,0x09,0x09,0x06},
        {0x3E,0x41,0x51,0x21,0x5E}, {0x7F,0x09,0x19,0x29,0x46},
        {0x46,0x49,0x49,0x49,0x31}, {0x01,0x01,0x7F,0x01,0x01},
        {0x3F,0x40,0x40,0x40,0x3F}, {0x1F,0x20,0x40,0x20,0x1F},
        {0x7F,0x20,0x18,0x20,0x7F}, {0x63,0x14,0x08,0x14,0x63},
        {0x07,0x08,0x70,0x08,0x07}, {0x61,0x51,0x49,0x45,0x43}
    };
    if (c >= '0' && c <= '9') return digits[c - '0'];
    if (c >= 'A' && c <= 'Z') return letters[c - 'A'];
    if (c == '-') { static const uint8_t minus[5] = {0x08,0x08,0x08,0x08,0x08}; return minus; }
    if (c == '.') { static const uint8_t period[5] = {0x00,0x00,0x60,0x60,0x00}; return period; }
    return blank;
}

static void tft_clear(uint16_t color)
{
    for (size_t i = 0; i < sizeof(s_tft_pixels) / sizeof(s_tft_pixels[0]); ++i) {
        s_tft_pixels[i] = color;
    }
}

static void tft_text(int x, int y, const char *text, uint16_t color, int scale)
{
    for (; *text != '\0'; ++text, x += 6 * scale) {
        const uint8_t *glyph = glyph_for((char)toupper((unsigned char)*text));
        for (int col = 0; col < 5; ++col) {
            for (int row = 0; row < 7; ++row) {
                if ((glyph[col] >> row) & 1) {
                    for (int dx = 0; dx < scale; ++dx) {
                        for (int dy = 0; dy < scale; ++dy) {
                            int px = x + col * scale + dx;
                            int py = y + row * scale + dy;
                            if (px >= 0 && px < TFT_WIDTH && py >= 0 && py < TFT_HEIGHT) {
                                s_tft_pixels[py * TFT_WIDTH + px] = color;
                            }
                        }
                    }
                }
            }
        }
    }
}

static void tft_flush(void)
{
    if (s_tft_ready) {
        /* The panel expects each RGB565 pixel big-endian (high byte first)
         * over SPI; our framebuffer is native little-endian, which was
         * silently reinterpreting color channels (blue rendering as green,
         * yellow as lilac). Byte-swap the whole frame just before sending;
         * it is safe to leave it swapped since tft_clear() overwrites the
         * entire buffer at the start of the next frame. */
        size_t pixel_count = sizeof(s_tft_pixels) / sizeof(s_tft_pixels[0]);
        for (size_t i = 0; i < pixel_count; ++i) {
            uint16_t v = s_tft_pixels[i];
            s_tft_pixels[i] = (uint16_t)((v << 8) | (v >> 8));
        }
        esp_lcd_panel_draw_bitmap(s_tft_panel, 0, 0, TFT_WIDTH, TFT_HEIGHT,
                                  s_tft_pixels);
    }
}

/* Draws each glyph twice, offset by one pixel, to approximate a bold weight
 * without a second font. */
static void tft_text_bold(int x, int y, const char *text, uint16_t color, int scale)
{
    tft_text(x, y, text, color, scale);
    tft_text(x + 1, y, text, color, scale);
}

static void tft_fill_rect(int x, int y, int w, int h, uint16_t color)
{
    for (int j = 0; j < h; ++j) {
        int py = y + j;
        if (py < 0 || py >= TFT_HEIGHT) continue;
        for (int i = 0; i < w; ++i) {
            int px = x + i;
            if (px < 0 || px >= TFT_WIDTH) continue;
            s_tft_pixels[py * TFT_WIDTH + px] = color;
        }
    }
}

static void tft_rect(int x, int y, int w, int h, uint16_t color, int thickness)
{
    for (int t = 0; t < thickness; ++t) {
        int xx = x + t, yy = y + t, ww = w - 2 * t, hh = h - 2 * t;
        if (ww <= 0 || hh <= 0) break;
        for (int i = 0; i < ww; ++i) {
            int px = xx + i;
            if (px < 0 || px >= TFT_WIDTH) continue;
            if (yy >= 0 && yy < TFT_HEIGHT) s_tft_pixels[yy * TFT_WIDTH + px] = color;
            int by = yy + hh - 1;
            if (by >= 0 && by < TFT_HEIGHT) s_tft_pixels[by * TFT_WIDTH + px] = color;
        }
        for (int j = 0; j < hh; ++j) {
            int py = yy + j;
            if (py < 0 || py >= TFT_HEIGHT) continue;
            if (xx >= 0 && xx < TFT_WIDTH) s_tft_pixels[py * TFT_WIDTH + xx] = color;
            int bx = xx + ww - 1;
            if (bx >= 0 && bx < TFT_WIDTH) s_tft_pixels[py * TFT_WIDTH + bx] = color;
        }
    }
}

/* Issues the ST7789's reset/init/orientation command sequence on the
 * already-created s_tft_panel/s_tft_io handles. Split out from tft_start()
 * so it can be re-run later (see the re-init call in display_task): if the
 * supply is still sagging from engine cranking when tft_start() first runs,
 * these SPI commands can land while the rail is unstable and leave the
 * panel initialized incorrectly (backlight on, nothing drawn) until power
 * is cycled. Re-issuing the same commands once more after cranking has
 * surely finished self-heals that without needing a manual power cycle. */
static void tft_panel_init_sequence(void)
{
    ESP_ERROR_CHECK(esp_lcd_panel_reset(s_tft_panel));
    ESP_ERROR_CHECK(esp_lcd_panel_init(s_tft_panel));
    /* Some ST7789 modules default to 18-bit/pixel and bleed channels into
     * each other (pure red rendering as yellow/white, pure blue as violet)
     * unless COLMOD is forced back to 16-bit RGB565. */
    uint8_t colmod_param = 0x55;
    esp_lcd_panel_io_tx_param(s_tft_io, 0x3A, &colmod_param, 1);
    /* BGR order and color inversion were tried and ruled out; the actual
     * cause was a pixel byte-order mismatch, now fixed in tft_flush(). */
    ESP_ERROR_CHECK(esp_lcd_panel_invert_color(s_tft_panel, true));
    /* The new case mounts the panel rotated 90 degrees clockwise compared
     * to the old one, so the image is rotated 90 degrees counter-clockwise
     * here to compensate and land upright again (320x240 landscape). If it
     * comes up mirrored/sideways on this specific unit, try swapping the
     * mirror_y argument below to true<->false first. */
    ESP_ERROR_CHECK(esp_lcd_panel_swap_xy(s_tft_panel, true));
    ESP_ERROR_CHECK(esp_lcd_panel_mirror(s_tft_panel, false, true));
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(s_tft_panel, true));
}

static void tft_start(void)
{
    /* On a cold start (bike ignition just turned on), the panel's supply
     * rail ramps up slower than the ESP32 boots, so issuing SPI/reset
     * commands too early leaves it initialized against an unstable supply
     * and stuck showing a black screen with the backlight on until power
     * is cycled. Give the rail time to settle before touching the panel;
     * display_task also re-runs tft_panel_init_sequence() once more after
     * boot as a second line of defence (see there). */
    vTaskDelay(pdMS_TO_TICKS(2000));
    spi_bus_config_t bus_config = {
        .sclk_io_num = TFT_SCLK_GPIO,
        .mosi_io_num = TFT_MOSI_GPIO,
        .miso_io_num = -1,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = sizeof(s_tft_pixels),
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &bus_config, SPI_DMA_CH_AUTO));

    esp_lcd_panel_io_spi_config_t io_config = {
        .dc_gpio_num = TFT_DC_GPIO,
        .cs_gpio_num = TFT_CS_GPIO,
        .pclk_hz = TFT_PIXEL_CLOCK_HZ,
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
        .spi_mode = 0,
        .trans_queue_depth = 4,
        .on_color_trans_done = NULL,
        .user_ctx = NULL,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)SPI2_HOST,
                                             &io_config, &s_tft_io));

    esp_lcd_panel_dev_config_t panel_config = {
        .reset_gpio_num = TFT_RST_GPIO,
        .bits_per_pixel = 16,
        .rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_st7789(s_tft_io, &panel_config, &s_tft_panel));
    tft_panel_init_sequence();
    s_tft_ready = true;
    tft_clear(0x0000);
    tft_text(12, 12, "MOTA CAN", 0xFFFF, 3);
    tft_flush();
    ESP_LOGI(TAG, "ST7789 TFT started: %dx%d MOSI=%d SCK=%d CS=%d DC=%d RST=%d",
             TFT_WIDTH, TFT_HEIGHT, TFT_MOSI_GPIO, TFT_SCLK_GPIO, TFT_CS_GPIO,
             TFT_DC_GPIO, TFT_RST_GPIO);
}

static bool read_pid_value(uint8_t pid, int *value)
{
    char response[64];
    unsigned int first = 0, second = 0;
    if (!obd_query_pid(pid, response, sizeof(response))) return false;
    if (sscanf(response, "%*x %*x %x %x", &first, &second) < 1) return false;
    switch (pid) {
    case 0x05: *value = (int)first - 40; return true;
    case 0x0F: *value = (int)first - 40; return true;
    case 0x0B: *value = (int)first; return true;
    case 0x0C: *value = (int)(((first << 8) | second) / 4); return true;
    case 0x0D: *value = (int)first; return true;
    case 0x11: *value = (int)((first * 1000U) / 255U); return true;
    case 0x04: *value = (int)((first * 100U) / 255U); return true;
    case 0x0E: *value = (int)first * 5 - 640; return true;
    case 0x2F: *value = (int)((first * 100U) / 255U); return true;
    case 0x42: *value = (int)((first << 8) | second); return true;
    case 0x5C: *value = (int)first - 40; return true;
    case 0x46: *value = (int)first - 40; return true;
    case 0x33: *value = (int)first; return true;
    case 0x06: case 0x07: *value = (((int)first - 128) * 1000) / 128; return true;
    case 0x14: case 0x15: *value = (int)first * 5; return true; /* millivolts, 0-1275mV range */
    case 0x5E: *value = (int)(((first << 8) | second) / 2); return true; /* tenths of L/h */
    default: return false;
    }
}

/* Render a decoded PID value using the encoding read_pid_value() produced
 * for that specific PID (plain int, tenths, or millivolts). Units are
 * implied by the row label to keep the compact grid columns short. */
static void format_row_value(uint8_t pid, int value, char *out, size_t out_size)
{
    switch (pid) {
    case 0x05: case 0x0F: case 0x5C: case 0x46:
        snprintf(out, out_size, "%dC", value);
        break;
    case 0x0B: case 0x33:
        snprintf(out, out_size, "%dK", value);
        break;
    case 0x11: case 0x0E: case 0x06: case 0x07:
        snprintf(out, out_size, "%d.%d", value / 10, abs(value % 10));
        break;
    case 0x42: {
        int tenths_v = value / 100; /* millivolts -> tenths of a volt */
        snprintf(out, out_size, "%d.%dV", tenths_v / 10, abs(tenths_v % 10));
        break;
    }
    case 0x14: case 0x15:
        snprintf(out, out_size, "%d.%02dV", value / 1000, abs(value % 1000) / 10);
        break;
    case 0x5E:
        snprintf(out, out_size, "%d.%dL", value / 10, abs(value % 10));
        break;
    default:
        snprintf(out, out_size, "%d", value);
        break;
    }
}

#define TEMP_CRITICAL_C 170
#define COLOR_BLUE   0x05FF /* vivid sky blue; pure blue (0x001F) read as dark/navy and was hard to see */
#define COLOR_GREEN  0x07E0
#define COLOR_ORANGE 0xFD20
#define COLOR_RED    0xF800

/* Engine temperature colour ladder: blue when cold, green in the normal
 * band, orange/red as it climbs, then a dedicated critical alert above
 * TEMP_CRITICAL_C handled by the caller. */
static uint16_t temp_color(int celsius)
{
    if (celsius < 80) return COLOR_BLUE;
    if (celsius < 130) return COLOR_GREEN;
    if (celsius < 150) return COLOR_ORANGE;
    return COLOR_RED;
}

/* RPM: blue in the efficient mid-range, green either side of it, orange
 * approaching the limits, red when lugging or over-revving. */
static uint16_t rpm_color(int rpm)
{
    if (rpm < 2000 || rpm > 9000) return COLOR_RED;
    if ((rpm >= 2000 && rpm < 3000) || (rpm >= 8000 && rpm <= 9000)) return COLOR_ORANGE;
    if ((rpm >= 3000 && rpm < 4000) || (rpm > 6000 && rpm < 8000)) return COLOR_GREEN;
    return COLOR_BLUE; /* 4000-6000 inclusive */
}

/* Throttle position, stored in tenths of a percent. */
static uint16_t thr_color(int tenths_percent)
{
    if (tenths_percent > 700) return COLOR_RED;
    if (tenths_percent >= 500) return COLOR_ORANGE;
    if (tenths_percent >= 250) return COLOR_GREEN;
    return COLOR_BLUE;
}

/* Spark advance, stored in tenths of a degree; negative values (and low
 * positive values) mean retarded timing. */
static uint16_t spark_color(int tenths_degrees)
{
    if (tenths_degrees > 300) return COLOR_BLUE;
    if (tenths_degrees >= 150) return COLOR_GREEN;
    if (tenths_degrees >= 50) return COLOR_ORANGE;
    return COLOR_RED;
}

/* Fuel trim (short or long term): orange when the ECU is adding fuel,
 * blue when it is removing fuel. */
static uint16_t trim_color(int tenths_percent)
{
    return tenths_percent >= 0 ? COLOR_ORANGE : COLOR_BLUE;
}

static size_t row_index_for_pid(uint8_t pid)
{
    for (size_t i = 0; i < TFT_TELEMETRY_ROWS; ++i) {
        if (s_rows[i].pid == pid) {
            return i;
        }
    }
    return 0;
}

#define SECTION_FRAME_COLOR 0x8410

static void tft_section_box(int x, int y, int w, int h, const char *title)
{
    tft_rect(x, y, w, h, SECTION_FRAME_COLOR, 1);
    tft_text_bold(x + 6, y + 6, title, 0xFFFF, 2);
}

/* Draws one "LABEL value" field using row i's own label, decoded value and
 * (for RPM/THR/SPK/trim rows) live threshold colour. Unlike the old grid,
 * this has no critical/flashing state to handle - that stays exclusive to
 * the hero row, even for AIR (intake temperature) here in ADMISSION. */
static void tft_draw_field(int x, int y, size_t i, bool ignition_on, int scale)
{
    char text[24];
    uint16_t color;
    if (!ignition_on || !s_row_valid[i]) {
        snprintf(text, sizeof(text), "%s N/A", s_rows[i].label);
        color = 0x8410;
    } else {
        char value_str[16];
        format_row_value(s_rows[i].pid, s_row_values[i], value_str, sizeof(value_str));
        snprintf(text, sizeof(text), "%s %s", s_rows[i].label, value_str);
        switch (s_rows[i].pid) {
        case 0x0C: color = rpm_color(s_row_values[i]); break;
        case 0x11: color = thr_color(s_row_values[i]); break;
        case 0x0E: color = spark_color(s_row_values[i]); break;
        case 0x06: case 0x07: color = trim_color(s_row_values[i]); break;
        default: color = s_rows[i].color; break;
        }
    }
    tft_text_bold(x, y, text, color, scale);
}

static void tft_render(void)
{
    bool ignition_on = s_last_response != 0 &&
                       xTaskGetTickCount() - s_last_response <=
                       pdMS_TO_TICKS(TFT_DATA_TIMEOUT_MS);
    bool blink_on = ((xTaskGetTickCount() / pdMS_TO_TICKS(250)) % 2) == 0;
    tft_clear(0x0000);

    /* Hero: engine coolant temperature. Two label lines ("CYLINDER HEAD" /
     * "TEMPERATURE") stack on the left; the value sits to their right,
     * right-justified against HERO_VALUE_RIGHT_X so short readings (the
     * common case) sit out near the edge instead of clumping right after
     * the labels, while still leaving room for a longer one like "-40C".
     * An off/unsupported reading is communicated as "N/A" on the value
     * only - the labels always show. Note: this PID has no fractional
     * resolution (whole degrees C only), so the value reads e.g. "85C",
     * not "85.5C". */
    const int hero_value_scale = 4;
    const int hero_value_right_x = 266;
    bool hero_valid = ignition_on && s_row_valid[0];
    int temp_c = hero_valid ? s_row_values[0] : 0;
    bool hero_critical = hero_valid && temp_c >= TEMP_CRITICAL_C;
    if (hero_critical) {
        tft_fill_rect(4, 0, 266, 36, 0xF800);
        if (blink_on) {
            tft_rect(2, 0, 270, 38, 0xFFFF, 2);
        }
    }
    tft_text_bold(8, 2, "CYLINDER HEAD", 0xFFFF, 2);
    tft_text_bold(8, 18, "TEMPERATURE", 0xFFFF, 2);
    char hero_text[16];
    uint16_t hero_value_color;
    if (!hero_valid) {
        snprintf(hero_text, sizeof(hero_text), "N/A");
        hero_value_color = 0x8410;
    } else {
        snprintf(hero_text, sizeof(hero_text), "%dC", temp_c);
        hero_value_color = hero_critical ? 0x0000 : temp_color(temp_c);
    }
    int hero_value_x = hero_value_right_x - (int)strlen(hero_text) * 6 * hero_value_scale;
    tft_text_bold(hero_value_x, 3, hero_text, hero_value_color, hero_value_scale);

    /* Barometric pressure: this ECU doesn't return ambient temperature (no
     * data ever comes back for that PID, see s_rows), so this is a single
     * small, plain reading rather than a boxed section - deliberately
     * understated since it's the least important number on the screen. */
    tft_draw_field(8, 40, row_index_for_pid(0x33), ignition_on, 1);

    /* TRIMS and LAMBDAS: two small framed sections side by side, each
     * holding a related pair of readings. */
    tft_section_box(4, 54, 150, 74, "TRIMS");
    tft_draw_field(10, 82, row_index_for_pid(0x06), ignition_on, 2);
    tft_draw_field(10, 100, row_index_for_pid(0x07), ignition_on, 2);

    tft_section_box(160, 54, 150, 74, "LAMBDAS");
    tft_draw_field(166, 82, row_index_for_pid(0x14), ignition_on, 2);
    tft_draw_field(166, 100, row_index_for_pid(0x15), ignition_on, 2);

    /* ADMISSION: wider section spanning both columns, with its own grid
     * (throttle/RPM, then MAP/spark advance, then intake air temp). */
    tft_section_box(4, 136, 308, 88, "ADMISSION");
    const int admission_col_a = 10;
    const int admission_col_b = 4 + 308 / 2 + 4;
    tft_draw_field(admission_col_a, 164, row_index_for_pid(0x11), ignition_on, 2);
    tft_draw_field(admission_col_b, 164, row_index_for_pid(0x0C), ignition_on, 2);
    tft_draw_field(admission_col_a, 182, row_index_for_pid(0x0B), ignition_on, 2);
    tft_draw_field(admission_col_b, 182, row_index_for_pid(0x0E), ignition_on, 2);
    tft_draw_field(admission_col_a, 200, row_index_for_pid(0x0F), ignition_on, 2);

    tft_flush();
}

/* Runs on the CAN/protocol core, separate from the display core, so a slow
 * or unanswered OBD query never stalls screen redraws. Every row whose
 * period has elapsed gets queried on this pass (not just the first one
 * found), so each row actually refreshes at its own configured rate instead
 * of being serialized behind the others. */
static void telemetry_task(void *arg)
{
    (void)arg;
    while (s_self_test_active) {
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    TickType_t next_due[TFT_TELEMETRY_ROWS] = {0};
    while (true) {
        TickType_t now = xTaskGetTickCount();
        for (size_t i = 0; i < TFT_TELEMETRY_ROWS; ++i) {
            if (now >= next_due[i]) {
                int value;
                if (read_pid_value(s_rows[i].pid, &value)) {
                    s_row_values[i] = value;
                    s_row_valid[i] = true;
                    s_last_response = xTaskGetTickCount();
                }
                next_due[i] = xTaskGetTickCount() + pdMS_TO_TICKS(s_rows[i].period_ms);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static void display_task(void *arg)
{
    (void)arg;
    tft_start();

    /* Self-test: briefly force a simulated over-temperature reading so the
     * hero row's flashing red alert box can be visually confirmed on every
     * boot, without needing to actually overheat the engine. The grid-cell
     * variant of this alert has no PID to test it with any more: TEMP is
     * the only temperature reading the current layout still displays. */
    TickType_t self_test_until = xTaskGetTickCount() + pdMS_TO_TICKS(4000);
    s_row_values[0] = TEMP_CRITICAL_C + 5;
    s_row_valid[0] = true;
    s_last_response = xTaskGetTickCount();
    while (xTaskGetTickCount() < self_test_until) {
        tft_render();
        vTaskDelay(pdMS_TO_TICKS(TFT_REFRESH_PERIOD_MS));
    }
    s_row_valid[0] = false;
    s_self_test_active = false;

    /* By now it's ~6s since boot (tft_start()'s startup delay plus this
     * self-test), well past any engine-cranking voltage sag. Re-run the
     * panel init as a second line of defence against the panel having come
     * up wrong the first time - see tft_panel_init_sequence(). */
    tft_panel_init_sequence();

    while (true) {
        tft_render();
        vTaskDelay(pdMS_TO_TICKS(TFT_REFRESH_PERIOD_MS));
    }
}

static bool obd_query_pid(uint8_t pid, char *response, size_t response_size)
{
    if (s_can_mutex == NULL || xSemaphoreTake(s_can_mutex, pdMS_TO_TICKS(700)) != pdTRUE) {
        snprintf(response, response_size, "NO DATA");
        return false;
    }
    struct can_packet stale;
    while (xQueueReceive(s_obd_queue, &stale, 0) == pdTRUE) {
        /* Discard frames left from a previous request. */
    }

    uint8_t tx_data[8] = { 0x02, 0x01, pid, 0x55, 0x55, 0x55, 0x55, 0x55 };
    twai_frame_t tx_frame = {
        .header = { .id = 0x7DF, .dlc = 8 },
        .buffer = tx_data,
        .buffer_len = sizeof(tx_data),
    };

    ESP_LOGI(TAG, "OBD TX: id=7DF data=02 01 %02X 55 55 55 55 55", pid);
    esp_err_t tx_result = twai_node_transmit(s_twai, &tx_frame, 100);
    esp_err_t done_result = tx_result == ESP_OK
                                ? twai_node_transmit_wait_all_done(s_twai, 100)
                                : tx_result;
    if (tx_result != ESP_OK || done_result != ESP_OK) {
        ESP_LOGW(TAG, "OBD TX failed: transmit=%s done=%s",
                 esp_err_to_name(tx_result), esp_err_to_name(done_result));
        snprintf(response, response_size, "NO DATA");
        xSemaphoreGive(s_can_mutex);
        return false;
    }

    struct can_packet packet;
    TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(OBD_RESPONSE_TIMEOUT_MS);
    while (xTaskGetTickCount() < deadline) {
        if (xQueueReceive(s_obd_queue, &packet, pdMS_TO_TICKS(20)) != pdTRUE) {
            continue;
        }
        if (packet.id < 0x7E8 || packet.id > 0x7EF || packet.len < 4 ||
            packet.data[1] != 0x41 || packet.data[2] != pid) {
            continue;
        }

        ESP_LOGD(TAG, "OBD response PID 01%02X: id=%03" PRIX32 " raw=", pid, packet.id);
        for (uint8_t i = 0; i < packet.len; ++i) {
            ESP_LOGD(TAG, "  data[%u]=%02X", i, packet.data[i]);
        }

        /* Classic ISO-TP single frame: byte 0 is the payload length.
         * Do not expose CAN padding bytes as ELM data. */
        uint8_t payload_len = packet.data[0] & 0x0F;
        if (payload_len == 0 || payload_len > packet.len - 1) {
            payload_len = packet.len - 1;
        }
        int used = 0;
        for (uint8_t i = 1; i <= payload_len && used < (int)response_size - 4; ++i) {
            used += snprintf(response + used, response_size - (size_t)used,
                             "%02X%s", packet.data[i],
                             i < payload_len ? " " : "");
        }
                    xSemaphoreGive(s_can_mutex);
        return true;
    }
    ESP_LOGW(TAG, "OBD RX timeout: no response for PID 01%02X", pid);
    snprintf(response, response_size, "NO DATA");
    xSemaphoreGive(s_can_mutex);
    return false;
}

static const ble_uuid16_t s_obd_service_uuid = BLE_UUID16_INIT(0xFFF0);
static const ble_uuid16_t s_obd_notify_uuid = BLE_UUID16_INIT(0xFFF1);
static const ble_uuid16_t s_obd_write_uuid = BLE_UUID16_INIT(0xFFF2);
static const ble_uuid16_t s_hm10_service_uuid = BLE_UUID16_INIT(0xFFE0);
static const ble_uuid16_t s_hm10_characteristic_uuid = BLE_UUID16_INIT(0xFFE1);

struct ble_command {
    char text[BLE_COMMAND_LENGTH];
};

static void ble_send_text(const char *text)
{
    if (s_ble_conn == BLE_HS_CONN_HANDLE_NONE || !s_ble_notify_ready ||
        (s_ble_notify_handle == 0 && s_ble_hm10_handle == 0)) {
        return;
    }

    const char *cursor = text;
    size_t remaining = strlen(text);
    while (s_ble_notify_handle != 0 && remaining != 0) {
        size_t chunk_len = remaining > 20 ? 20 : remaining;
        struct os_mbuf *om = ble_hs_mbuf_from_flat(cursor, chunk_len);
        if (om == NULL) {
            ESP_LOGW(TAG, "BLE response allocation failed");
            return;
        }
        int rc = ble_gatts_notify_custom(s_ble_conn, s_ble_notify_handle, om);
        if (rc != 0) {
            ESP_LOGD(TAG, "BLE notify failed: %d", rc);
        }
        cursor += chunk_len;
        remaining -= chunk_len;
    }

    if (s_ble_hm10_handle != 0) {
        const char *cursor_alt = text;
        size_t remaining_alt = strlen(text);
        while (remaining_alt != 0) {
            size_t chunk_len = remaining_alt > 20 ? 20 : remaining_alt;
            struct os_mbuf *om = ble_hs_mbuf_from_flat(cursor_alt, chunk_len);
            if (om == NULL) return;
            ble_gatts_notify_custom(s_ble_conn, s_ble_hm10_handle, om);
            cursor_alt += chunk_len;
            remaining_alt -= chunk_len;
        }
    }
}

static void normalize_command(char *command)
{
    size_t len = strlen(command);
    while (len > 0 && isspace((unsigned char)command[len - 1])) {
        command[--len] = '\0';
    }
    for (size_t i = 0; i < len; ++i) {
        command[i] = (char)toupper((unsigned char)command[i]);
    }
}

static void process_elm_command(char *command)
{
    normalize_command(command);
    if (command[0] == '\0') {
        return;
    }

    ESP_LOGI(TAG, "BLE command: %s", command);
    if (s_echo) {
        ble_send_text(command);
        ble_send_text("\r");
    }

    if (!strcmp(command, "ATZ") || !strcmp(command, "ATWS")) {
        s_echo = true;
        s_monitor_mode = false;
        ble_send_text("ELM327 v1.5\r\r>");
    } else if (!strcmp(command, "ATI")) {
        ble_send_text("MOTA CAN ELM327 BRIDGE\r\r>");
    } else if (!strcmp(command, "ATE0")) {
        s_echo = false;
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "ATE1")) {
        s_echo = true;
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "ATL0") || !strcmp(command, "ATS0") ||
               !strcmp(command, "ATH0") || !strcmp(command, "ATCAF1") ||
               !strcmp(command, "ATCAF0") || !strcmp(command, "ATCFC") ||
               !strcmp(command, "ATPC") || !strcmp(command, "ATAT0") ||
               !strcmp(command, "ATAT1") || !strcmp(command, "ATAT2") ||
               !strcmp(command, "ATSP0") || !strcmp(command, "ATSP6") ||
               !strcmp(command, "ATD") || !strcmp(command, "ATWS") ||
               !strcmp(command, "AT@1")) {
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "ATL1") || !strcmp(command, "ATS1") ||
               !strcmp(command, "ATH1")) {
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "ATDPN")) {
        ble_send_text("6\r\r>");
    } else if (!strcmp(command, "ATDP")) {
        ble_send_text("ISO 15765-4 (CAN 11/500)\r\r>");
    } else if (!strcmp(command, "ATSH7DF")) {
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "ATRV")) {
        ble_send_text("12.6V\r\r>");
    } else if (!strcmp(command, "ATMA")) {
        s_monitor_mode = true;
        ble_send_text("OK\r");
    } else if (!strcmp(command, "ATST") || !strcmp(command, "ATIGN")) {
        ble_send_text("OK\r\r>");
    } else if (strlen(command) == 4 && command[0] == '0' &&
               command[1] == '1' && isxdigit((unsigned char)command[2]) &&
               isxdigit((unsigned char)command[3])) {
        ESP_LOGI(TAG, "ELM OBD request received: %s", command);
        char response[64];
        uint8_t pid = (uint8_t)strtoul(command + 2, NULL, 16);
        bool got_response = obd_query_pid(pid, response, sizeof(response));
        ESP_LOGI(TAG, "ELM response for %s: %s (valid=%u)",
             command, response, got_response);
        ble_send_text(response);
        ble_send_text("\r\r>");
    } else {
        ble_send_text("?\r\r>");
    }
}

static void ble_command_task(void *arg)
{
    (void)arg;
    struct ble_command command;
    while (xQueueReceive(s_ble_command_queue, &command, portMAX_DELAY) == pdTRUE) {
        process_elm_command(command.text);
    }
}

static bool queue_elm_command(const char *command)
{
    if (s_ble_command_queue == NULL || command[0] == '\0') {
        return false;
    }
    struct ble_command queued = {0};
    strncpy(queued.text, command, sizeof(queued.text) - 1);
    return xQueueSend(s_ble_command_queue, &queued, 0) == pdTRUE;
}

static void enqueue_command_buffer(void)
{
    if (s_command_length == 0) {
        return;
    }
    s_command_buffer[s_command_length] = '\0';
    if (!queue_elm_command(s_command_buffer)) {
        ESP_LOGW(TAG, "BLE command queue full");
    }
    s_command_length = 0;
}

static bool command_is_complete_without_cr(void)
{
    s_command_buffer[s_command_length] = '\0';
    static const char *const commands[] = {
        "ATZ", "ATWS", "ATI", "ATE0", "ATE1", "ATL0", "ATL1",
        "ATS0", "ATS1", "ATH0", "ATH1", "ATDPN", "ATDP", "ATSH7DF",
        "ATRV", "ATMA", "0100", "010C", "010D"
    };
    for (size_t i = 0; i < sizeof(commands) / sizeof(commands[0]); ++i) {
        if (!strcmp(s_command_buffer, commands[i])) {
            return true;
        }
    }
    /* Accept any four-character hexadecimal Mode 01 PID, as DMD2 may ask
     * for temperature, load, throttle, fuel and other standard PIDs. */
    if (strlen(s_command_buffer) == 4 && s_command_buffer[0] == '0' &&
        s_command_buffer[1] == '1') {
        for (size_t i = 2; i < 4; ++i) {
            if (!isxdigit((unsigned char)s_command_buffer[i])) {
                return false;
            }
        }
        return true;
    }
    return false;
}

static int uart_write_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        char incoming[BLE_COMMAND_LENGTH] = {0};
        uint16_t length = 0;
        uint16_t available = OS_MBUF_PKTLEN(ctxt->om);
        if (available >= sizeof(incoming)) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        if (ble_hs_mbuf_to_flat(ctxt->om, incoming, sizeof(incoming) - 1, &length) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        for (uint16_t i = 0; i < length; ++i) {
            char character = incoming[i];
            if (character == '\r' || character == '\n') {
                enqueue_command_buffer();
            } else if (s_command_length < sizeof(s_command_buffer) - 1) {
                s_command_buffer[s_command_length++] = character;
            }
        }
        /* nRF Connect can send UTF-8 without a line-ending byte. */
        if (s_command_length != 0 && command_is_complete_without_cr()) {
            enqueue_command_buffer();
        }
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static int uart_notify_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                                 struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        const char *identity = "MOTA CAN ELM327 BRIDGE";
        return os_mbuf_append(ctxt->om, identity, strlen(identity)) == 0
                   ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static int hm10_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        const char *identity = "MOTA CAN ELM327 BRIDGE";
        return os_mbuf_append(ctxt->om, identity, strlen(identity)) == 0
                   ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return uart_write_access_cb(conn_handle, attr_handle, ctxt, arg);
}

static const struct ble_gatt_svc_def s_gatt_services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_obd_service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &s_obd_notify_uuid.u,
                .access_cb = uart_notify_access_cb,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_ble_notify_handle,
            },
            {
                .uuid = &s_obd_write_uuid.u,
                .access_cb = uart_write_access_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            { 0 }
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_hm10_service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &s_hm10_characteristic_uuid.u,
                .access_cb = hm10_access_cb,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE |
                         BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_ble_hm10_handle,
            },
            { 0 }
        },
    },
    { 0 }
};

static void gatt_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg)
{
    (void)arg;
    if (ctxt->op == BLE_GATT_REGISTER_OP_CHR) {
        ESP_LOGD(TAG, "GATT characteristic registered: value handle %u", ctxt->chr.val_handle);
    }
}

static void start_advertising(void)
{
    struct ble_hs_adv_fields fields = {0};
    struct ble_gap_adv_params params = {0};
    const char *name = ble_svc_gap_device_name();

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;
    fields.uuids16 = (ble_uuid16_t[]) { s_obd_service_uuid, s_hm10_service_uuid };
    fields.num_uuids16 = 2;
    fields.uuids16_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "BLE advertisement fields failed: %d", rc);
        return;
    }
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "BLE advertisement start failed: %d", rc);
    }
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_ble_conn = event->connect.conn_handle;
            s_ble_notify_ready = false;
            s_command_length = 0;
            ESP_LOGI(TAG, "BLE connected");
        } else {
            start_advertising();
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        s_ble_conn = BLE_HS_CONN_HANDLE_NONE;
        s_ble_notify_ready = false;
        s_monitor_mode = false;
        ESP_LOGI(TAG, "BLE disconnected");
        start_advertising();
        return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        start_advertising();
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "BLE notify subscription: %d", event->subscribe.cur_notify);
        if (event->subscribe.attr_handle == s_ble_notify_handle &&
            event->subscribe.cur_notify) {
            s_ble_notify_ready = true;
            ble_send_text("\r\nELM327 v1.5\r\n>");
        } else if (event->subscribe.attr_handle == s_ble_notify_handle) {
            s_ble_notify_ready = false;
        } else if (event->subscribe.attr_handle == s_ble_hm10_handle &&
                   event->subscribe.cur_notify) {
            s_ble_notify_ready = true;
            ble_send_text("\r\nELM327 v1.5\r\n>");
        }
        return 0;
    default:
        return 0;
    }
}

static void ble_on_sync(void)
{
    int rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "BLE address setup failed: %d", rc);
        return;
    }
    start_advertising();
}

static void ble_on_reset(int reason)
{
    ESP_LOGE(TAG, "BLE host reset: %d", reason);
}

static void ble_host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static bool twai_rx_callback(twai_node_handle_t handle,
                             const twai_rx_done_event_data_t *event_data,
                             void *user_ctx)
{
    (void)event_data;
    (void)user_ctx;
    struct can_packet packet = {0};
    uint8_t buffer[8] = {0};
    twai_frame_t frame = {
        .buffer = buffer,
        .buffer_len = sizeof(buffer),
    };

    if (twai_node_receive_from_isr(handle, &frame) == ESP_OK) {
        packet.id = frame.header.id;
        packet.extended = frame.header.ide;
        packet.rtr = frame.header.rtr;
        packet.len = frame.header.dlc > 8 ? 8 : (uint8_t)frame.header.dlc;
        memcpy(packet.data, buffer, packet.len);
        BaseType_t higher_priority_task_woken = pdFALSE;
        xQueueSendFromISR(s_can_queue, &packet, &higher_priority_task_woken);
        xQueueSendFromISR(s_obd_queue, &packet, &higher_priority_task_woken);
        return higher_priority_task_woken == pdTRUE;
    }
    return false;
}

static void can_task(void *arg)
{
    (void)arg;
    struct can_packet packet;
    char line[128];
    TickType_t last_log = 0;
    while (xQueueReceive(s_can_queue, &packet, portMAX_DELAY) == pdTRUE) {
        TickType_t now = xTaskGetTickCount();
        if (s_monitor_mode || now - last_log >= pdMS_TO_TICKS(500)) {
            int used = snprintf(line, sizeof(line), "%sCAN id=%" PRIX32 " data=",
                                packet.extended ? "CAN-EXT " : "CAN ", packet.id);
            for (uint8_t i = 0; i < packet.len && used < (int)sizeof(line) - 4; ++i) {
                used += snprintf(line + used, sizeof(line) - (size_t)used, "%02X ", packet.data[i]);
            }
            ESP_LOGI(TAG, "%s", line);
            last_log = now;
        }

        if (s_monitor_mode && s_ble_conn != BLE_HS_CONN_HANDLE_NONE &&
            s_ble_notify_ready) {
            char ble_line[64];
            int pos = snprintf(ble_line, sizeof(ble_line), "%" PRIX32 " ", packet.id);
            for (uint8_t i = 0; i < packet.len && pos < (int)sizeof(ble_line) - 4; ++i) {
                pos += snprintf(ble_line + pos, sizeof(ble_line) - (size_t)pos,
                                "%02X ", packet.data[i]);
            }
            if (pos < (int)sizeof(ble_line) - 2) {
                ble_line[pos++] = '\r';
                ble_line[pos] = '\0';
                ble_send_text(ble_line);
            }
        }
    }
}

static void twai_start(void)
{
    s_can_queue = xQueueCreate(CAN_QUEUE_LENGTH, sizeof(struct can_packet));
    ESP_ERROR_CHECK(s_can_queue == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    s_obd_queue = xQueueCreate(OBD_QUEUE_LENGTH, sizeof(struct can_packet));
    ESP_ERROR_CHECK(s_obd_queue == NULL ? ESP_ERR_NO_MEM : ESP_OK);

    twai_onchip_node_config_t config = {
        .io_cfg = {
            .tx = CAN_TX_GPIO,
            .rx = CAN_RX_GPIO,
        },
        .bit_timing = {
            .bitrate = CAN_BITRATE,
            .sp_permill = 750,
        },
        .tx_queue_depth = 8,
        .flags = {
            .no_receive_rtr = 1,
        },
    };
    ESP_ERROR_CHECK(twai_new_node_onchip(&config, &s_twai));

    /* Bus-off recovery runs in its own task, driven by state-change events,
     * so the node rejoins the bus without a reboot or any polling. */
    can_bus_port_t port = can_bus_twai_port(s_twai, "twai0");
    can_bus_supervisor_config_t supervisor_config = CAN_BUS_SUPERVISOR_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(can_bus_supervisor_create(&port, &supervisor_config, &s_can_bus));

    twai_event_callbacks_t callbacks = {
        .on_rx_done = twai_rx_callback,
        .on_state_change = can_bus_twai_on_state_change,
    };
    ESP_ERROR_CHECK(twai_node_register_event_callbacks(s_twai, &callbacks, s_can_bus));

    twai_mask_filter_config_t filter = {
        .id = 0,
        .mask = 0,
        .is_ext = 0,
        .no_fd = 1,
    };
    ESP_ERROR_CHECK(twai_node_config_mask_filter(s_twai, 0, &filter));
    ESP_ERROR_CHECK(twai_node_enable(s_twai));
    ESP_LOGI(TAG, "TWAI active OBD mode started: %u bit/s, TX GPIO %d, RX GPIO %d",
             CAN_BITRATE, CAN_TX_GPIO, CAN_RX_GPIO);
}

static void ble_start(void)
{
    s_ble_command_queue = xQueueCreate(BLE_COMMAND_QUEUE_LENGTH, sizeof(struct ble_command));
    ESP_ERROR_CHECK(s_ble_command_queue == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    xTaskCreate(ble_command_task, "ble_elm_task", 4096, NULL, 5, NULL);
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.reset_cb = ble_on_reset;
    ble_hs_cfg.sync_cb = ble_on_sync;
    ble_hs_cfg.gatts_register_cb = gatt_register_cb;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    /* ELM327 BLE clients should connect without a PIN or bonded pairing. */
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_bonding = 0;
    ble_hs_cfg.sm_sc = 0;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(s_gatt_services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(s_gatt_services));
    ESP_ERROR_CHECK(ble_svc_gap_device_name_set("OBDII"));
    nimble_port_freertos_init(ble_host_task);
}

void app_main(void)
{
    esp_err_t nvs_result = nvs_flash_init();
    if (nvs_result == ESP_ERR_NVS_NO_FREE_PAGES || nvs_result == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        nvs_result = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs_result);

    s_can_mutex = xSemaphoreCreateMutex();
    ESP_ERROR_CHECK(s_can_mutex == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    twai_start();
    xTaskCreate(can_task, "can_task", 4096, NULL, 5, NULL);
    /* CAN/OBD polling stays on core 0 with the CAN and BLE stacks; the
     * display gets its own core (1) so rendering never waits on the bus. */
    xTaskCreatePinnedToCore(telemetry_task, "telemetry_task", 4096, NULL, 5, NULL, 0);
    xTaskCreatePinnedToCore(display_task, "display_task", 8192, NULL, 4, NULL, 1);
    ble_start();

    ESP_LOGI(TAG, "Ready. Connect a BLE UART app and use ATMA for raw CAN streaming.");
}
