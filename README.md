# MOTA CAN Reader

First milestone for the UICPAL ESP32-S3 N16R8 + Waveshare SN65HVD230.

## Hardware

| SN65HVD230 | ESP32-S3 | Note |
|---|---:|---|
| 3V3 | 3.3 V | Do not use 5 V logic power |
| GND | GND | Common ground |
| CTX/TX | GPIO 17 | TWAI TX |
| CRX/RX | GPIO 18 | TWAI RX |
| CANH | Motorcycle OBD pin 2 | Verify pinout with a meter |
| CANL | Motorcycle OBD pin 5 | Verify pinout with a meter |

The transceiver must have a 120-ohm termination only when it is an endpoint. Do not add termination until the motorcycle bus termination has been measured.

## Safety and protocol assumptions

- The firmware starts in **listen-only mode**, so it cannot acknowledge or transmit onto an unknown bus.
- Default bitrate is 500 kbit/s, but this is configurable in `main/main.c`; many motorcycles use 250 or 500 kbit/s.
- The first serial test is raw-frame capture. Do not infer standard OBD-II PIDs until the bus protocol and request/response IDs are identified.
- DMD2 will not consume arbitrary raw CAN frames. The BLE side advertises an ELM327-style service and implements a small command subset; real DMD2 compatibility may require matching the adapter profile and adding the motorcycle's actual OBD request mapping.

## Build and flash

Use the ESP-IDF PowerShell environment with the project directory as the current directory.

- `idf.py set-target esp32s3`
- `idf.py build`
- `idf.py -p COM8 flash monitor`

The serial monitor is 115200 baud. The log prints every received frame as `CAN id=... data=...` and reports TWAI error state changes.

## BLE

The device advertises as `MOTA-CAN-ELM`. Its custom UART service uses the Nordic UART Service UUIDs so it can be tested with a generic BLE UART app. The RX characteristic accepts ELM327-like text commands and the TX characteristic sends responses/notifications.

Implemented initial commands: `ATZ`, `ATI`, `ATE0/ATE1`, `ATL0/ATL1`, `ATS0/ATS1`, `ATSP0`, `ATMA`, and `0100`/`010C`/`010D` placeholders. The OBD responses are deliberately conservative until the motorcycle's actual CAN IDs and PID encoding are captured.

## Fuel injection estimate

The bottom strip of the dashboard (amber `FUEL EST` badge) shows an estimated fuel volume per injection (microlitres, `UL`) and the resulting flow (`L/H`). It is a speed-density estimate from MAP, intake air temperature and RPM, corrected by the averaged narrowband O2 voltage; see `main/fuel_estimator.h` for the model and its assumptions.

The engine geometry (default ZongShen Carrera 125, 57.3 x 48.4 mm single), volumetric efficiency, stoichiometric AFR, fuel density, O2 handling and smoothing are set in `idf.py menuconfig` under **Fuel injection estimate**. Calibrate the volumetric efficiency against a fill-to-fill consumption check.

The maths has a host-side test that needs no ESP-IDF:

`gcc -std=c11 -Wall -I main test/host/test_fuel_estimator.c main/fuel_estimator.c -lm -o fuel_test && ./fuel_test`
