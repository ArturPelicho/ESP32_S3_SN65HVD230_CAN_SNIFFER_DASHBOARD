# MOTA CAN Reader — Wiring / Pinout Reference

Board: **UICPAL ESP32-S3 N16R8** (16 MB flash, 8 MB Octal PSRAM, dual core, BLE).
USB-serial: **CH343**, enumerates as `COM8` on this PC.

> PSRAM is present on this module (N16R8 = Octal PSRAM). GPIO 33-37 are
> internally reserved for it and must never be wired externally.

## CAN bus (SN65HVD230 transceiver)

| Signal        | ESP32-S3 GPIO | Notes                              |
|---------------|---------------|-------------------------------------|
| CAN TX        | GPIO 17       | To transceiver `TXD`                |
| CAN RX        | GPIO 18       | To transceiver `RXD`                |
| Transceiver VCC | 3.3V        | SN65HVD230 is a 3.3V part           |
| Transceiver GND | GND         | Common ground with ESP32-S3         |
| CAN bus speed | —             | 500 kbit/s, active OBD-II mode      |
| OBD request ID | —            | `0x7DF`, ECU responses `0x7E8-0x7EF`|

## TFT display (GMT020-02-7P, ST7789 driver, 240x320 SPI, portrait)

| Signal          | ESP32-S3 GPIO | Notes                                   |
|-----------------|---------------|-------------------------------------------|
| MOSI / SDA      | GPIO 11       | Native FSPI MOSI                          |
| SCK / SCL       | GPIO 12       | Native FSPI SCK                           |
| CS              | GPIO 10       | Native FSPI CS0                           |
| DC (data/cmd)   | GPIO 38       | General I/O, safe pin                    |
| RST             | GPIO 40       | General I/O, safe pin                    |
| VCC             | 3.3V          | Do not use 5V                             |
| GND             | GND           | Common ground with ESP32-S3               |
| Backlight (LED) | —             | Tied to panel VCC on this module (no separate control pin used) |

SPI bus: `SPI2_HOST`, 20 MHz pixel clock, 16-bit RGB565, `bits_per_pixel = 16`,
`rgb_ele_order = BGR`, color inversion enabled (`esp_lcd_panel_invert_color(true)`).
Orientation: `swap_xy = false`, `mirror(x=true, y=true)` (portrait, native rotation).

## Reserved / do-not-touch pins on this module

Per the Octal PSRAM restriction (N16R8): **never wire GPIO 33, 34, 35, 36, 37**.

Strapping pins to leave alone at power-on: GPIO 0, GPIO 3, GPIO 45, GPIO 46.
See `ESP32_S3_Pinout` in this repo for the full board-level GPIO matrix.

## BLE

No external pins — BLE radio is internal to the ESP32-S3 SoC. Advertises as
device name `OBDII`, ELM327-style GATT profile (service `FFF0`, notify `FFF1`,
write `FFF2`) plus an HM-10-compatible profile (service `FFE0`, char `FFE1`).

## Quick rebuild checklist after a rewiring

1. Confirm `COM8` (CH343) is enumerated in Device Manager before flashing.
2. Build: use the ESP-IDF extension's Build command, or
   `idf.py --build-dir build build`.
3. Flash (if the ESP-IDF extension's flash command fails with a folder
   settings error, flash directly with `esptool`):
   ```
   esptool --chip esp32s3 -p COM8 -b 460800 --before default-reset --after hard-reset
     write-flash --flash-mode dio --flash-freq 80m --flash-size 16MB
     0x0     build/bootloader/bootloader.bin
     0x8000  build/partition_table/partition-table.bin
     0x10000 build/mota_can_reader.bin
   ```
4. Re-check all six wires above (CAN x2 + power/ground, TFT x5 + power/ground)
   for continuity before powering on, especially after physical shock/drops.
