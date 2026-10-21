# NavExpress

<img src="docs/img/navexpress-icon.svg" width="96" align="right" alt="NavExpress icon">

A motorcycle navigator and smart dashboard, in two parts that work alone or together:

- **NavExpress Mobile** (`android/`), the phone app, shown on the phone as **NavExpress**. It has offline maps with relief, planned and imported tracks, and a dashboard you lay out yourself.
- **NavExpress Brain** (`main/`), the boards on the bike: ESP32-S3 firmware that reads the bike's CAN bus and works out fuel use, range and gear. It sends all of it to the phone over BLE.

The name nods to the Espressif ESP32 at the heart of the bike unit. The first bike is a **ZongShen Carrera 125 Euro5+** (ZS125GY-13-E55); sensors, displays and the MCU are meant to be swappable, and the app is designed to work in any country.

## Get it

| What | Link |
|---|---|
| Phone app, newest APK (the file name carries the version; the app also updates itself from Definições → Sobre) | [Latest release](../../releases/latest) |
| Offline maps: Portugal, Madeira, Açores and Spain, with contours every 10 m, shaded relief, speed limits and cameras | [Releases "maps-…"](../../releases?q=maps&expanded=true), downloaded from inside the app (Mapa → ☰) |
| Every app and map build | [All releases](../../releases) |
| How to install and use the app (Portuguese) | [docs/app-telemovel.md](docs/app-telemovel.md) |
| Sample track to try the track screens | [samples/gpx/teste-lisboa-marginal.gpx](samples/gpx/teste-lisboa-marginal.gpx) |
| Wiring and pins of the bike unit | [PINOUT.md](PINOUT.md) |
| PID scan procedure (Portuguese) | [docs/pid-scan-protocol.md](docs/pid-scan-protocol.md) |
| Work in progress | [Pull requests](../../pulls) · [CI runs](../../actions) |

## NavExpress Mobile at a glance

- **Map** (MapLibre, fully offline once downloaded): road colours by class, local or Portuguese names, 3D buildings, shaded relief and contour lines. Contour spacing follows the zoom (100 m far out, down to 10 m close in) or can be fixed. Follows the bike heading-up, in 3D or north-up. Auto zoom works from your own speed and zoom ranges. Shows the speed limit sign and warns of speed cameras with a beep.
- **Tracks**: opens GPX, KML, KMZ, GeoJSON, TCX and FIT. You can also plan a track on the map with numbered waypoints and road routing, or tap **Go there**. Tracks can be reversed, renamed, recoloured, saved or shared as GPX. While you follow one, the bottom strip shows its height profile and how far along you are.
- **Your own screens**: the Painel and the Mapa are built from elements you pick, such as speed, gear, RPM, fuel, range, temperatures, clock, coordinates, a north rose that tilts with the 3D view, and a lean/pitch gauge. Each sits in a slot grid with small, medium or large slots and its own opacity, and you can drag elements between slots. The map's button bar can sit on any edge.
- **Looks**: Sand or Graphite by day; warm desert, Graphite or pure-black OLED by night. It can switch by itself at sunset, from the light sensor or with the phone's dark mode. The interface goes from 85 % to 150 % for reading with a helmet on.
- **Languages**: English, Portuguese, Spanish, French, Italian and German.
- **With the Brain**: a full bike dashboard, fault codes read and cleared, fuel and gear settings, and the phone's GPS as a speed source for the bike.

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

## NavExpress Brain: what it does today

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
- The bike's own CAN broadcast (`main/bike_can.c`): the ECU sends two frames
  about every 9 ms. `0x110` carries RPM (bytes 2-3, RPM x 4), an engine
  temperature, an injection value (bytes 5-6) and status bits. The
  injection value is a multiple of 1000 sent truncated to 16 bits, so it
  wraps every 65536; the last digits of the received value tell how many
  times it wrapped and the true value is rebuilt frame by frame, up to 5
  wraps (39 shown, ~7 L/h, margin for full throttle at the 10500 rpm cut).
  A week of riding: 99.98 % of 1.28 million frames fit, reaching 3 wraps;
  `INJ wrap` serial lines and per-wrap frame counts check this. Shown as raw / 10000 it reads 1.2 at
  idle, 13 at 8400 rpm and 0 on fuel cut, and behaves as a fuel rate, not a
  pulse time (see Fuel consumption). `0x111` carries battery voltage
  (byte 6, 0.1 V per bit). RPM comes from the broadcast while it keeps
  arriving and falls back to polling PID `0C`. A `BIKE` serial line every 2 s
  logs the raw values beside the fuel estimate.
- PID discovery test (`main/pid_scan.c`): send `SCAN` over BLE (or enable it
  at boot in menuconfig) to ask every Mode 01 PID from `00` to `FF`, keep
  watching the ones that answer and tally all other CAN traffic. A report is
  printed every 30 s (`SCAN REPORT` for one now, `SCAN STOP` to end) while the
  dashboard keeps running. Step-by-step procedure (in Portuguese):
  [docs/pid-scan-protocol.md](docs/pid-scan-protocol.md).

