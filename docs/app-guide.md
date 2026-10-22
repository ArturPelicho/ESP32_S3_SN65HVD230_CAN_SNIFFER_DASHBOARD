# NavExpress Mobile: user guide

How to set up and use every part of the phone app. The app speaks English, Portuguese,
Spanish, French, Italian and German (Settings → Preferences → Language); this guide uses
the English names.

- [Install and update](#install-and-update)
- [The four tabs](#the-four-tabs)
- [Offline maps](#offline-maps)
- [The map screen](#the-map-screen)
- [Layers](#layers)
- [Tracks, routes and places](#tracks-routes-and-places)
- [Navigating](#navigating)
- [Track mode: pacenotes and section times](#track-mode-pacenotes-and-section-times)
- [Recording a ride](#recording-a-ride)
- [Measuring and direct lines](#measuring-and-direct-lines)
- [Your own screens (elements)](#your-own-screens-elements)
- [Units, trips and odometer](#units-trips-and-odometer)
- [Calls and messages](#calls-and-messages)
- [Theme and looks](#theme-and-looks)
- [With the bike unit (NavExpress Brain)](#with-the-bike-unit-navexpress-brain)

## Install and update

1. Download the newest APK from [the latest release](../../../releases/latest). The file name
   always carries the version, for example `navexpress-v0.1.135-abc1234.apk`.
2. Open it on the phone and allow installing from that app when Android asks. If Play Protect
   hangs on "Installing…", restart the phone and install from the Files app.
3. Updates: every time the app starts it checks GitHub for a newer build and offers
   **Update** or **Dismiss**. You can also check by hand in Settings → About.

The Android status bar and navigation buttons always stay visible.

## The four tabs

| Tab | What it is |
|---|---|
| **Map** | Offline map, navigation, tracks, and any elements you place on it |
| **Dashboard** | The bike's dashboard, built from elements (🚧 still being worked on, see below) |
| **Diagnostics** | Live data from the bike unit, fault codes (read and clear), speed sources side by side |
| **Settings** | General settings on the left (Preferences, Maps, Tracks, Bike, Phone, About), each opening in the panel on the right |

Settings that belong to one screen live on that screen: tap the ⚙ button in its corner.

> **Dashboard tab: under construction.** It works and shows live data when the bike unit is
> connected, but configurable rows, a map/dashboard split screen and ride statistics are
> still to come, and its layout may change between versions.

## Offline maps

Settings → **Maps** (or the Maps button on the map).

- **Regions**: search the list and download a country or region over Wi-Fi. Each region can
  add extras: **relief** (contour lines), **shading** (shaded relief), **limits** (speed limits,
  speed cameras, dirt roads by difficulty) and **offline routing** (BRouter segments, about
  125 MB per region, so routes work without network).
- **Updates**: "Check for updates" compares with the newest map build; maps are rebuilt every
  month from fresh OpenStreetMap data.
- **Map view**: follow mode (heading up, 3D, north up), auto zoom by speed with your own
  speed and zoom ranges, day/night map theme, names in the app's language or as written
  locally, the corner for the navigation boxes, the speed camera beep, contour spacing.
- **Detail by zoom**: a slider per road class, rivers and streams, water names, street names and
  road numbers for the zoom they start at. The map files hold some classes only from a certain
  zoom (secondary roads about 9, streets about 12), so lower than that shows nothing more.

Map data © OpenStreetMap contributors, base map Protomaps, relief Copernicus DEM.

## The map screen

- The arrow moves smoothly between GPS fixes: it is predicted along the road or track ahead
  and eased back onto each new fix.
- **Buttons** (the bar can sit on any edge; hide the ones you don't use in the map's ⚙):
  view mode (▲ / 3D / N), follow, zoom, GPS details, day/night, layers, tracks, record, maps.
- **Speed limit sign** from OpenStreetMap, red when you are over it; **speed camera** warning
  with a beep.
- **Search** (🔍): type an address or a place; your favourite places come first, then results
  from OpenStreetMap (needs internet). Pick one to see it pinned, then **Go there** for a route
  from where you are, or save it as a favourite.
- **Long press on the map**: start a route or add a waypoint, go there, save a favourite place,
  measure from here, draw a direct line.
- In tunnels the position carries on by dead reckoning for up to 3 minutes.

## Layers

Tap the layers button. Tap a layer to switch it on or off; **long press** a layer marked ⋯ for
its options:

| Layer | Long press options |
|---|---|
| Tracks on the map | Hide the followed track's line and points (navigation carries on); the GPX button does the same with a tap |
| Shaded relief | Strength slider (10–100 %) |
| Contour lines | Spacing (none, 10, 20, 50, 100 m) for the zoom level you are at; each zoom keeps its own, "Back to the general setting" clears them |
| Roads | Each road class on or off: motorways, expressways, main, secondary, local, streets and minor roads, service roads |
| Dirt roads by difficulty | Each kind on or off: easy (green), medium (yellow), hard (red), not graded (brown), private or closed (black, gates may be shut) |
| 3D buildings | Shown in the 3D view from zoom 15 |
| Speed cameras | Icons with the limit |
| Weather (needs internet, off by default) | Wind arrows (coloured by strength, with gusts), temperature, rain radar and forecast, UV, each on or off. The last forecast is kept for when there is no signal |

## Tracks, routes and places

Settings → **Tracks (GPX)**, or a long press on the GPX button on the map (a tap shows or hides the
followed track; with nothing followed it opens the list too). Throughout the app a tap does the main
thing and a long press opens the options.

- **Import**: GPX, KML, KMZ, GeoJSON, TCX and FIT (or "Open with" NavExpress from another app).
- **Plan on the map**: long press → start a new route here, then long press elsewhere to add
  numbered waypoints. Legs follow the roads (offline routing, or online BRouter when there is
  network). Long press a waypoint to move or delete it; long press the line to rename, save,
  share as GPX or delete.
- **Per track**: colour, width, rename, duplicate, reverse, follow, share, delete.
- **Favourite places**: long press → save place; "Go there" from the place or the list.

## Navigating

Follow a track, or long press → **Go there**. The track box (in the corner you chose) shows
distance left, arrival time at the end and, for planned routes, at the next waypoint. **Long
press the track box** for its menu:

- Stop navigation, voice on or off, arrival time at the next waypoint, arrival time on the
  progress bar, pacenotes and section times (see below).
- **Mode**: *Nav* (off the line it works out a road route from where you are to the next
  waypoint or the end, and the original fades) or *Track* (the line is the line; off it, a
  thin dashed guide to its nearest point).
- **Route type ›** opens its own page: Fastest, Economical or Fun (seeks dirt within a detour
  you set), each with its own toggles (avoid motorways, tolls, dirt, towns; don't leave the
  main road for small gains). Changing any of them recalculates the route.

**Turn by turn**: a banner with the next turn and the road taken ("Turn left onto N4"), read
from the offline map, plus voice. Volumes for voice and warnings are in the map's ⚙.

**Progress bar**: the height profile of the track ahead (or of the ride so far when nothing
is followed). Under it: ▲ highest and ▼ lowest point, ↗ total climb and ↘ total descent,
where you are, and the arrival time at the end. With internet now and then, a blue lane along
its top shows the rain forecast for the time you reach each place (deeper blue for heavier rain),
an outline where it rains now but should have stopped by then, and the first rain ahead with
its distance and time ("🌧 42 km · 14:40"). Switch it off in the track box menu.

## Track mode: pacenotes and section times

In Track mode the app reads the track's shape like a rally co-driver instead of giving turn
instructions:

- Each corner is graded 1 (tightest) to 6 (almost straight) from its radius, plus hairpins,
  *long*, *tightens*, *opens*, and crests and dips from the terrain heights.
- Notes closer than 30 m are linked into one call ("into", "with a counter-curve"), and each
  call ends with the straight to the next note, rounded the rally way (30, 50, 70, 100, 150…).
- Choose words ("long, sharp right, tightens") or rally numbers ("long right 3 tightens") in
  the track box menu.

**Section times**: every track is cut into sections of about a kilometre, ending after a
corner. At the end of each one the app says **Fast** or **Slow** against your best time there
and shows the difference (−1.4 s). Bests are kept per track and direction and always updated;
a ride recorded with the app starts with its own times as the first bests.

## Recording a ride

The record button saves the ride as a track with times, also with the screen off. It can start
by itself when the bike unit connects and stop 15 minutes after the bike goes off. Share it
as GPX like any other track.

## Measuring and direct lines

- **Measure**: long press → measure distance from here. An orange cross sits in the middle of
  the screen; slide the map and long press to add points. The bar shows the total; undo or close.
- **Direct line**: long press → direct line to here. A magenta line from the bike with the
  distance and bearing, kept until you remove it (long press its end).

## Your own screens (elements)

The Map and Dashboard tabs are built from elements: speed (with the limit sign), gear, RPM,
rev counter, shift lights, fuel tank, range, head temperature, consumption now and average,
clock, bike and GPS link, battery, intake air, throttle, trips A/B and the bike's trip,
odometer, coordinates, altitude, engine faults, north rose (leans with the 3D view),
lean/pitch gauge (long press to zero it) and, on the map, the progress bar.

Open the screen's ⚙:

- **Shown on this screen**: pick the elements. Landscape and portrait each keep their own.
- **Slot size** (small, medium, large) and **background opacity**.
- **Move mode**: drag an element onto another slot; dropping it on another element swaps them.
  Save or revert on screen.
- On the map: the button bar's edge and which buttons show.

Every one-slot element has the same look: a small label and the value as big as the box allows.

## Units, trips and odometer

- **Units** (Settings → Preferences): km/h or mph, °C or °F, L/100 km, km/L, mpg (UK or US).
  The defaults follow the phone's country. Diagnostics and the bike unit stay metric.
- **Trips A and B** and the **odometer** (Dashboard ⚙): distance, moving time, average and top
  speed, fuel used. They count from the bike unit when connected, otherwise from the phone's
  GPS. Reset each trip there, or set the odometer to your bike's reading.

## Calls and messages

Settings → Phone. Incoming calls show as one row with big Answer / Reject buttons (made for
gloves); once answered, a slim bar with the time and Hang up. Messages (SMS, WhatsApp,
Telegram, Signal, Messenger, Google Messages…) show for a few seconds and can be read aloud.
Both appear away from the navigation boxes so the next turn stays in view. The app asks for
the permissions it needs there.

## Theme and looks

Settings → Preferences → Theme: Sand or Graphite by day; warm desert, Graphite or pure-black
OLED by night; switch by hand, at sunset, from the light sensor or with the phone's dark mode.
The map can follow the theme. The interface size goes from 85 % to 150 % for reading with a
helmet on.

## With the bike unit (NavExpress Brain)

When the ESP32-S3 unit on the bike is connected over Bluetooth the app also shows:

- the full bike dashboard (RPM, gear, head temperature with a STOP ENGINE warning, fuel flow,
  range, battery…);
- **Diagnostics**: every value the bike sends, all speed sources side by side, the learnt gears;
- **fault codes**, read and cleared;
- **Bike settings** (Dashboard ⚙, and Settings → Bike for the connection and the unit's
  parameters): speed source for consumption, tank, fill-ups ("Fill to full" with the pump
  litres), fuel constant, range baseline, gear learning.

The phone's GPS is sent to the bike as a speed source while no wheel sensor is fitted.
