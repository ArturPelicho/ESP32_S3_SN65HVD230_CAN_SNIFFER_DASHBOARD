#include <ctype.h>
#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_err.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "nvs_flash.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_twai.h"
#include "esp_twai_onchip.h"
#include "can_bus_twai.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_commands.h"
#include "esp_lcd_panel_vendor.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "fuel_estimator_config.h"
#include "steady_cal.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "bike_can.h"
#include "obd_poller.h"
#include "pid_scan_task.h"
#include "boot_diag.h"

static const char *TAG = "mota_can";

#define CAN_TX_GPIO 17
#define CAN_RX_GPIO 18
#define CAN_BITRATE 500000
#define CAN_QUEUE_LENGTH 32
#define CAN_TX_QUEUE_DEPTH 8
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
/* Framebuffers to rotate through. 1 = single buffer: a frame is only drawn
 * once the previous one has finished going out over SPI (~61 ms at 20 MHz,
 * well inside TFT_REFRESH_PERIOD_MS, so no frames are skipped at 10 fps).
 * 2 = double buffer: the next frame is drawn while the previous one is
 * still being sent, which only matters for faster refresh rates. A full
 * 320x240 RGB565 frame is 150 KB, so extra buffers come from DMA-capable
 * internal heap and are only taken if TFT_FB_HEAP_RESERVE bytes stay free
 * for BLE/CAN afterwards. With NimBLE running that is not the case today,
 * so the display normally runs single-buffered (logged at boot). */
#define TFT_FB_COUNT 2
#define TFT_FB_HEAP_RESERVE (64 * 1024)
/* How often the panel's configuration is re-sent while running; this is
 * also the longest the screen can stay black after a supply dip. */
#define TFT_HEALTH_PERIOD_MS 1000
#define TFT_RESET_SETTLE_MS 120
#define TFT_TEMP_PERIOD_MS 100
#define TFT_FAST_PERIOD_MS 100
#define TFT_SLOW_PERIOD_MS 500
#define OBD_RESPONSE_TIMEOUT_MS 150
#define OBD_QUERY_TIMEOUT_MS 700
/* Must match the poller config (OBD_POLLER_DEFAULT_CONFIG). */
#define OBD_REQUEST_ID 0x7DF
#define OBD_RESPONSE_ID_MIN 0x7E8
#define OBD_RESPONSE_ID_MAX 0x7EF

#ifndef CONFIG_PID_SCAN_GAP_MS
#define CONFIG_PID_SCAN_GAP_MS 40
#endif
#ifndef CONFIG_PID_SCAN_REPORT_PERIOD_S
#define CONFIG_PID_SCAN_REPORT_PERIOD_S 30
#endif
#define TFT_DATA_TIMEOUT_MS 1200

static twai_node_handle_t s_twai;
static can_bus_supervisor_handle_t s_can_bus;
static QueueHandle_t s_can_queue;
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
    bool hidden; /* rows tft_render() currently has no place for; polling is unaffected */
};

/* Index 0 is the engine coolant temperature, rendered as a standalone hero
 * row. The rest of the visible rows are hand-placed into named sections in
 * tft_render() (TRIMS, LAMBDAS, ADMISSION) rather than a generic grid, so
 * this table's order no longer determines on-screen layout - tft_render()
 * looks rows up by PID via row_index_for_pid().
 *
 * SPD, FRT, VLT, OIL, FUL and AMB are hidden because this ECU never
 * returns data for them. LOD is hidden because the current screen layout
 * has no place for it. All stay in this table so another ECU (or bike) that
 * does answer them works unchanged: obd_poller asks the ECU which PIDs it
 * supports and skips the rest, and backs off any PID that keeps going
 * unanswered, so unsupported rows cost no bus time. */
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
/* Keeps telemetry_on_value() off the rows until display_task's boot self-test (which forces
 * a fake critical reading to visually confirm the alert flash) is done
 * writing to s_row_values/s_row_valid, so real CAN data doesn't race with
 * and immediately overwrite the self-test values. */
static volatile bool s_self_test_active = true;

/* Fuel consumption estimate: computed on core 0 by fuel_task from the
 * MAP/IAT/RPM/O2 rows, read on core 1 by tft_render(). The spinlock only
 * guards a struct copy, so neither side ever waits more than a few cycles. */
static fuel_estimator_t s_fuel_estimator;
static fuel_estimate_t s_fuel_estimate;
static portMUX_TYPE s_fuel_lock = portMUX_INITIALIZER_UNLOCKED;

/* The bike's own CAN broadcast (bike_can.h): RPM, the candidate injection
 * value and battery voltage, about every 9 ms. Decoded by can_task on
 * core 0, read by the display on core 1. RPM from here takes over from
 * the polled PID 0C while it keeps arriving; the poll is the fallback. */
#define BIKE_CAN_FRESH_MS 300
static const bike_can_config_t s_bike_can_config = BIKE_CAN_ZS125_CONFIG();
static bike_can_data_t s_bike;
static TickType_t s_bike_engine_at;
static TickType_t s_bike_power_at;
static portMUX_TYPE s_bike_lock = portMUX_INITIALIZER_UNLOCKED;

/* Fuel measured from the injector pulse (inj_meter.h). can_task adds every
 * engine frame to the trip totals under s_bike_lock; fuel_task saves them
 * to NVS. The trip survives key-off and runs until "TRIP RESET" over BLE,
 * so a fill-to-fill comparison can span several stops. */
#define TRIP_NVS_NAMESPACE "trip"
#define TRIP_NVS_KEY "totals1"
#define TRIP_SAVE_PERIOD_MS 15000
static inj_meter_config_t s_inj_config;
static inj_meter_totals_t s_trip;
static int64_t s_trip_frame_us;
static volatile bool s_trip_reset_requested;

static int gap_event(struct ble_gap_event *event, void *arg);

struct can_packet {
    uint32_t id;
    uint32_t extended;
    uint32_t rtr;
    uint8_t len;
    uint8_t data[8];
};

#define TFT_FB_PIXELS (TFT_WIDTH * TFT_HEIGHT)
#define TFT_FB_BYTES (TFT_FB_PIXELS * sizeof(uint16_t))

/* Frame buffer handoff between display_task (draws) and the SPI DMA (sends).
 * esp_lcd_panel_draw_bitmap() only queues the transfer and returns, so the
 * buffer it was given stays in use for ~61 ms afterwards. s_tft_idle is
 * given back by the transfer-done ISR; display_task only polls it (zero
 * timeout), so the render loop on core 1 never blocks on the bus. Drawing
 * primitives write through s_tft_pixels, which always points at a buffer
 * that is not being sent. */
static uint16_t s_tft_fb0[TFT_FB_PIXELS];
static uint16_t *s_tft_fb[TFT_FB_COUNT];
static size_t s_tft_fb_count = 1;
static size_t s_tft_draw_idx;
static uint16_t *s_tft_pixels = s_tft_fb0;
static SemaphoreHandle_t s_tft_idle;

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
    if (c == '/') { static const uint8_t slash[5] = {0x20,0x10,0x08,0x04,0x02}; return slash; }
    if (c == '%') { static const uint8_t percent[5] = {0x23,0x13,0x08,0x64,0x62}; return percent; }
    return blank;
}