**Dashboard (ST7789)**
- RPM bar from 0 to 10500 with a flashing shift warning at 10000 and above.
- Cylinder head temperature colour ladder: blue (cold), green, orange, red,
  and a flashing **STOP ENGINE** alert at 165 C. It needs the reading to hold
  for a second and clears with hysteresis, so one bad sample cannot trigger it.
- Telemetry rows for throttle, spark advance, fuel trims, O2, MAP, baro,
  intake and ambient temperature, battery voltage and more.
- Battery voltage (from `0x111`) next to the cylinder head temperature, and
  the ECU's injection value as a cyan `INJ` field.
- Boot screen: pixel-art bike, firmware version and authors (Artur, Claude),
  held for `DASH_SPLASH_HOLD_MS` (menuconfig, **Boot screen**).
- Boot diagnostics (`main/boot_diag.c`): boot progress is recorded in NVS and
  logged on the serial port at every start, to track down a black screen at
  key-on.
- All thresholds are set in `idf.py menuconfig` under **Dashboard thresholds**.

**Fuel consumption**
- Measured from the ECU's injection value by default (`main/inj_meter.c`):
  fuel flow in L/h = constant x value. The value is not a pulse time on this
  ECU: read as ms it would rise 15x from idle to 8400 rpm while air per
  cycle only doubles, and hold the injector open 92 % of the cycle at half
  throttle. As a fuel rate it implies a smooth VE curve. The constant
  (0.180 L/h per unit, from a week of riding against Artur's usual
  consumption) is in menuconfig and is to be refined from fill-ups.
- A trip counter (integral of the value over time, running time, injections)
  is kept in NVS, survives key-off and is logged as a `TRIP` serial line
  every 2 s. Send `TRIP` over BLE to read it and `TRIP RESET` to zero it. A
  fill-up gives the exact constant, which applies to past trips too:
  constant = litres x 3600 / value_s.
- Calibration from steady riding (`main/steady_cal.c`): stretches of at
  least 10 s with RPM within 5 %, throttle within 2 points and MAP within
  3 kPa, engine warm, battery charging, no fuel cut and the O2 sensor
  switching (closed loop) are summed per 1000 rpm band. Each band gives the
  injection value there, trims, the fuel it stands for and the VE that fuel
  implies at lambda 1: a smooth curve with a plausible top checks the fuel
  model and its constant. Kept in NVS, printed as `CAL` lines at start-up
  and every 5 minutes of new data; `CAL` / `CAL RESET` over BLE.
- Falls back to a speed-density model from MAP, intake air temperature and
  RPM, corrected by the averaged narrowband O2 voltage
  (`main/fuel_estimator.c`), when the broadcast is missing or when chosen in
  menuconfig. O2 readings of exactly 1010 mV are ignored: the ECU sends that
  while the sensor is cold.
- Shows L/h when stopped or idling, L/100km when a road speed is available,
  and `FUEL CUT` during engine braking. The average stays in L/h until 500 m
  have been covered.
- Engine geometry (57.3 x 48.4 mm single cylinder), volumetric efficiency,
  AFR, fuel density and the fuel-cut thresholds are in `idf.py menuconfig`
  under **Fuel injection estimate**. Volumetric efficiency is the calibration
  factor: if the reading is 10 % high against a fill-to-fill check, lower it
  by 10 %.
- Litres used since start-up. The ECU has no usable speed PID, so road
  speed comes from the sources below; with none the panel shows `NO SPEED`.

**Road speed, gears and tank**
- Speed sources kept side by side (`main/speed_source.*`): phone GPS over BLE
  (`GPS_EXT`), wheel sensor, on-board GPS module, gear estimate and OBD. One
  of them feeds the fuel totals (`SPD_SRC`); when it drops out the gear
  estimate stands in, then the average of the last ~20 s is held for up to
  `SPD_HOLD_S` while the engine pulls (tunnels).
- Gear learning (`main/gear_learner.*`): steady riding with a GPS speed fills
  a histogram of km/h per 1000 rpm; each gear is a peak, saved in NVS. Once
  learnt, speed = RPM x ratio at the CAN rate, and a shift is followed by
  picking the gear that keeps the speed continuous (works without GPS). The
  gear shows as `G1`..`G5` under the temperature unit.
- Tank (`main/tank.*`): `FILL FULL <L>`, `FILL ADD <L>` or `TANK SET <L>`;
  level, since-fill average and range, all saved in NVS. Two fills to full
  give a suggested `FUEL_K` from the pump litres.
- Runtime parameters (`main/rt_params.*`, menuconfig **Speed, gears, tank and
  phone link** for the defaults): `PARAM` lists them, `PARAM SET <NAME> <n>`
  changes one and stores it. Other commands: `AVG RESET`, `TANK`, `GEAR`,
  `GEAR RESET`, `SPEED`.

