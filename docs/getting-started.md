# Getting started

From nothing to your first ride with NavExpress in about ten minutes. These steps use
plain Android names; phone makers sometimes word a menu differently, but the same option is
always there.

1. [What you need](#what-you-need)
2. [Install the app](#install-the-app)
3. [Allow what it asks for](#allow-what-it-asks-for)
4. [Keep it running with the screen off](#keep-it-running-with-the-screen-off)
5. [Download your maps](#download-your-maps)
6. [Ride](#ride)
7. [Connect the bike unit (optional)](#connect-the-bike-unit-optional)
8. [Updates, backups and a new phone](#updates-backups-and-a-new-phone)

## What you need

- An Android phone with **Android 8.0 or newer**, ideally on a handlebar mount with charging:
  navigation keeps the screen and GPS on.
- **Wi-Fi** and some free space for the maps. A small region such as Madeira is under 20 MB;
  a country such as Portugal is about 620 MB, plus about 300 MB of extras (contours, shaded
  relief, speed limits) and about 125 MB if you also want offline routing.
- Optionally, the **NavExpress Brain** unit on the bike. Everything on the map works without it.

## Install the app

NavExpress is published here on GitHub rather than in an app store, so Android installs it as
a file you download.

1. On the phone, open the [latest release](../../../releases/latest) and tap the file
   `navexpress-v0.1.N-xxxxxxx.apk` (the name always carries the version).
2. When the download finishes, open it. Android asks whether the app you used to open it (your
   browser or file manager) may **install unknown apps**: allow it for that app only. You can
   switch it off again afterwards.
3. Tap **Install**. Google Play Protect may say it does not know the app: choose
   **More details → Install anyway**. If the installer seems stuck, restart the phone and open
   the file again from the Files app.

Each new version is signed with the same key, so it installs over the previous one and
keeps your settings.

## Allow what it asks for

The app asks for each permission when the feature that needs it is first used. None is needed
to look at the map.

| Permission | What it is for | When |
|---|---|---|
| **Location**, precise | Your position on the map, navigation, speed | First start |
| Location **all the time** | Recording a ride and following the route with the screen off; starting by itself when the bike unit connects | Settings → Phone → *Allow always* |
| **Nearby devices** (Bluetooth) | Connecting to the bike unit | First start |
| **Notifications** | The ride recording and bike link notice; the update download | First start |
| Phone, call log, contacts | Showing who is calling, answer and reject buttons | Settings → Phone, only if you turn calls on |
| Notification access | Showing and reading out messages | Settings → Phone, only if you turn messages on |

Nothing leaves the phone except map, routing, search, weather and update requests, and a
suggestion or crash report when you choose to send one.

## Keep it running with the screen off

Android saves battery by pausing apps it thinks you are not using. For navigation and ride
recording NavExpress has to keep going in your pocket or with the screen off.

1. Open **Settings → Phone** in NavExpress. If *Battery optimisation* says **on**, tap
   **Turn off optimisation** and confirm.
2. In the same place, make sure *Location "always"* says **yes**.
3. Some phones add their own battery manager on top of Android. If the app still stops in
   the background, open the phone's **Settings → Apps → NavExpress → Battery** and choose
   **Unrestricted** (or **Don't optimise**), and turn on any **Auto-start** or **Allow
   background activity** switch you find there. [dontkillmyapp.com](https://dontkillmyapp.com)
   lists where each maker hides it.

## Download your maps

1. On the map, tap **☰** (Map settings) and open **Regions**. The same list is in
   Settings → Maps.
2. Type the country or region in the search box and tap **Download** (Wi-Fi recommended).
   The size shown includes the extras that come with the map: contour lines, shaded relief,
   and speed limits, speed cameras and dirt roads by difficulty.
3. For road routes without signal, also download the region's **offline routing** (about
   125 MB). Without it, routes come from an online server while there is a connection.
4. Maps are rebuilt every month from fresh OpenStreetMap data. **Check for updates** in the
   same list tells you when a newer build is out.

See [Download maps for a trip](how-to/download-maps.md) for more.

## Ride

- Open the app on the **Map** tab. The arrow follows you, the map turns with you and zooms
  out as you speed up.
- To go somewhere, tap **🔍**, type an address or place and tap **Go there**, or long press
  the map where you want to go.
- To ride a route someone sent you, open the GPX (or KML, KMZ, GeoJSON, TCX, FIT) file with
  NavExpress and tap **Follow**.

The [how-to guides](how-to/README.md) walk through each of these.

## Connect the bike unit (optional)

With the NavExpress Brain fitted, turn the ignition on with the app open. The app finds the
unit by itself (it advertises as `OBDII`), remembers it and from then on connects every
time the bike is switched on. **Settings → Bike** shows the link and lets you pick another
unit. See [Connect the bike unit](how-to/connect-the-bike-unit.md).

## Updates, backups and a new phone

- **Updates**: every time the app starts it checks for a newer version and offers **Update**.
  Settings → About also has a button to check by hand.
- **What changed**: after each update a short window lists the changes; one tap closes it.
  The full list is in the [changelog](../CHANGELOG.md).
- **Backup**: Settings → About → Backup → **Save to a file** writes your settings, tracks, places and
  lines to one file. **Load from a file** on a new phone (or after reinstalling) brings it all back.
  Maps are not in the file: download them again on the new phone.
