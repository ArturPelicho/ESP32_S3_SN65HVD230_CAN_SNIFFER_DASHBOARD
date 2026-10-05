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

## Fuel consumption estimate

The FUEL panel (amber `FUEL EST` badge) shows instant consumption and the average since start-up. The instant reading is L/100km when moving (RPM at or above 2500 and a road speed is available) and L/h when stopped or idling. It shows `FUEL CUT` during engine braking (closed throttle, RPM above idle, O2 fully lean). The average stays in L/h until 500 m have been covered.

L/100km needs road speed. This ECU does not answer OBD PID 0x0D, so until a speed source is added (CAN broadcast, GPS or wheel sensor), both readings stay in L/h.

The estimate uses the speed-density method: MAP, intake air temperature and RPM, corrected by the averaged narrowband O2 voltage. See `main/fuel_estimator.h` for the model and its assumptions.

Engine geometry (default ZongShen Carrera 125, 57.3 x 48.4 mm single cylinder), volumetric efficiency, stoichiometric AFR, fuel density, the unit-switch RPM and the fuel-cut thresholds are set in `idf.py menuconfig` under **Fuel injection estimate**. Volumetric efficiency is also the calibration factor: if the reading is 10 % high against a fill-to-fill check, lower it by 10 %.

The maths has a host-side test that needs no ESP-IDF:

`gcc -std=c11 -Wall -I main test/host/test_fuel_estimator.c main/fuel_estimator.c -lm -o fuel_test && ./fuel_test`