**Link to NavExpress Mobile**
- Sends telemetry to the app and takes the phone's GPS fixes as a speed source. The binary packets are in `main/phone_proto.h`, service `6d6f7461-0001-4b1e-9c3a-5a1e00000000`. The APK is built by the "Android app" GitHub Actions workflow.

**BLE ELM327 emulator**
- Advertises as `OBDII` with an ELM327-style GATT profile (service `FFF0`,
  notify `FFF1`, write `FFF2`) plus an HM-10 compatible profile (`FFE0` /
  `FFE1`), so apps such as DMD2 or a generic BLE UART terminal can connect.
- Answers the common `AT` commands and forwards Mode 01 requests to the bike.

## Build and flash

Built with ESP-IDF 6.0 for the ESP32-S3 target. Open the ESP-IDF PowerShell (or
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

The fuel model, the injector meter, the bus-off recovery state machine, the
PID scan and the `0x110`/`0x111` decoder have host-side tests that need only a C compiler:

```
gcc -std=c11 -Wall -I main test/host/test_fuel_estimator.c main/fuel_estimator.c -lm -o fuel_test && ./fuel_test
gcc -std=c11 -Wall -Wextra -I main test/host/test_can_bus_recovery_fsm.c main/can_bus_recovery_fsm.c -o fsm_test && ./fsm_test
gcc -std=c11 -Wall -I main test/host/test_pid_scan.c main/pid_scan.c -o scan_test && ./scan_test
gcc -std=c11 -Wall -I main test/host/test_bike_can.c main/bike_can.c -o bike_test && ./bike_test
gcc -std=c11 -Wall -I main test/host/test_inj_meter.c main/inj_meter.c -lm -o inj_test && ./inj_test
gcc -std=c11 -Wall -I main test/host/test_steady_cal.c main/steady_cal.c -lm -o cal_test && ./cal_test
gcc -std=c11 -Wall -I main test/host/test_gear_learner.c main/gear_learner.c -lm -o gear_test && ./gear_test
gcc -std=c11 -Wall -I main test/host/test_speed_source.c main/speed_source.c -o speed_test && ./speed_test
gcc -std=c11 -Wall -I main test/host/test_tank.c main/tank.c -o tank_test && ./tank_test
gcc -std=c11 -Wall -I main test/host/test_phone_proto.c main/phone_proto.c -lm -o proto_test && ./proto_test
```

The "Host tests" GitHub Actions workflow runs all of them on every push.
The "Android app" workflow builds and publishes the APK, and "Map data"
rebuilds the offline maps every month from fresh OpenStreetMap data.

## Project layout

| Path | Contents |
|---|---|
| `main/main.c` | App start-up, display, BLE ELM327 bridge, task wiring |
| `main/obd_poller.*` | Non-blocking OBD-II PID poller |
| `main/can_bus_twai.*` | TWAI (CAN) driver port |
| `main/can_bus_supervisor.*`, `main/can_bus_recovery_fsm.*` | Bus-off detection and recovery |
| `main/fuel_estimator*` | Fuel consumption, units and averages; speed-density fallback |
| `main/inj_meter.*` | Fuel from the ECU's injection value, trip sums |
| `main/steady_cal.*` | Fuel model check from steady riding |
| `main/bike_can.*` | Decoder for the ECU's `0x110` / `0x111` broadcast |
| `main/pid_scan*` | PID discovery test |
| `main/boot_diag.*` | Boot progress record (NVS + serial log) |
| `main/speed_source.*` | Road speed sources and the fuel speed selection |
| `main/gear_learner.*` | Gear ratio learning and gear tracking |
| `main/tank.*` | Tank level, range, fill-up calibration |
| `main/phone_proto.*` | BLE packets to and from the phone app |
| `main/rt_params.*` | Runtime parameters in NVS |
| `android/` | Phone app (Kotlin, Jetpack Compose, MapLibre) |
| `maps/` | Offline map builder for the "Map data" workflow (regions, contours, shaded relief, speed limits) |
| `samples/` | Test files (GPX track) |
| `docs/` | Test procedures |
| `main/Kconfig.projbuild` | `menuconfig` options for fuel and dashboard thresholds |
| `test/host/` | Host unit tests |

## Future plans

- **Wheel speed** from the front wheel sensor (N/W wire) through a conditioned
  input, which unlocks L/100km, trip distance and a real speedometer.
- **Two 1.5" 360x360 round displays** driven with LVGL, with several pages of
  screens switched by handlebar buttons.
- **Stock dash signals**: gear position, blinkers, lights and fuel level.
- **IMU** on the bike for lean angle and acceleration (the app already shows lean from the phone).
- **GPS** for speed, position and trip logging.
- **Alarm** with movement detection.
- **Independent battery** so the dashboard and alarm keep running with the
  ignition off.
- **Multi-MCU** split across several ESP32-S3 or an ESP32-P4 as features grow.
