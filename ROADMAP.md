# Roadmap

Where NavExpress is going, and how far it has got. Updated with every step; what already
shipped, version by version, is in the [changelog](CHANGELOG.md).

**Legend:** ✅ shipped · 🛠️ in progress · 🧭 next · 💡 later · 🏍️ needs a road test

## Now

| | What | Notes |
|---|---|---|
| 🛠️ | **Dashboard you lay out yourself** | Rows you choose, sizes, and a split screen with the map beside the dashboard |
| 🏍️ | **Road test of the October releases** | Pacenotes and speed camera warnings are ridden and confirmed. Still to confirm on the road: auto zoom ranges, progress bar, tilt, speed limit sign, rerouting in Nav mode, section times, tunnels |
| 🛠️ | **Documentation** | Getting started, how-to guides with real screens of the app, this roadmap |

## Next

| | What | Notes |
|---|---|---|
| 🧭 | **Ride data** | Ride statistics after each ride: distance, moving time, average and top speed, climb, fuel, lean |
| 🧭 | **Fuel stops along the route** | Stations ahead on the route, and a warning when the range will not reach the next one |
| 🧭 | **Your own alerts** | Alerts at points you set on a route, and your own speed limits per road class |
| 🧭 | **Music** | Play, pause and skip on the riding screen, with big buttons |
| 🧭 | **Crash detection and SOS** | A hard fall followed by stillness sends your position to a contact unless you cancel |
| 🧭 | **Anti-theft** | Movement alert while parked, from the bike unit |

## Later

| | What | Notes |
|---|---|---|
| 💡 | **Curvy-road routing** | A route profile that seeks bends from the road geometry, not only from road class |
| 💡 | **Live traffic** | Needs a traffic data key of your own (TomTom or HERE) |
| 💡 | **More languages** | Countries with a strong touring culture |
| 💡 | **Wider phone support** | Testing across more phones and Android versions |

## NavExpress Brain (bike unit)

| | What | Notes |
|---|---|---|
| ✅ | CAN and OBD-II reading, bus-off recovery | Non-blocking, the screen and Bluetooth keep running if the bus goes quiet |
| ✅ | Fuel flow, trip, range and tank | From the ECU's own broadcast; refined at each fill-up |
| ✅ | Gear learning, speed sources side by side | Phone GPS as the speed source for now |
| ✅ | Fault codes read and cleared from the app | |
| 🧭 | Power-up reliability | A supervisor on the reset line so the screen never stays black at key-on |
| 🧭 | **Wheel speed sensor** | The front wheel sensor through a conditioned input: real speed and distance without GPS |
| 🧭 | **Stock dash signals** | Gear position, indicators, lights and the fuel level sender |
| 💡 | **Two round 1.5" screens** | LVGL pages switched by handlebar buttons |
| 💡 | **IMU and GPS module on the bike** | Lean angle and acceleration; position without the phone |
| 💡 | **Alarm with its own battery** | Movement detection and a mobile data link, running with the ignition off |
| 💡 | **More MCUs** | Several ESP32-S3 or an ESP32-P4 as features grow |

## Shipped highlights

| Area | |
|---|---|
| ✅ Maps | Any country, cut from the world map on the phone · monthly OpenStreetMap updates · contours and shaded relief · 3D buildings · speed limits and cameras · dirt roads by difficulty · detail by zoom · weather layers |
| ✅ Navigation | Offline routing · Fastest, Economical, Fun with avoids · turn by turn with voice and road names · Nav and Track modes with rerouting · arrival times · height profile · rain forecast along the route |
| ✅ Track riding | Rally pacenotes from the track's shape · section times against your best · import GPX, KML, KMZ, GeoJSON, TCX, FIT · plan on the map |
| ✅ On the ride | Smooth arrow between fixes · dead reckoning in tunnels · calls and messages with glove-sized buttons · ride recording · favourite places · measuring and direct lines |
| ✅ Your app | Screens built from elements · themes that follow the sun · six languages · units for any country · backup to a file · in-app updates · suggestions and crash reports |

## Have an idea?

Open the app → Settings → About → **Send a suggestion**, or
[open an issue](../../issues/new) on GitHub.