static void tft_clear(uint16_t color)
{
    for (size_t i = 0; i < TFT_FB_PIXELS; ++i) {
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

static IRAM_ATTR bool tft_on_color_trans_done(esp_lcd_panel_io_handle_t io,
                                               esp_lcd_panel_io_event_data_t *edata,
                                               void *user_ctx)
{
    (void)io;
    (void)edata;
    (void)user_ctx;
    BaseType_t woken = pdFALSE;
    xSemaphoreGiveFromISR(s_tft_idle, &woken);
    return woken == pdTRUE;
}

static bool tft_transfer_idle(void)
{
    return uxSemaphoreGetCount(s_tft_idle) > 0;
}

/* Called before drawing a frame. Returns false when there is no buffer
 * free to draw into yet (single-buffer mode with the last frame still in
 * flight); the caller just skips this refresh tick instead of waiting. */
static bool tft_begin_frame(void)
{
    if (!s_tft_ready) return false;
    /* With two or more buffers the draw buffer is never the one in flight. */
    return s_tft_fb_count > 1 || tft_transfer_idle();
}

/* Queues the drawn frame for DMA and moves drawing to the next buffer.
 * Never waits: if the previous transfer is still running (only possible
 * with double buffering), this frame is dropped and the next tick draws a
 * fresher one. */
static void tft_flush(void)
{
    if (!s_tft_ready) return;
    if (xSemaphoreTake(s_tft_idle, 0) != pdTRUE) return;
    /* The panel expects each RGB565 pixel big-endian (high byte first)
     * over SPI; our framebuffer is native little-endian, which was
     * silently reinterpreting color channels (blue rendering as green,
     * yellow as lilac). Byte-swap the whole frame just before sending;
     * it is safe to leave it swapped since tft_clear() overwrites the
     * entire buffer before this buffer is drawn into again. */
    for (size_t i = 0; i < TFT_FB_PIXELS; ++i) {
        uint16_t v = s_tft_pixels[i];
        s_tft_pixels[i] = (uint16_t)((v << 8) | (v >> 8));
    }
    if (esp_lcd_panel_draw_bitmap(s_tft_panel, 0, 0, TFT_WIDTH, TFT_HEIGHT,
                                  s_tft_pixels) != ESP_OK) {
        /* Nothing was queued, so no ISR will release the transfer slot. */
        xSemaphoreGive(s_tft_idle);
        return;
    }
    s_tft_draw_idx = (s_tft_draw_idx + 1) % s_tft_fb_count;
    s_tft_pixels = s_tft_fb[s_tft_draw_idx];
}

/* Bounded wait for the in-flight frame to finish. Only for rare one-off
 * operations that must not overlap a pixel transfer (the panel re-init);
 * the render loop never calls this. */
static bool tft_wait_idle(TickType_t timeout)
{
    if (xSemaphoreTake(s_tft_idle, timeout) != pdTRUE) return false;
    xSemaphoreGive(s_tft_idle);
    return true;
}

static void tft_alloc_framebuffers(void)
{
    s_tft_fb[0] = s_tft_fb0;
    s_tft_fb_count = 1;
    for (size_t i = 1; i < TFT_FB_COUNT; ++i) {
        uint16_t *fb = NULL;
        if (heap_caps_get_free_size(MALLOC_CAP_DMA) >= TFT_FB_BYTES + TFT_FB_HEAP_RESERVE) {
            fb = heap_caps_malloc(TFT_FB_BYTES, MALLOC_CAP_DMA);
        }
        if (fb == NULL) {
            ESP_LOGI(TAG, "TFT: not enough free RAM for framebuffer %u, running with %u",
                     (unsigned)(i + 1), (unsigned)s_tft_fb_count);
            break;
        }
        s_tft_fb[i] = fb;
        s_tft_fb_count = i + 1;
    }
    s_tft_draw_idx = 0;
    s_tft_pixels = s_tft_fb[0];
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

/* Takes the panel out of sleep's aftermath into our operating mode:
 * pixel format, inversion, orientation and display on. Every command here
 * is idempotent and leaves GRAM alone, so tft_health_tick() can re-send it
 * while the panel is running without any visible effect. Returns the first
 * error instead of aborting, so a misbehaving panel cannot reboot the bike's
 * dashboard from the runtime path. */
static esp_err_t tft_panel_apply_config(void)
{
    /* Some ST7789 modules default to 18-bit/pixel and bleed channels into
     * each other (pure red rendering as yellow/white, pure blue as violet)
     * unless COLMOD is forced back to 16-bit RGB565. */
    uint8_t colmod_param = 0x55;
    esp_err_t err = esp_lcd_panel_io_tx_param(s_tft_io, LCD_CMD_COLMOD, &colmod_param, 1);
    /* BGR order and color inversion were tried and ruled out; the actual
     * cause was a pixel byte-order mismatch, now fixed in tft_flush(). */
    if (err == ESP_OK) err = esp_lcd_panel_invert_color(s_tft_panel, true);
    /* The new case mounts the panel rotated 90 degrees clockwise compared
     * to the old one, so the image is rotated 90 degrees counter-clockwise
     * here to compensate and land upright again (320x240 landscape). If it
     * comes up mirrored/sideways on this specific unit, try swapping the
     * mirror_y argument below to true<->false first. */
    if (err == ESP_OK) err = esp_lcd_panel_swap_xy(s_tft_panel, true);
    if (err == ESP_OK) err = esp_lcd_panel_mirror(s_tft_panel, false, true);
    if (err == ESP_OK) err = esp_lcd_panel_disp_on_off(s_tft_panel, true);
    return err;
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
    /* esp_lcd releases RST only 10 ms before esp_lcd_panel_init() sends
     * SLPOUT, but the ST7789 datasheet allows up to 120 ms for the reset
     * to complete when the panel was already awake (the re-init in
     * display_task). A SLPOUT landing inside that window is ignored, which
     * leaves the panel asleep: black with the backlight on. One-off, at
     * boot only. */
    vTaskDelay(pdMS_TO_TICKS(TFT_RESET_SETTLE_MS));
    ESP_ERROR_CHECK(esp_lcd_panel_init(s_tft_panel));
    ESP_ERROR_CHECK(tft_panel_apply_config());
}

/* Panel health check, run from display_task's main loop.
 *
 * Key-on and cranking can dip the supply far enough that the ST7789 resets
 * on its own while the ESP32 keeps running. A reset panel wakes up in
 * sleep-in, display-off, 18-bit, unrotated, and stays black (backlight on)
 * however many frames we send, until the next power cycle. The panel's SDO
 * line is not wired (miso_io_num = -1), so its status (RDDPM 0x0A) cannot be
 * read back to detect this. Instead the wake-up and configuration commands
 * are simply re-sent every TFT_HEALTH_PERIOD_MS; on a healthy panel they
 * change nothing.
 *
 * Never blocks: it only runs when no frame is in flight (otherwise it
 * retries on the next tick), and the 5 ms the datasheet asks for after
 * SLPOUT is covered by splitting the work over two refresh ticks. Returns
 * false on the SLPOUT tick so that tick's frame is skipped. */
static bool tft_health_tick(void)
{
    static bool s_slpout_sent;
    static TickType_t s_next_check;

    if (!s_tft_ready) return true;
    TickType_t now = xTaskGetTickCount();
    if (!s_slpout_sent && (int32_t)(now - s_next_check) < 0) return true;
    if (xSemaphoreTake(s_tft_idle, 0) != pdTRUE) return true;

    bool render = true;
    if (!s_slpout_sent) {
        if (esp_lcd_panel_io_tx_param(s_tft_io, LCD_CMD_SLPOUT, NULL, 0) == ESP_OK) {
            s_slpout_sent = true;
            render = false;
        } else {
            s_next_check = now + pdMS_TO_TICKS(TFT_HEALTH_PERIOD_MS);
        }
    } else {
        esp_err_t err = tft_panel_apply_config();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "TFT: panel re-config failed: %s", esp_err_to_name(err));
        }
        s_slpout_sent = false;
        s_next_check = now + pdMS_TO_TICKS(TFT_HEALTH_PERIOD_MS);
    }
    xSemaphoreGive(s_tft_idle);
    return render;
}

/* Boot record on the splash screen, held for CONFIG_BOOT_DIAG_HOLD_MS on
 * every start (CONFIG_BOOT_DIAG_ALWAYS_SHOW) or only when the previous
 * start failed or this one was not a clean power-on. See boot_diag.h for
 * how to interpret the numbers. */
#define TFT_BOOT_DIAG_HOLD_MS CONFIG_BOOT_DIAG_HOLD_MS

static void tft_draw_boot_diag(void)
{
    const boot_diag_info_t *d = boot_diag_info();
    char line[40];
    uint16_t color = boot_diag_noteworthy() ? 0xFFE0 : 0x8410;
    /* 2x font: 26 characters per line at most. */
    snprintf(line, sizeof(line), "BOOT %lu  FAILED %lu",
             (unsigned long)d->boot_count, (unsigned long)d->failed_boots);
    tft_text(12, 136, line, color, 2);
    snprintf(line, sizeof(line), "RESET %s", boot_diag_reason_name(d->reason));
    tft_text(12, 158, line, color, 2);
    snprintf(line, sizeof(line), "LAST RESET %s", boot_diag_reason_name(d->prev_reason));
    tft_text(12, 180, line, color, 2);
    snprintf(line, sizeof(line), "LAST STAGE %u OF %u", d->prev_stage,
             (unsigned)BOOT_DIAG_STAGE_RUNNING);
    tft_text(12, 202, line, color, 2);
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
    s_tft_idle = xSemaphoreCreateBinary();
    ESP_ERROR_CHECK(s_tft_idle == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    xSemaphoreGive(s_tft_idle);
    tft_alloc_framebuffers();
    spi_bus_config_t bus_config = {
        .sclk_io_num = TFT_SCLK_GPIO,
        .mosi_io_num = TFT_MOSI_GPIO,
        .miso_io_num = -1,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = TFT_FB_BYTES,
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
        .on_color_trans_done = tft_on_color_trans_done,
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
    tft_draw_boot_diag();
    tft_flush();
    boot_diag_mark(BOOT_DIAG_STAGE_PANEL_INIT);
    ESP_LOGI(TAG, "ST7789 TFT started: %dx%d, %u framebuffer(s) MOSI=%d SCK=%d CS=%d DC=%d RST=%d",
             TFT_WIDTH, TFT_HEIGHT, (unsigned)s_tft_fb_count, TFT_MOSI_GPIO, TFT_SCLK_GPIO, TFT_CS_GPIO,
             TFT_DC_GPIO, TFT_RST_GPIO);
}

/* Decodes the data bytes (A, B, ...) of a Mode 01 reply into the integer
 * encoding format_row_value() expects for that PID. */
static bool decode_pid_value(uint8_t pid, const uint8_t *data, uint8_t len, int *value)
{
    if (len < 1) return false;
    unsigned int first = data[0];
    unsigned int second = len > 1 ? data[1] : 0;
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

/* Render a decoded PID value using the encoding decode_pid_value() produced
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

/* Thresholds come from menuconfig ("Dashboard thresholds"); the
 * fallbacks keep the file building before sdkconfig has the new keys. */
#ifndef CONFIG_DASH_CHT_BLUE_BELOW_C
#define CONFIG_DASH_CHT_BLUE_BELOW_C 80
#endif
#ifndef CONFIG_DASH_CHT_GREEN_BELOW_C
#define CONFIG_DASH_CHT_GREEN_BELOW_C 135
#endif
#ifndef CONFIG_DASH_CHT_ORANGE_BELOW_C
#define CONFIG_DASH_CHT_ORANGE_BELOW_C 150
#endif
#ifndef CONFIG_DASH_CHT_CRITICAL_C
#define CONFIG_DASH_CHT_CRITICAL_C 165
#endif
#ifndef CONFIG_DASH_CHT_CRITICAL_HYSTERESIS_C
#define CONFIG_DASH_CHT_CRITICAL_HYSTERESIS_C 5
#endif
#ifndef CONFIG_DASH_CHT_CRITICAL_CONFIRM_MS
#define CONFIG_DASH_CHT_CRITICAL_CONFIRM_MS 1000
#endif
#ifndef CONFIG_DASH_RPM_GAUGE_MAX
#define CONFIG_DASH_RPM_GAUGE_MAX 10500
#endif
#ifndef CONFIG_DASH_RPM_SHIFT
#define CONFIG_DASH_RPM_SHIFT 10000
#endif
#define TEMP_CRITICAL_C CONFIG_DASH_CHT_CRITICAL_C
#define COLOR_BLUE   0x05FF /* vivid sky blue; pure blue (0x001F) read as dark/navy and was hard to see */
#define COLOR_GREEN  0x07E0
#define COLOR_ORANGE 0xFD20
#define COLOR_RED    0xF800

/* Engine temperature colour ladder: blue when cold, green in the normal
 * band, orange/red as it climbs, then a dedicated critical alert above
 * TEMP_CRITICAL_C handled by the caller. */
static uint16_t temp_color(int celsius)
{
    if (celsius < CONFIG_DASH_CHT_BLUE_BELOW_C) return COLOR_BLUE;
    if (celsius < CONFIG_DASH_CHT_GREEN_BELOW_C) return COLOR_GREEN;
    if (celsius < CONFIG_DASH_CHT_ORANGE_BELOW_C) return COLOR_ORANGE;
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
#define COLOR_WHITE  0xFFFF
#define COLOR_YELLOW 0xFFE0
#define COLOR_CYAN   0x07FF
#define COLOR_LABEL  0xC618 /* light grey: labels recede, values stand out */
#define COLOR_DIM    0x2945 /* unlit gauge segments */
#define COLOR_GREY   0x8410 /* missing values */

/* Gauge ranges: where the segmented bars start and end. */
#define TEMP_GAUGE_MIN_C 40
#define TEMP_GAUGE_MAX_C 180
#define RPM_GAUGE_MAX CONFIG_DASH_RPM_GAUGE_MAX

/* Narrowband O2 colour: orange rich, blue lean, green around
 * stoichiometric (0.45 V). Millivolts. */
static uint16_t o2_color(int millivolts)
{
    if (millivolts > 600) return COLOR_ORANGE;
    if (millivolts < 300) return COLOR_BLUE;
    return COLOR_GREEN;
}

static uint16_t thr_percent_color(int percent) { return thr_color(percent * 10); }

static int tft_text_width(const char *text, int scale)
{
    int len = (int)strlen(text);
    return len > 0 ? len * 6 * scale - scale : 0;
}

static void tft_text_right(int right_x, int y, const char *text, uint16_t color, int scale)
{
    tft_text_bold(right_x - tft_text_width(text, scale) - 1, y, text, color, scale);
}

/* Segmented "LED bar" gauge: each segment takes the colour its own value
 * would get from color_fn, lit up to the current value and dim beyond, so
 * the bar reads as a scale as well as a level. */
static void tft_gauge(int x, int y, int w, int h, int value, bool valid, int min, int max,
                      uint16_t (*color_fn)(int))
{
    const int pitch = 6;
    int segments = (w + 1) / pitch;
    for (int s = 0; s < segments; ++s) {
        int seg_value = min + (int)(((long)(max - min) * s + (max - min) / 2) / segments);
        bool lit = valid && value >= seg_value;
        tft_fill_rect(x + s * pitch, y, pitch - 1, h, lit ? color_fn(seg_value) : COLOR_DIM);
    }
}

/* Thin frame with a small grey caption inset into its top edge. */
static void tft_panel(int x, int y, int w, int h, const char *caption)
{
    tft_rect(x, y, w, h, SECTION_FRAME_COLOR, 1);
    if (caption != NULL) {
        int cw = tft_text_width(caption, 1);
        tft_fill_rect(x + 6, y, cw + 6, 1, 0x0000);
        tft_text(x + 9, y - 3, caption, COLOR_LABEL, 1);
    }
}

/* One "LABEL value" line of a panel, from telemetry row i. Missing data
 * shows "--" in grey so the layout never jumps. */
/* Copy of the latest broadcast data, with whether each group is recent. */
static bike_can_data_t bike_snapshot(bool *engine_fresh, bool *power_fresh)
{
    bike_can_data_t data;
    TickType_t engine_at, power_at;
    portENTER_CRITICAL(&s_bike_lock);
    data = s_bike;
    engine_at = s_bike_engine_at;
    power_at = s_bike_power_at;
    portEXIT_CRITICAL(&s_bike_lock);
    TickType_t now = xTaskGetTickCount();
    *engine_fresh = data.engine_valid && now - engine_at <= pdMS_TO_TICKS(BIKE_CAN_FRESH_MS);
    *power_fresh = data.battery_valid && now - power_at <= pdMS_TO_TICKS(BIKE_CAN_FRESH_MS);
    return data;
}

/* Battery colours: red when flat or overcharging, orange when low or high. */
static uint16_t battery_color(int millivolts)
{
    if (millivolts < 11800 || millivolts > 15200) return COLOR_RED;
    if (millivolts < 12300 || millivolts > 14800) return COLOR_ORANGE;
    return COLOR_GREEN;
}

/* Battery icon with a fill level (11.5 V empty, 14.5 V full) and the
 * voltage under it, in the hero's otherwise empty left corner. The icon
 * says what the number is, so it carries no unit. */
static void tft_draw_battery(int x, int y, bool valid, int millivolts)
{
    const int body_w = 40;
    const int body_h = 18;
    uint16_t color = valid ? battery_color(millivolts) : COLOR_GREY;
    uint16_t frame = valid ? COLOR_LABEL : COLOR_GREY;
    tft_rect(x, y, body_w, body_h, frame, 2);
    tft_fill_rect(x + body_w, y + 5, 3, body_h - 10, frame);
    char text[16];
    if (valid) {
        int inner = body_w - 6;
        int fill = (millivolts - 11500) * inner / 3000;
        if (fill < 2) fill = 2;
        if (fill > inner) fill = inner;
        tft_fill_rect(x + 3, y + 3, fill, body_h - 6, color);
        snprintf(text, sizeof(text), "%d.%d", millivolts / 1000, (millivolts / 100) % 10);
    } else {
        snprintf(text, sizeof(text), "--");
    }
    tft_text_bold(x, y + body_h + 6, text, color, 2);
}

static void tft_draw_field(int x, int right_x, int y, size_t i, bool ignition_on,
                           uint16_t (*color_fn)(int))
{
    tft_text_bold(x, y, s_rows[i].label, COLOR_LABEL, 2);
    if (!ignition_on || !s_row_valid[i]) {
        tft_text_right(right_x, y, "--", COLOR_GREY, 2);
        return;
    }
    char value_str[16];
    format_row_value(s_rows[i].pid, s_row_values[i], value_str, sizeof(value_str));
    uint16_t color = color_fn != NULL ? color_fn(s_row_values[i]) : s_rows[i].color;
    tft_text_right(right_x, y, value_str, color, 2);
}

#define O2_INACTIVE_MV 1010

/* Fuel estimate inputs are read straight from the telemetry rows; road
 * speed comes from OBD PID 0x0D when the ECU answers it, otherwise the
 * estimator falls back to L/h. A GPS or wheel-speed source would feed the
 * same speed input. */
static void fuel_estimate_update(uint32_t dt_ms)
{
    size_t map = row_index_for_pid(0x0B);
    size_t iat = row_index_for_pid(0x0F);
    size_t rpm = row_index_for_pid(0x0C);
    size_t o2 = row_index_for_pid(0x14);
    size_t thr = row_index_for_pid(0x11);
    size_t spd = row_index_for_pid(0x0D);
    /* The ECU reports 0xCA (1010 mV) while the O2 sensor is cold or
     * inactive, e.g. with the engine off: a placeholder, not "rich". */
    bool o2_valid = s_row_valid[o2] && s_row_values[o2] != O2_INACTIVE_MV;
    fuel_estimator_inputs_t in = {
        .map_kpa = (float)s_row_values[map],
        .intake_temp_c = (float)s_row_values[iat],
        .rpm = (float)s_row_values[rpm],
        .o2_volts = s_row_values[o2] / 1000.0f,   /* row stores millivolts */
        .throttle_pct = s_row_values[thr] / 10.0f, /* row stores tenths of % */
        .speed_kmh = (float)s_row_values[spd],
        .map_valid = s_row_valid[map],
        .intake_temp_valid = s_row_valid[iat],
        .rpm_valid = s_row_valid[rpm],
        .o2_valid = o2_valid,
        .throttle_valid = s_row_valid[thr],
        .speed_valid = s_row_valid[spd],
    };
#if CONFIG_FUEL_SOURCE_INJECTOR
    bool engine_fresh, power_fresh;
    bike_can_data_t bike = bike_snapshot(&engine_fresh, &power_fresh);
    if (engine_fresh) {
        in.measured_flow_lph = inj_meter_flow_lph(&s_inj_config, bike.rpm, bike.inj_raw / 10000.0f);
        in.measured_flow_valid = true;
        in.rpm = bike.rpm;
        in.rpm_valid = true;
    }
#endif
    fuel_estimate_t out;
    fuel_estimator_update(&s_fuel_estimator, &in, dt_ms, &out);
    portENTER_CRITICAL(&s_fuel_lock);
    s_fuel_estimate = out;
    portEXIT_CRITICAL(&s_fuel_lock);
}

/* Fixed-point "12.3" without relying on printf float support. */
static void format_tenths(float value, char *out, size_t out_size)
{
    int tenths = (int)(value * 10.0f + 0.5f);
    if (tenths < 0) tenths = 0;
    if (tenths > 9999) tenths = 9999;
    snprintf(out, out_size, "%d.%d", tenths / 10, tenths % 10);
}

/* Litres with two decimals below 10 L ("0.42"), one above ("12.3"). */
static void format_litres(float litres, char *out, size_t out_size)
{
    if (litres < 9.995f) {
        int hundredths = (int)(litres * 100.0f + 0.5f);
        if (hundredths < 0) hundredths = 0;
        snprintf(out, out_size, "%d.%02d", hundredths / 100, hundredths % 100);
    } else {
        format_tenths(litres, out, out_size);
    }
}

/* FUEL panel: amber badge marking the line as an estimate, the instant
 * reading (L/100km while moving, L/h when stopped or with no speed source),
 * then the average and the litres used since start-up stacked on the
 * right. */
static void tft_draw_fuel_panel(int x, int y, int w, int h, bool ignition_on)
{
    fuel_estimate_t est;
    portENTER_CRITICAL(&s_fuel_lock);
    est = s_fuel_estimate;
    portEXIT_CRITICAL(&s_fuel_lock);
    bool valid = ignition_on && est.valid;

    const int badge_w = 60;
    tft_rect(x, y, w, h, SECTION_FRAME_COLOR, 1);
    tft_fill_rect(x, y, badge_w, h, valid ? COLOR_ORANGE : SECTION_FRAME_COLOR);
    tft_text_bold(x + 7, y + h / 2 - 12, "FUEL", 0x0000, 2);
    tft_text(x + 21, y + h / 2 + 6, "EST", 0x0000, 1);

    const int now_x = x + badge_w + 8;
    const int divider_x = x + 172;
    const int right_x = divider_x + 7;
    const int right_end = x + w - 5;
    tft_fill_rect(divider_x, y + 5, 1, h - 10, SECTION_FRAME_COLOR);

    char text[16];
    /* Instant reading. */
    if (!valid) {
        tft_text_bold(now_x, y + 5, "NOW", COLOR_LABEL, 2);
        tft_text_bold(now_x, y + 24, "--", COLOR_GREY, 4);
    } else if (est.fuel_cut) {
        tft_text_bold(now_x, y + 5, "FUEL CUT", COLOR_CYAN, 2);
        tft_text_bold(now_x, y + 24, "0.0", COLOR_CYAN, 4);
    } else {
        bool per_km = est.instant_unit == FUEL_UNIT_L_PER_100KM;
        tft_text_bold(now_x, y + 5, per_km ? "L/100KM" : "L/H", COLOR_LABEL, 2);
        if (est.speed_missing) {
            /* Riding but no road speed: say why it isn't L/100km. */
            tft_text(now_x + 44, y + 9, "NO SPEED", COLOR_ORANGE, 1);
        }
        format_tenths(per_km ? est.l_per_100km : est.fuel_flow_l_per_h, text, sizeof(text));
        tft_text_bold(now_x, y + 24, text, COLOR_YELLOW, 4);
    }

    /* Since start-up: average consumption, then litres used. Each row is
     * "LABEL value unit" with the small unit right-aligned. */
    const int avg_y = y + 8;
    const int used_y = y + h - 22;
    tft_text_bold(right_x, avg_y, "AVG", COLOR_LABEL, 2);
    tft_text_bold(right_x, used_y, "USED", COLOR_LABEL, 2);
    bool have_totals = ignition_on || est.total_fuel_l > 0.0f;
    bool avg_per_km = est.avg_unit == FUEL_UNIT_L_PER_100KM;
    const char *avg_unit = avg_per_km ? "L/100KM" : "L/H";
    int avg_unit_x = right_end - tft_text_width(avg_unit, 1);
    int used_unit_x = right_end - tft_text_width("L", 1);
    tft_text(avg_unit_x, avg_y + 7, avg_unit, COLOR_LABEL, 1);
    tft_text(used_unit_x, used_y + 7, "L", COLOR_LABEL, 1);
    if (!have_totals) {
        tft_text_right(avg_unit_x - 4, avg_y, "--", COLOR_GREY, 2);
        tft_text_right(used_unit_x - 4, used_y, "--", COLOR_GREY, 2);
        return;
    }
    format_tenths(avg_per_km ? est.avg_l_per_100km : est.avg_l_per_h, text, sizeof(text));
    tft_text_right(avg_unit_x - 4, avg_y, text, COLOR_WHITE, 2);
    format_litres(est.total_fuel_l, text, sizeof(text));
    tft_text_right(used_unit_x - 4, used_y, text, COLOR_WHITE, 2);
}

/* Fuel estimate refresh, on the CAN/protocol core (0) beside the poller.
 * Independent of how polling is scheduled; vTaskDelayUntil keeps a steady
 * 100 ms step and never waits on the bus. */
#define FUEL_UPDATE_PERIOD_MS 100
#define FUEL_LOG_EVERY 20 /* one serial line every 2 s for bench checks */

static void trip_load(void)
{
    nvs_handle_t nvs;
    if (nvs_open(TRIP_NVS_NAMESPACE, NVS_READONLY, &nvs) != ESP_OK) return;
    inj_meter_totals_t totals;
    size_t len = sizeof(totals);
    if (nvs_get_blob(nvs, TRIP_NVS_KEY, &totals, &len) == ESP_OK && len == sizeof(totals)) {
        s_trip = totals;
    }
    nvs_close(nvs);
}

static void trip_save(const inj_meter_totals_t *totals)
{
    nvs_handle_t nvs;
    if (nvs_open(TRIP_NVS_NAMESPACE, NVS_READWRITE, &nvs) != ESP_OK) return;
    if (nvs_set_blob(nvs, TRIP_NVS_KEY, totals, sizeof(*totals)) != ESP_OK ||
        nvs_commit(nvs) != ESP_OK) {
        ESP_LOGW(TAG, "trip not saved");
    }
    nvs_close(nvs);
}

static inj_meter_totals_t trip_snapshot(void)
{
    inj_meter_totals_t totals;
    portENTER_CRITICAL(&s_bike_lock);
    totals = s_trip;
    portEXIT_CRITICAL(&s_bike_lock);
    return totals;
}

/* One line with the raw sums, so flow and dead time can be refitted from
 * fill-ups later: litres = flow * (open - injections * dead). */
static void trip_format(const inj_meter_totals_t *t, char *out, size_t out_size)
{
    int millilitres = (int)(inj_meter_litres(&s_inj_config, t) * 1000.0 + 0.5);
    snprintf(out, out_size, "TRIP fuel=%d.%03dL injections=%llu open=%llums run=%lus "
             "flow=%d.%dcc/min dead=%dus",
             millilitres / 1000, millilitres % 1000, (unsigned long long)t->injections,
             (unsigned long long)t->open_ms, (unsigned long)t->running_s,
             CONFIG_FUEL_INJ_FLOW_CC_MIN_X10 / 10, CONFIG_FUEL_INJ_FLOW_CC_MIN_X10 % 10,
             CONFIG_FUEL_INJ_DEAD_TIME_US);
}

/* Saves the trip every TRIP_SAVE_PERIOD_MS while it grows, and at once
 * when it stops growing (engine stopped), so a key-off loses at most one
 * period of riding. */
static void trip_service(void)
{
    static inj_meter_totals_t saved;
    static TickType_t saved_at;
    if (s_trip_reset_requested) {
        s_trip_reset_requested = false;
        portENTER_CRITICAL(&s_bike_lock);
        memset(&s_trip, 0, sizeof(s_trip));
        portEXIT_CRITICAL(&s_bike_lock);
        ESP_LOGI(TAG, "trip reset");
    }
    inj_meter_totals_t now = trip_snapshot();
    if (!memcmp(&now, &saved, sizeof(now))) return;
    static inj_meter_totals_t previous;
    bool growing = memcmp(&now, &previous, sizeof(now)) != 0;
    previous = now;
    if (growing && xTaskGetTickCount() - saved_at < pdMS_TO_TICKS(TRIP_SAVE_PERIOD_MS)) return;
    trip_save(&now);
    saved = now;
    saved_at = xTaskGetTickCount();
}

/* Injector calibration from steady riding (steady_cal.h). Owned by
 * fuel_task: fed every step, saved to NVS every CAL_SAVE_PERIOD_MS while
 * it grows and as soon as the engine stops, reported over serial at
 * start-up, after each save and on the BLE "CAL" command. */
#define CAL_NVS_KEY "steady1"
#define CAL_SAVE_PERIOD_MS (5U * 60U * 1000U)
#define CAL_MIN_DEAD_BASIS 2.0f
static steady_cal_t s_cal;
static volatile bool s_cal_report_requested;
static volatile bool s_cal_report_to_ble;
static volatile bool s_cal_reset_requested;

static void ble_send_text(const char *text);

static void cal_load(void)
{
    steady_cal_config_t config = STEADY_CAL_DEFAULT_CONFIG();
    steady_cal_init(&s_cal, &config);
    nvs_handle_t nvs;
    if (nvs_open(TRIP_NVS_NAMESPACE, NVS_READONLY, &nvs) != ESP_OK) return;
    size_t len = sizeof(s_cal.totals);
    esp_err_t err = nvs_get_blob(nvs, CAL_NVS_KEY, &s_cal.totals, &len);
    nvs_close(nvs);
    if (err != ESP_OK || len != sizeof(s_cal.totals) ||
        s_cal.totals.version != STEADY_CAL_VERSION) {
        steady_cal_reset(&s_cal);
    }
}

static void cal_save(void)
{
    nvs_handle_t nvs;
    if (nvs_open(TRIP_NVS_NAMESPACE, NVS_READWRITE, &nvs) != ESP_OK) return;
    if (nvs_set_blob(nvs, CAL_NVS_KEY, &s_cal.totals, sizeof(s_cal.totals)) != ESP_OK ||
        nvs_commit(nvs) != ESP_OK) {
        ESP_LOGW(TAG, "calibration not saved");
    }
    nvs_close(nvs);
}

static steady_cal_sample_t cal_sample(void)
{
    bool engine_fresh, power_fresh;
    bike_can_data_t bike = bike_snapshot(&engine_fresh, &power_fresh);
    size_t map = row_index_for_pid(0x0B);
    size_t iat = row_index_for_pid(0x0F);
    size_t thr = row_index_for_pid(0x11);
    size_t o2 = row_index_for_pid(0x14);
    size_t stft = row_index_for_pid(0x06);
    size_t ltft = row_index_for_pid(0x07);
    size_t temp = row_index_for_pid(0x05);
    steady_cal_sample_t sample = {
        .rpm = bike.rpm,
        .pulse_ms = bike.inj_raw / 10000.0f,
        .map_kpa = (float)s_row_values[map],
        .intake_temp_c = (float)s_row_values[iat],
        .throttle_pct = s_row_values[thr] / 10.0f,  /* rows store tenths */
        .o2_volts = s_row_values[o2] / 1000.0f,     /* row stores millivolts */
        .stft_pct = s_row_values[stft] / 10.0f,
        .ltft_pct = s_row_values[ltft] / 10.0f,
        .engine_temp_c = (float)s_row_values[temp],
        .battery_v = bike.battery_mv / 1000.0f,
        .rpm_valid = engine_fresh,
        .map_valid = s_row_valid[map],
        .intake_temp_valid = s_row_valid[iat],
        .throttle_valid = s_row_valid[thr],
        .o2_valid = s_row_valid[o2] && s_row_values[o2] != O2_INACTIVE_MV,
        .trims_valid = s_row_valid[stft] && s_row_valid[ltft],
        .engine_temp_valid = s_row_valid[temp],
        .battery_valid = power_fresh,
    };
    return sample;
}

/* "+4.0" / "-1.2" without float printf. */
static void format_signed_tenths(float value, char *out, size_t out_size)
{
    int tenths = (int)lroundf(value * 10.0f);
    snprintf(out, out_size, "%c%d.%d", tenths < 0 ? '-' : '+', abs(tenths) / 10, abs(tenths) % 10);
}

static void cal_line(const char *line, bool to_ble)
{
    ESP_LOGI(TAG, "%s", line);
    if (to_ble) {
        ble_send_text(line);
        ble_send_text("\r");
    }
}

/* One line per RPM band with data, then the dead time and the overall
 * flow implied at the configured VE. */
static void cal_report(bool to_ble)
{
    const fuel_estimator_config_t *fc = &s_fuel_estimator.config;
    steady_cal_engine_t engine = {
        .displacement_cc = s_fuel_estimator.displacement_m3 * 1e6f,
        .volumetric_efficiency = fc->volumetric_efficiency,
        .stoich_afr = fc->stoich_afr,
        .fuel_density_g_per_ml = fc->fuel_density_g_per_ml,
        .dead_time_ms = s_inj_config.dead_time_ms,
    };
    int ve = (int)lroundf(fc->volumetric_efficiency * 100.0f);
    char line[192];
    double flow_sum = 0.0, flow_seconds = 0.0;
    uint32_t windows = 0;
    for (int i = 0; i < STEADY_CAL_BINS; ++i) {
        steady_cal_band_t band;
        if (!steady_cal_band(&s_cal.totals, i, &band)) continue;
        windows += band.windows;
        float flow = 0.0f;
        bool flow_ok = steady_cal_band_flow(&band, &engine, &flow);
        if (flow_ok) {
            flow_sum += (double)flow * band.seconds;
            flow_seconds += band.seconds;
        }
        char stft[12], ltft[12];
        format_signed_tenths(band.stft_pct, stft, sizeof(stft));
        format_signed_tenths(band.ltft_pct, ltft, sizeof(ltft));
        int pw = (int)lroundf(band.pulse_ms * 100.0f);
        int o2 = (int)lroundf(band.o2_volts * 100.0f);
        int thr = (int)lroundf(band.throttle_pct * 10.0f);
        snprintf(line, sizeof(line),
                 "CAL %d-%drpm %lus n=%lu rpm=%d thr=%d.%d%% map=%dkPa iat=%dC "
                 "pw=%d.%02dms stft=%s%% ltft=%s%% o2=%d.%02dV -> %dcc/min@VE%d",
                 i * STEADY_CAL_BIN_RPM, i * STEADY_CAL_BIN_RPM + STEADY_CAL_BIN_RPM - 1,
                 (unsigned long)band.seconds, (unsigned long)band.windows,
                 (int)lroundf(band.rpm), thr / 10, thr % 10, (int)lroundf(band.map_kpa),
                 (int)lroundf(band.intake_temp_c), pw / 100, pw % 100, stft, ltft,
                 o2 / 100, o2 % 100, flow_ok ? (int)lroundf(flow) : 0, ve);
        cal_line(line, to_ble);
    }

    char dead_text[32] = "n/a";
    float dead = 0.0f, basis = 0.0f;
    if (steady_cal_dead_time(&s_cal.totals, &dead, &basis) && basis >= CAL_MIN_DEAD_BASIS) {
        int dead_us = (int)lroundf(dead * 1000.0f);
        snprintf(dead_text, sizeof(dead_text), "%dus", dead_us);
    }
    int basis_tenths = (int)lroundf(basis * 10.0f);
    snprintf(line, sizeof(line),
             "CAL summary windows=%lu dead=%s (basis %d.%d, needs %d) flow=%dcc/min@VE%d "
             "(using dead %dus)",
             (unsigned long)windows, dead_text, basis_tenths / 10, basis_tenths % 10,
             (int)CAL_MIN_DEAD_BASIS,
             flow_seconds > 0.0 ? (int)lround(flow_sum / flow_seconds) : 0, ve,
             CONFIG_FUEL_INJ_DEAD_TIME_US);
    cal_line(line, to_ble);
    if (to_ble) ble_send_text("\r>");
}

static void cal_service(float dt_s)
{
    static bool dirty;
    static TickType_t saved_at;
    if (s_cal_reset_requested) {
        s_cal_reset_requested = false;
        steady_cal_reset(&s_cal);
        dirty = true;
        ESP_LOGI(TAG, "calibration reset");
    }
    steady_cal_sample_t sample = cal_sample();
    if (steady_cal_add(&s_cal, &sample, dt_s) >= 0) dirty = true;

    bool stopped = !sample.rpm_valid || sample.rpm < 200.0f;
    TickType_t now = xTaskGetTickCount();
    if (dirty && (stopped || now - saved_at >= pdMS_TO_TICKS(CAL_SAVE_PERIOD_MS))) {
        cal_save();
        dirty = false;
        saved_at = now;
        s_cal_report_requested = true;
    }
    if (s_cal_report_requested) {
        bool to_ble = s_cal_report_to_ble;
        s_cal_report_requested = false;
        s_cal_report_to_ble = false;
        cal_report(to_ble);
    }
}

static void fuel_task(void *arg)
{
    (void)arg;
    TickType_t last = xTaskGetTickCount();
    unsigned log_count = 0;
    while (true) {
        TickType_t before = last;
        vTaskDelayUntil(&last, pdMS_TO_TICKS(FUEL_UPDATE_PERIOD_MS));
        if (s_self_test_active) {
            continue;
        }
        fuel_estimate_update((uint32_t)pdTICKS_TO_MS(last - before));
        trip_service();
        cal_service(pdTICKS_TO_MS(last - before) / 1000.0f);
        if (++log_count >= FUEL_LOG_EVERY) {
            log_count = 0;
            fuel_estimate_t est;
            portENTER_CRITICAL(&s_fuel_lock);
            est = s_fuel_estimate;
            portEXIT_CRITICAL(&s_fuel_lock);
            /* Integer tenths/hundredths so the log needs no float printf. */
            ESP_LOGI(TAG, "FUEL valid=%d cut=%d spd=%d(valid=%d) lambda=%d.%02d flow=%d.%02dL/h "
                     "inst=%d.%dL/100km(%s) avg=%d.%d%s used=%d.%03dL dist=%d.%02dkm",
                     est.valid, est.fuel_cut, (int)est.speed_kmh, est.speed_valid,
                     (int)(est.lambda * 100) / 100, (int)(est.lambda * 100) % 100,
                     (int)(est.fuel_flow_l_per_h * 100) / 100,
                     (int)(est.fuel_flow_l_per_h * 100) % 100,
                     (int)(est.l_per_100km * 10) / 10, (int)(est.l_per_100km * 10) % 10,
                     est.instant_unit == FUEL_UNIT_L_PER_100KM ? "shown" : "L/h shown",
                     (int)((est.avg_unit == FUEL_UNIT_L_PER_100KM ? est.avg_l_per_100km
                                                                   : est.avg_l_per_h) * 10) / 10,
                     (int)((est.avg_unit == FUEL_UNIT_L_PER_100KM ? est.avg_l_per_100km
                                                                   : est.avg_l_per_h) * 10) % 10,
                     est.avg_unit == FUEL_UNIT_L_PER_100KM ? "L/100km" : "L/h",
                     (int)(est.total_fuel_l * 1000) / 1000, (int)(est.total_fuel_l * 1000) % 1000,
                     (int)(est.total_distance_km * 100) / 100,
                     (int)(est.total_distance_km * 100) % 100);
            /* The bike's own broadcast, logged beside the estimate so the
             * injection-time candidate can be compared with fuel flow. */
            bool engine_fresh, power_fresh;
            bike_can_data_t bike = bike_snapshot(&engine_fresh, &power_fresh);
            ESP_LOGI(TAG, "BIKE engine=%d rpm=%u inj_raw=%u temp_raw=%u status=%02X "
                     "power=%d batt=%u.%uV",
                     engine_fresh, bike.rpm, bike.inj_raw, bike.temp_raw, bike.status,
                     power_fresh, bike.battery_mv / 1000U, (bike.battery_mv / 100U) % 10U);
            inj_meter_totals_t trip = trip_snapshot();
            char trip_line[160];
            trip_format(&trip, trip_line, sizeof(trip_line));
            ESP_LOGI(TAG, "%s", trip_line);
        }
    }
}

/* Stop-now alert state. Enters only after the reading has held at or above
 * TEMP_CRITICAL_C for CONFIG_DASH_CHT_CRITICAL_CONFIRM_MS (one bad sample
 * can't trigger it) and clears only below the hysteresis band, so it
 * doesn't flicker at the threshold. Display task only. */
static bool cht_critical_update(bool valid, int temp_c)
{
    static bool critical;
    static bool above;
    static TickType_t above_since;
    TickType_t now = xTaskGetTickCount();
    if (valid && temp_c >= TEMP_CRITICAL_C) {
        if (!above) {
            above = true;
            above_since = now;
        }
        if (now - above_since >= pdMS_TO_TICKS(CONFIG_DASH_CHT_CRITICAL_CONFIRM_MS)) {
            critical = true;
        }
    } else {
        above = false;
        if (!valid || temp_c < TEMP_CRITICAL_C - CONFIG_DASH_CHT_CRITICAL_HYSTERESIS_C) {
            critical = false;
        }
    }
    return critical;
}

/* Layout, 320x240 landscape:
 *
 *   +---------------------------+-----------+
 *   | CYL HEAD TEMP             | RPM  5200 |
 *   |                 92 C      | [gauge]   |
 *   | [temperature gauge]       | THR   31% |
 *   |                           | [gauge]   |
 *   +-------+-------------------+-----------+
 *   | FUEL  | L/100KM           | AVG       |
 *   |  EST  | 3.4               |      3.1  |
 *   +-------+-------------------+-----------+
 *   +- MIXTURE ---------+ +- INTAKE --------+
 *   | O2-1 / O2-2       | | MAP / AIR       |
 *   | SHORT / LONG trim | | SPK / BAR       |
 *   +-------------------+ +-----------------+
 *
 * Cylinder head temperature stays the hero: biggest number, full-width
 * gauge, and the flashing red alert at TEMP_CRITICAL_C. */
static void tft_render(void)
{
    bool ignition_on = s_last_response != 0 &&
                       xTaskGetTickCount() - s_last_response <=
                       pdMS_TO_TICKS(TFT_DATA_TIMEOUT_MS);
    bool blink_on = ((xTaskGetTickCount() / pdMS_TO_TICKS(250)) % 2) == 0;
    bool bike_engine_fresh, bike_power_fresh;
    bike_can_data_t bike = bike_snapshot(&bike_engine_fresh, &bike_power_fresh);
    tft_clear(0x0000);

    /* Hero: cylinder head temperature. This PID has whole-degree
     * resolution only, so the value reads e.g. "92", not "92.5". */
    const int hero_w = 202;
    bool hero_valid = ignition_on && s_row_valid[0];
    int temp_c = hero_valid ? s_row_values[0] : 0;
    bool hero_critical = cht_critical_update(hero_valid, temp_c);
    if (hero_critical) {
        tft_fill_rect(0, 0, hero_w, 84, COLOR_RED);
        if (blink_on) {
            tft_rect(0, 0, hero_w, 84, COLOR_WHITE, 3);
        }
    }
    tft_text_bold(8, 5, "CYL HEAD TEMP", hero_critical ? COLOR_WHITE : COLOR_LABEL, 2);
    if (pid_scan_running()) {
        tft_text(174, 9, "SCAN", COLOR_CYAN, 1);
    }
    if (!hero_critical) {
        tft_draw_battery(8, 26, bike_power_fresh, bike.battery_mv);
    }
    char hero_text[8];
    uint16_t hero_color;
    if (!hero_valid) {
        snprintf(hero_text, sizeof(hero_text), "--");
        hero_color = COLOR_GREY;
    } else {
        snprintf(hero_text, sizeof(hero_text), "%d", temp_c);
        hero_color = hero_critical ? 0x0000 : temp_color(temp_c);
    }
    const int unit_x = 166;
    tft_text_right(unit_x - 4, 22, hero_text, hero_color, 6);
    if (hero_valid) {
        tft_text_bold(unit_x, 22, "C", hero_color, 3);
    }
    if (hero_critical) {
        if (blink_on) {
            tft_text_bold(8, 66, "STOP ENGINE", COLOR_WHITE, 2);
        }
    } else {
        tft_gauge(8, 70, hero_w - 14, 9, temp_c, hero_valid,
                  TEMP_GAUGE_MIN_C, TEMP_GAUGE_MAX_C, temp_color);
    }

    /* RPM and throttle, the two "what is the engine doing" readings. */
    const int side_x = hero_w + 8;
    const int side_right = 314;
    tft_fill_rect(hero_w + 2, 4, 1, 76, SECTION_FRAME_COLOR);
    size_t rpm_i = row_index_for_pid(0x0C);
    bool rpm_valid = ignition_on && s_row_valid[rpm_i];
    /* At or above the shift point the whole RPM block flashes red with
     * white text: change up or roll off now. */
    bool shift = rpm_valid && s_row_values[rpm_i] >= CONFIG_DASH_RPM_SHIFT;
    bool shift_flash = shift && blink_on;
    if (shift_flash) {
        tft_fill_rect(hero_w + 4, 2, 320 - hero_w - 4, 40, COLOR_RED);
    }
    tft_text_bold(side_x, 8, "RPM", shift_flash ? COLOR_WHITE : COLOR_LABEL, 2);
    char text[16];
    if (rpm_valid) {
        snprintf(text, sizeof(text), "%d", s_row_values[rpm_i]);
        tft_text_right(side_right, 8, text,
                       shift_flash ? COLOR_WHITE : rpm_color(s_row_values[rpm_i]), 2);
    } else {
        tft_text_right(side_right, 8, "--", COLOR_GREY, 2);
    }
    tft_gauge(side_x, 27, side_right - side_x, 8, rpm_valid ? s_row_values[rpm_i] : 0,
              rpm_valid, 0, RPM_GAUGE_MAX, rpm_color);

    size_t thr_i = row_index_for_pid(0x11);
    bool thr_valid = ignition_on && s_row_valid[thr_i];
    int thr_pct = thr_valid ? (s_row_values[thr_i] + 5) / 10 : 0;
    tft_text_bold(side_x, 46, "THR", COLOR_LABEL, 2);
    if (thr_valid) {
        snprintf(text, sizeof(text), "%d%%", thr_pct);
        tft_text_right(side_right, 46, text, thr_percent_color(thr_pct), 2);
    } else {
        tft_text_right(side_right, 46, "--", COLOR_GREY, 2);
    }
    tft_gauge(side_x, 65, side_right - side_x, 8, thr_pct, thr_valid, 0, 100,
              thr_percent_color);

    /* Fuel estimate. */
    tft_draw_fuel_panel(4, 88, 312, 54, ignition_on);

    /* Mixture: O2 sensor voltages (narrowband: about 0.1 V lean, 0.45 V
     * stoichiometric, 0.8 V rich) and the ECU's fuel trims. */
    const int panel_y = 152;
    const int panel_h = 84;
    tft_panel(4, panel_y, 154, panel_h, "MIXTURE");
    tft_draw_field(10, 152, panel_y + 9, row_index_for_pid(0x14), ignition_on, o2_color);
    tft_draw_field(10, 152, panel_y + 27, row_index_for_pid(0x15), ignition_on, o2_color);
    tft_draw_field(10, 152, panel_y + 45, row_index_for_pid(0x06), ignition_on, trim_color);
    tft_draw_field(10, 152, panel_y + 63, row_index_for_pid(0x07), ignition_on, trim_color);

    /* Intake side and ignition timing, plus ambient pressure. */
    tft_panel(162, panel_y, 154, panel_h, "INTAKE");
    tft_draw_field(168, 310, panel_y + 9, row_index_for_pid(0x0B), ignition_on, NULL);
    tft_draw_field(168, 310, panel_y + 27, row_index_for_pid(0x0F), ignition_on, NULL);
    tft_draw_field(168, 310, panel_y + 45, row_index_for_pid(0x0E), ignition_on, spark_color);
    /* Test field: the broadcast value that looks like injection time,
     * shown as ms assuming 0.1 us per bit. Cyan marks it as unconfirmed. */
    tft_text_bold(168, panel_y + 63, "INJ", COLOR_CYAN, 2);
    if (ignition_on && bike_engine_fresh) {
        snprintf(text, sizeof(text), "%u.%02u", bike.inj_raw / 10000U, (bike.inj_raw / 100U) % 100U);
        tft_text(214, panel_y + 70, "ms", COLOR_LABEL, 1);
        tft_text_right(310, panel_y + 63, text, COLOR_CYAN, 2);
    } else {
        tft_text_right(310, panel_y + 63, "--", COLOR_GREY, 2);
    }

    tft_flush();
}

/* obd_poller callback, runs on the CAN/protocol core (0). The poller sends
 * the next request as soon as a reply arrives and sleeps until something is
 * due, so the display core never waits on the bus. */
static void telemetry_on_value(uint8_t pid, const uint8_t *data, uint8_t len, void *ctx)
{
    (void)ctx;
    if (s_self_test_active) {
        return; /* display_task owns the rows until its self-test ends */
    }
    if (pid == 0x0C) {
        bool engine_fresh, power_fresh;
        bike_snapshot(&engine_fresh, &power_fresh);
        if (engine_fresh) {
            s_last_response = xTaskGetTickCount();
            return; /* the broadcast RPM is newer than any poll */
        }
    }
    int value;
    if (!decode_pid_value(pid, data, len, &value)) {
        return;
    }
    for (size_t i = 0; i < TFT_TELEMETRY_ROWS; ++i) {
        if (s_rows[i].pid == pid) {
            s_row_values[i] = value;
            s_row_valid[i] = true;
        }
    }
    s_last_response = xTaskGetTickCount();
}

/* Non-blocking: queues the frame in the TWAI driver and returns. The driver
 * keeps a pointer to the frame until it is on the wire, so frames live in a
 * ring with more slots than the driver can hold (queue + one in hardware). */
static bool obd_can_send(uint32_t id, bool extended, const uint8_t data[8], void *ctx)
{
    (void)ctx;
    static uint8_t buffers[CAN_TX_QUEUE_DEPTH + 2][8];
    static twai_frame_t frames[CAN_TX_QUEUE_DEPTH + 2];
    static size_t next;
    memcpy(buffers[next], data, 8);
    frames[next] = (twai_frame_t) {
        .header = { .id = id, .ide = extended, .dlc = 8 },
        .buffer = buffers[next],
        .buffer_len = 8,
    };
    if (twai_node_transmit(s_twai, &frames[next], 0) != ESP_OK) {
        return false;
    }
    next = (next + 1) % (CAN_TX_QUEUE_DEPTH + 2);
    return true;
}

static void telemetry_start(void)
{
    static obd_poll_entry_t entries[TFT_TELEMETRY_ROWS];
    for (size_t i = 0; i < TFT_TELEMETRY_ROWS; ++i) {
        entries[i] = (obd_poll_entry_t) { s_rows[i].pid, s_rows[i].period_ms };
    }
    obd_poller_config_t config = OBD_POLLER_DEFAULT_CONFIG();
    config.response_timeout_ms = OBD_RESPONSE_TIMEOUT_MS;
    config.entries = entries;
    config.entry_count = TFT_TELEMETRY_ROWS;
    config.send = obd_can_send;
    config.on_value = telemetry_on_value;
    config.core = 0;
    ESP_ERROR_CHECK(obd_poller_start(&config));
}

static void display_task(void *arg)
{
    (void)arg;
    tft_start();
#if CONFIG_BOOT_DIAG_ALWAYS_SHOW
    bool hold_boot_diag = true;
#else
    bool hold_boot_diag = boot_diag_noteworthy();
#endif
    if (hold_boot_diag) vTaskDelay(pdMS_TO_TICKS(TFT_BOOT_DIAG_HOLD_MS));

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
        if (tft_health_tick() && tft_begin_frame()) tft_render();
        vTaskDelay(pdMS_TO_TICKS(TFT_REFRESH_PERIOD_MS));
    }
    s_row_valid[0] = false;
    s_self_test_active = false;
    boot_diag_mark(BOOT_DIAG_STAGE_SELF_TEST_DONE);

    /* By now it's ~6s since boot (tft_start()'s startup delay plus this
     * self-test), well past any engine-cranking voltage sag. Re-run the
     * panel init as a second line of defence against the panel having come
     * up wrong the first time - see tft_panel_init_sequence(). */
    /* The reset pulse is a GPIO toggle that does not wait for the SPI queue,
     * so let the last self-test frame finish first. One-off at boot; a
     * frame takes ~61 ms, so the 200 ms bound is never normally reached. */
    tft_wait_idle(pdMS_TO_TICKS(200));
    tft_panel_init_sequence();

    TickType_t running_at = xTaskGetTickCount() + pdMS_TO_TICKS(10000);
    while (true) {
        if (tft_health_tick() && tft_begin_frame()) tft_render();
        if ((int32_t)(xTaskGetTickCount() - running_at) >= 0) {
            boot_diag_mark(BOOT_DIAG_STAGE_RUNNING);
        }
        vTaskDelay(pdMS_TO_TICKS(TFT_REFRESH_PERIOD_MS));
    }
}

static void ble_send_text(const char *text);

/* PID discovery test (pid_scan_task.h). Requests ride the poller's one-off
 * query slot, so the dashboard keeps polling between them. */
static bool s_scan_to_ble; /* the scan was asked for over BLE: echo the report there */

static bool scan_query(uint8_t pid, uint32_t timeout_ms)
{
    uint8_t reply[8];
    uint8_t reply_len = 0;
    return obd_poller_query(pid, reply, &reply_len, timeout_ms);
}

static void scan_line_out(const char *line, void *ctx)
{
    (void)ctx;
    ESP_LOGI("pid_scan", "%s", line);
    if (s_scan_to_ble) {
        ble_send_text(line);
        ble_send_text("\r");
    }
}

static void scan_init(void)
{
    pid_scan_task_config_t config = {
        .ids = {
            .request_id = OBD_REQUEST_ID,
            .response_id_min = OBD_RESPONSE_ID_MIN,
            .response_id_max = OBD_RESPONSE_ID_MAX,
        },
        .query = scan_query,
        .query_timeout_ms = OBD_QUERY_TIMEOUT_MS,
        .gap_ms = CONFIG_PID_SCAN_GAP_MS,
        .report_period_ms = CONFIG_PID_SCAN_REPORT_PERIOD_S * 1000U,
        .line_out = scan_line_out,
        .core = 0,
        .priority = 3,
    };
    pid_scan_task_init(&config);
#if CONFIG_PID_SCAN_AT_BOOT
    if (pid_scan_start() != ESP_OK) {
        ESP_LOGW(TAG, "PID scan could not start; the dashboard runs without it");
    }
#endif
}

/* ELM327 passthrough: the poller sends it ahead of scheduled polling, so
 * only the BLE task waits and the dashboard keeps updating. */
static bool obd_query_pid(uint8_t pid, char *response, size_t response_size)
{
    uint8_t reply[8];
    uint8_t reply_len = 0;
    if (!obd_poller_query(pid, reply, &reply_len, OBD_QUERY_TIMEOUT_MS)) {
        snprintf(response, response_size, "NO DATA");
        return false;
    }
    int used = 0;
    response[0] = '\0';
    for (uint8_t i = 0; i < reply_len && used < (int)response_size - 4; ++i) {
        used += snprintf(response + used, response_size - (size_t)used,
                         "%02X%s", reply[i], i + 1 < reply_len ? " " : "");
    }
    return true;
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
    } else if (!strcmp(command, "SCAN") || !strcmp(command, "SCAN START")) {
        s_scan_to_ble = true;
        ble_send_text(pid_scan_start() == ESP_OK ? "OK\r\r>" : "ERROR\r\r>");
    } else if (!strcmp(command, "SCAN STOP")) {
        pid_scan_stop();
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "SCAN REPORT")) {
        s_scan_to_ble = true;
        pid_scan_request_report();
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "TRIP")) {
        inj_meter_totals_t trip = trip_snapshot();
        char trip_line[160];
        trip_format(&trip, trip_line, sizeof(trip_line));
        ble_send_text(trip_line);
        ble_send_text("\r\r>");
    } else if (!strcmp(command, "CAL")) {
        s_cal_report_to_ble = true;
        s_cal_report_requested = true;
    } else if (!strcmp(command, "CAL RESET")) {
        s_cal_reset_requested = true;
        ble_send_text("OK\r\r>");
    } else if (!strcmp(command, "TRIP RESET")) {
        s_trip_reset_requested = true;
        ble_send_text("OK\r\r>");
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
        bool obd_woken = obd_poller_feed_frame_from_isr(packet.id, packet.data, packet.len);
        bool scan_woken = pid_scan_feed_frame_from_isr(packet.id, packet.extended, packet.data,
                                                       packet.len);
        return higher_priority_task_woken == pdTRUE || obd_woken || scan_woken;
    }
    return false;
}

static void can_task(void *arg)
{
    (void)arg;
    struct can_packet packet;
    char line[128];
    TickType_t last_log = 0;
    const size_t rpm_row = row_index_for_pid(0x0C);
    while (xQueueReceive(s_can_queue, &packet, portMAX_DELAY) == pdTRUE) {
        TickType_t now = xTaskGetTickCount();
        bike_can_data_t decoded;
        portENTER_CRITICAL(&s_bike_lock);
        decoded = s_bike;
        portEXIT_CRITICAL(&s_bike_lock);
        int group = bike_can_decode(&s_bike_can_config, packet.id, packet.extended, packet.data,
                                    packet.len, &decoded);
        if (group != BIKE_CAN_NONE) {
            portENTER_CRITICAL(&s_bike_lock);
            s_bike = decoded;
            if (group & BIKE_CAN_ENGINE) {
                s_bike_engine_at = now;
                int64_t now_us = esp_timer_get_time();
                if (s_trip_frame_us != 0) {
                    inj_meter_add(&s_inj_config, &s_trip, decoded.rpm, decoded.inj_raw / 10000.0f,
                                  (float)(now_us - s_trip_frame_us) / 1000.0f);
                }
                s_trip_frame_us = now_us;
            }
            if (group & BIKE_CAN_POWER) s_bike_power_at = now;
            portEXIT_CRITICAL(&s_bike_lock);
            if ((group & BIKE_CAN_ENGINE) && !s_self_test_active) {
                s_row_values[rpm_row] = decoded.rpm;
                s_row_valid[rpm_row] = true;
                s_last_response = now;
            }
        }
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

    twai_onchip_node_config_t config = {
        .io_cfg = {
            .tx = CAN_TX_GPIO,
            .rx = CAN_RX_GPIO,
        },
        .bit_timing = {
            .bitrate = CAN_BITRATE,
            .sp_permill = 750,
        },
        .tx_queue_depth = CAN_TX_QUEUE_DEPTH,
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
    boot_diag_init();

    s_inj_config = inj_meter_config_from_kconfig();
    trip_load();
    cal_load();
    s_cal_report_requested = true; /* print what was learnt so far at start-up */

    /* Display first: it has the longest start-up (panel settle delay), and
     * if anything below fails the screen is already coming up. */
    xTaskCreatePinnedToCore(display_task, "display_task", 8192, NULL, 4, NULL, 1);
    twai_start();
    xTaskCreate(can_task, "can_task", 4096, NULL, 5, NULL);
    /* CAN/OBD polling stays on core 0 with the CAN and BLE stacks; the
     * display gets its own core (1) so rendering never waits on the bus. */
    telemetry_start();
    ble_start();

    /* Fuel estimate on core 0 beside the polling; the display only reads
     * its published result, which reads as "no data" until the first step. */
    fuel_estimator_config_t fuel_config = fuel_estimator_config_from_kconfig();
    fuel_estimator_init(&s_fuel_estimator, &fuel_config);
    xTaskCreatePinnedToCore(fuel_task, "fuel_task", 4096, NULL, 4, NULL, 0);
    boot_diag_mark(BOOT_DIAG_STAGE_TASKS_STARTED);

    scan_init();

    ESP_LOGI(TAG, "Ready. Connect a BLE UART app and use ATMA for raw CAN streaming.");
}
