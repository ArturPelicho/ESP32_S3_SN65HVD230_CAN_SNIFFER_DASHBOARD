# Changelog

What changed in the NavExpress phone app, newest first. Every build is published as its own
release (`app-v0.1.N`, file `navexpress-v0.1.N-<commit>.apk`), and the app lists the
changes in a short window after each update. Bike unit firmware changes are in the
[commit history](../../commits/main/main).

Versions not listed are build fixes or small steps inside the ranges below.

## 2026-10-22 · v0.1.118 – v0.1.168

**One set of controls everywhere** (v0.1.168)
- Every option looks like the Layers ones: a frame with ✓, filled when on. A setting with
  several values opens a list to pick from; nothing cycles on a tap.
- Grouped panels (Map settings, Routes, Diagnostics, Dashboard settings) show tabs on top
  inside Settings and a menu on the left when opened from the map. Menus keep their scroll
  position while options change.
- Detail by zoom in words: Very close, Close, Middle, Far, Very far.
- On a new install the button bar starts on the left.

**New app id and backup** (v0.1.162 – v0.1.166)
- Settings → About → Backup saves settings, tracks, places and lines to one file and loads
  it back, on the same phone or a new one. The file format is versioned, so newer app
  versions keep reading older files.
- The app id is now `app.navexpress`; it installs as a new app (load the backup there).

**Feedback and what's new** (v0.1.152 – v0.1.158)
- Diagnostics moved into Settings as a section, with the fault codes inside.
- Map settings in groups: On screen, View, Look, Detail, Sound, Regions. The search button
  took the place of the old ⚙.
- Send a suggestion or report a problem from Settings → About; after a crash the app
  offers to send the report next time it opens (only when you tap).
- A short "what's new" window after each update; one tap closes it.
- Main roads down to tertiary show from zoom 5 (needs the October 22 map build).

**Search, weather, detail** (v0.1.138 – v0.1.150)
- Address and place search, with favourite places first.
- Weather: wind, temperature, rain radar and UV layers, and the rain forecast for the time
  you reach each point, drawn along the progress bar.
- Detail by zoom for each road class, rivers, water names, street names and road numbers.
- Pacenotes in rally rhythm, with an adjustable lead time.
- Hide the followed track's line while navigation carries on.

**Rally pacenotes and a smoother ride** (v0.1.118 – v0.1.134)
- Track mode reads the track like a co-driver: corners graded 1–6, hairpins, tightens,
  opens, crests, dips, linked notes and the distance to the next one.
- Section times: Fast or Slow against your best on tracks you have ridden before.
- Road names in turn instructions ("Turn left onto N4"), read from the offline map.
- The arrow and camera glide between GPS fixes.
- Layer options by long press: road classes and dirt kinds one by one.
- Update check at start, calls in one row away from the navigation boxes.

## 2026-10-21 · v0.1.77 – v0.1.116

- Nav mode reroutes from where you are to the next waypoint or the end.
- Track box menu by long press: mode, route type, avoids, recalculate, stop navigation.
- "Don't leave the main road for small gains" in route calculation.
- Dirt roads and tracks coloured by difficulty.
- Ride recording to a track with times, optional start and stop with the bike.
- Favourite places and "Go there".
- Dead reckoning in tunnels for up to 3 minutes; impossible GPS jumps ignored.
- Arrival times, and Fun routes that look for dirt within the detour you allow.
- Avoid toggles per route type, recalculation when they change.
- Voice and warning volumes, mute, clearer route lines and turn banners.
- Distance measuring and direct lines.
- Units: km or miles, °C or °F, L/100 km, km/L or mpg.
- Trips A and B and an odometer.
- Height profile of the ride so far when nothing is followed.

## 2026-10-20 · v0.1.28 – v0.1.75

- Plan routes on the map with numbered waypoints; line menu to insert points, change
  colour and width.
- Searchable map list, 3D buildings, shaded relief.
- Languages: English, Portuguese, Spanish, French, Italian, German.
- Themes: Sand by day, warm desert at night, Graphite and OLED black, switching by itself.
- Your own screens: elements you pick, place on a slot grid and resize.
- North and lean gauges, 10 m contours, auto zoom ranges, in-app updates.
- Progress bar with the height profile, reverse direction, Go there.
- Calls and messages on the riding screen.
- Nav and Track modes.
- Offline routing with BRouter, routing data per region.
- Route types: Fastest, Economical, Fun; avoid motorways, tolls, dirt, towns.
- Turn by turn on screen and by voice.

## 2026-10-19 · v0.1.9 – v0.1.26

- Offline map tab with downloadable regions.
- Heading-up, 3D and north-up views, auto zoom and tilt, compass when stopped.
- Speed limits and speed cameras, GPS details, interface size.
- Tracks: import GPX, KML, KMZ, GeoJSON, TCX and FIT, manage, draw and follow.
- The app takes the name NavExpress, with a new icon.

## 2026-10-14 · v0.1.7

- First app: phone GPS sent to the bike unit as a speed source, bike dashboard, gear
  learning, tank and range, fault codes read and cleared, units and screen orientation.
