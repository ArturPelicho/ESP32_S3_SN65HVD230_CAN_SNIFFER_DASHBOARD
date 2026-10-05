# MOTA CAN Reader

An all-in-one smart dashboard for the **ZongShen Carrera 125 Euro5+**
(ZS125GY-13-E55), built on an ESP32-S3. It reads the bike's CAN bus,
shows live engine telemetry on a colour TFT, estimates fuel consumption and
bridges the data to phone apps over BLE as an ELM327 adapter.

The code is kept modular on purpose: sensors, displays and even the MCU are
expected to change, so each subsystem sits behind its own module and runs in
its own non-blocking task across both cores.

## Hardware

| Part | Role | Notes |
|---|---|---|
| UICPAL ESP32-S3 N16R8 | Main MCU | 16 MB flash, 8 MB Octal PSRAM, dual core, BLE. USB-serial is a CH343 (`COM8` on the dev PC) |
| Waveshare SN65HVD230 | CAN transceiver | 3.3 V part, TWAI on GPIO 17 (TX) / 18 (RX), bus at **500 kbit/s** |
| GMT020-02-7P | Display | 2.0" ST7789, 320x240 SPI, RGB565 |
| ZongShen Carrera 125 Euro5+ ECU | Data source | OBD-II over ISO 15765-4 (CAN 11-bit / 500k), requests on `0x7DF`, replies from `0x7E8` |

Full wiring, SPI settings and the pins to avoid (GPIO 33-37 are taken by the
Octal PSRAM; 0, 3, 45, 46 are strapping pins) are in [PINOUT.md](PINOUT.md).
The board-level GPIO matrix is in [ESP32_S3_Pinout](ESP32_S3_Pinout).

Do not add a 120-ohm terminator to the transceiver unless it is a bus
endpoint; measure the bike's own termination first.

## What it does today

**CAN and OBD**
- Non-blocking OBD-II poller (`main/obd_poller.c`). It reads the ECU's
  supported-PID bitmaps, skips PIDs the ECU does not answer and never waits on
  a dead request, so the display and BLE keep running if the bus goes quiet.
- On this bike 23 Mode 01 PIDs answer from `0x7E8`. PIDs `04`, `0D`, `21`,
  `2F` and `4D` answer but always return the same value, so road speed (`0D`)
  is not usable from OBD.
- CAN bus-off recovery (`main/can_bus_supervisor.c`,
  `main/can_bus_recovery_fsm.c`): a supervisor task detects bus-off and
  recovers with back-off instead of hanging the driver.

**Dashboard (ST7789)**
- RPM bar from 0 to 10500 with a flashing shift warning at 10000 and above.
- Cylinder head temperature colour ladder: blue (cold), green, orange, red,
  and a flashing **STOP ENGINE** alert at 165 C. It needs the reading to hold
  for a second and clears with hysteresis, so one bad sample cannot trigger it.
- Telemetry rows for throttle, spark advance, fuel trims, O2, MAP, baro,
  intake and ambient temperature, battery voltage and more.
- All thresholds are set in `idf.py menuconfig` under **Dashboard thresholds**.

**Fuel consumption estimate**
- Speed-density model from MAP, intake air temperature and RPM, corrected by
  the averaged narrowband O2 voltage (`main/fuel_estimator.c`).
- Shows L/h when stopped or idling, L/100km when a road speed is available,
  and `FUEL CUT` during engine braking. The average stays in L/h until 500 m
  have been covered.
- Engine geometry (57.3 x 48.4 mm single cylinder), volumetric efficiency,
  AFR, fuel density and the fuel-cut thresholds are in `idf.py menuconfig`
  under **Fuel injection estimate**. Volumetric efficiency is the calibration
  factor: if the reading is 10 % high against a fill-to-fill check, lower it
  by 10 %.
- Since the ECU has no usable speed PID, L/100km needs an external speed
  source (see future plans).

**BLE ELM327 emulator**
- Advertises as `OBDII` with an ELM327-style GATT profile (service `FFF0`,
  notify `FFF1`, write `FFF2`) plus an HM-10 compatible profile (`FFE0` /
  `FFE1`), so apps such as DMD2 or a generic BLE UART terminal can connect.
- Answers the common `AT` commands and forwards Mode 01 requests to the bike.

### In open pull requests

- **Native CAN broadcast** (PR #5): the ECU also broadcasts on its own.
  `0x110` carries RPM (much faster than OBD polling), engine temperature and a
  field that is probably injection time; `0x111` carries battery voltage.
- **Litres used** since start-up and a `NO SPEED` hint on the fuel panel, plus
  a PID discovery test that asks every Mode 01 PID and tallies all CAN traffic
  (PR #5).
- **Boot diagnostics** on the splash screen to track down a black screen at
  key-on (PR #6).

## Build and flash

Requires ESP-IDF 5.x with the ESP32-S3 target. Open the ESP-IDF PowerShell (or
the VS Code ESP-IDF extension) in the project folder:

```
idf.py set-target esp32s3
idf.py menuconfig
idf.py build
idf.py -p COM8 flash monitor
```

The serial monitor runs at 115200 baud. If the extension's flash command
fails, flash directly with `esptool` (the full command is in
[PINOUT.md](PINOUT.md#quick-rebuild-checklist-after-a-rewiring)).

### Host tests

The fuel model and the bus-off recovery state machine have host-side tests
that need only a C compiler:

```
gcc -std=c11 -Wall -I main test/host/test_fuel_estimator.c main/fuel_estimator.c -lm -o fuel_test && ./fuel_test
gcc -std=c11 -Wall -Wextra -I main test/host/test_can_bus_recovery_fsm.c main/can_bus_recovery_fsm.c -o fsm_test && ./fsm_test
```

## Project layout

| Path | Contents |
|---|---|
| `main/main.c` | App start-up, display, BLE ELM327 bridge, task wiring |
| `main/obd_poller.*` | Non-blocking OBD-II PID poller |
| `main/can_bus_twai.*` | TWAI (CAN) driver port |
| `main/can_bus_supervisor.*`, `main/can_bus_recovery_fsm.*` | Bus-off detection and recovery |
| `main/fuel_estimator*` | Speed-density fuel model |
| `main/Kconfig.projbuild` | `menuconfig` options for fuel and dashboard thresholds |
| `test/host/` | Host unit tests |

## Future plans

- **Wheel speed** from the front wheel sensor (N/W wire) through a conditioned
  input, which unlocks L/100km, trip distance and a real speedometer.
- **Two 1.5" 360x360 round displays** driven with LVGL, with several pages of
  screens switched by handlebar buttons.
- **Stock dash signals**: gear position, blinkers, lights and fuel level.
- **IMU** for lean angle and acceleration.
- **GPS** for speed, position and trip logging.
- **Alarm** with movement detection.
- **Independent battery** so the dashboard and alarm keep running with the
  ignition off.
- **Multi-MCU** split across several ESP32-S3 or an ESP32-P4 as features grow.
