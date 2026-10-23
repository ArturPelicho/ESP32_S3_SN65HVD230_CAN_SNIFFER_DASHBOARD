#!/usr/bin/env python3
"""Takes the README and guide screenshots of the phone app on an Android emulator.

Run by the "Screenshots" workflow once the emulator is up. It installs the APK, puts the
Madeira map and a demo route straight into the app's storage, rides the route with a fake
GPS (NMEA sentences into the emulator) and walks the screens through adb, saving a PNG of each.

    run.py --apk app.apk --maps DIR --route route.gpx --out shots/
"""
import argparse
import math
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

PKG = "app.navexpress"
ACT = PKG + "/.MainActivity"


def adb(*args, check=True, inp=None, text=True, timeout=120):
    r = subprocess.run(["adb", *args], input=inp, capture_output=True, text=text, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")
    return r.stdout


def sh(cmd, check=False):
    return adb("shell", cmd, check=check)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------- the route ----------

def read_gpx(path):
    txt = Path(path).read_text()
    pts = []
    for m in re.finditer(r'<trkpt[^>]*lat="([-\d.]+)"[^>]*lon="([-\d.]+)"[^>]*>(.*?)</trkpt>', txt, re.S):
        ele = re.search(r"<ele>([-\d.]+)</ele>", m.group(3))
        pts.append((float(m.group(1)), float(m.group(2)), float(ele.group(1)) if ele else None))
    if not pts:  # lon before lat
        for m in re.finditer(r'<trkpt[^>]*lon="([-\d.]+)"[^>]*lat="([-\d.]+)"[^>]*>(.*?)</trkpt>', txt, re.S):
            ele = re.search(r"<ele>([-\d.]+)</ele>", m.group(3))
            pts.append((float(m.group(2)), float(m.group(1)), float(ele.group(1)) if ele else None))
    return pts


def dist(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((a[0] - b[0]) * 110540, (a[1] - b[1]) * 111320 * k)


def bearing(a, b):
    y = math.sin(math.radians(b[1] - a[1])) * math.cos(math.radians(b[0]))
    x = math.cos(math.radians(a[0])) * math.sin(math.radians(b[0])) - \
        math.sin(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.cos(math.radians(b[1] - a[1]))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def along(pts):
    out = [0.0]
    for a, b in zip(pts, pts[1:]):
        out.append(out[-1] + dist(a, b))
    return out


def at(pts, cum, s):
    """Point, bearing and height at s metres along the route."""
    s = max(0.0, min(s, cum[-1] - 0.1))
    i = max(0, next(j for j in range(1, len(cum)) if cum[j] >= s) - 1)
    a, b = pts[i], pts[i + 1]
    f = (s - cum[i]) / max(cum[i + 1] - cum[i], 1e-6)
    lat = a[0] + (b[0] - a[0]) * f
    lon = a[1] + (b[1] - a[1]) * f
    ele = (a[2] or 0) + ((b[2] or 0) - (a[2] or 0)) * f
    # Heading over the next 15 m, so the arrow does not twitch on short segments.
    j = i + 1
    while j < len(pts) - 1 and cum[j] - s < 15:
        j += 1
    return lat, lon, ele, bearing((lat, lon), pts[j][:2])


# ---------- fake GPS ----------

def nmea(body):
    c = 0
    for ch in body:
        c ^= ord(ch)
    return f"${body}*{c:02X}"


def dm(v, lat):
    a = abs(v)
    d = int(a)
    m = (a - d) * 60
    return (f"{d:02d}{m:07.4f}" if lat else f"{d:03d}{m:07.4f}"), ("N" if v >= 0 else "S") if lat else ("E" if v >= 0 else "W")


GEO_ARGS = None


def send_fix(lat, lon, ele, kmh, brg):
    """A fix into the emulator's GPS: position, height, satellites, speed (knots) and, where
    the emulator takes it, heading."""
    global GEO_ARGS
    kn = f"{kmh / 1.852:.1f}"
    base = ["emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", f"{ele:.1f}", "10", kn]
    if GEO_ARGS is None:  # find out once what this emulator accepts
        for extra in ([f"{brg:.1f}"], []):
            r = subprocess.run(["adb", *base, *extra], capture_output=True, text=True)
            if r.returncode == 0 and "KO" not in r.stdout + r.stderr:
                GEO_ARGS = len(extra)
                log(f"geo fix with {'heading' if extra else 'no heading'}: {r.stdout.strip()}")
                return
        GEO_ARGS = 0
        return
    adb(*base, *([f"{brg:.1f}"] if GEO_ARGS else []), check=False)


class Rider(threading.Thread):
    """Rides the route at a steady speed, one fix a second, while running is set."""

    def __init__(self, pts, kmh):
        super().__init__(daemon=True)
        self.pts, self.cum, self.kmh = pts, along(pts), kmh
        self.s = 0.0
        self.running = threading.Event()

    def run(self):
        while True:
            self.running.wait()
            lat, lon, ele, brg = at(self.pts, self.cum, self.s)
            send_fix(lat, lon, ele, self.kmh, brg)
            self.s = min(self.s + self.kmh / 3.6, self.cum[-1] - 1)
            time.sleep(1)

    def ride(self, seconds):
        self.running.set()
        time.sleep(seconds)

    def jump(self, s):
        self.s = s

    def hold(self):
        """Stops sending fixes (the screen goes still, so the UI tree can be read)."""
        self.running.clear()


# ---------- the app's storage ----------

def xml_prefs(values):
    out = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for k, v in values.items():
        if isinstance(v, bool):
            out.append(f'    <boolean name="{k}" value="{str(v).lower()}" />')
        elif isinstance(v, int):
            out.append(f'    <int name="{k}" value="{v}" />')
        elif isinstance(v, (set, list)):
            out.append(f'    <set name="{k}">' + "".join(f"<string>{s}</string>" for s in v) + "</set>")
        else:
            out.append(f'    <string name="{k}">{v}</string>')
    out.append("</map>")
    return "\n".join(out) + "\n"


def put_private(rel, data):
    """Writes a file inside the app's private storage (debug build: run-as)."""
    d = str(Path(rel).parent)
    adb("shell", f"run-as {PKG} mkdir -p {d}")
    r = subprocess.run(["adb", "exec-in", f"run-as {PKG} sh -c 'cat > {rel}'"], input=data.encode(), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode())


def prefs(name, values):
    put_private(f"shared_prefs/{name}.xml", xml_prefs(values))


def set_state(theme="DAY", track_mode="NAV", follow="THREE_D", orientation="LANDSCAPE", maps_files=None, weather=False):
    adb("shell", f"am force-stop {PKG}")
    time.sleep(1)
    prefs("app", {"whatsnew_seen": 9999, "theme_mode": theme, "theme_day": "SAND", "lang": "en"})
    prefs("units", {"dist": "KM", "temp": "C", "fuel": "L100"})
    prefs("mota", {"orientation": orientation, "screen_on": True})
    prefs("tracks", {"active": "demo"})
    m = {"follow": follow, "track_mode": track_mode, "theme": theme, "auto_zoom": True,
         "pacenotes": True, "section_times": True, "turn_banner": True, "camera_beep": False,
         "layer_weather": weather}
    if maps_files:
        m.update(maps_files)
    prefs("maps", m)


def start():
    adb("shell", f"am start -W -n {ACT}")
    time.sleep(6)


# ---------- reading and tapping the screen ----------

def nodes():
    """Every node with a text or description and its centre, read from the live UI tree."""
    for _ in range(6):
        out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && cat /sdcard/ui.xml")
        if out.strip().startswith("<?xml"):
            root = ET.fromstring(out[out.index("<?xml"):])
            res = []
            for n in root.iter("node"):
                label = n.get("text") or n.get("content-desc") or ""
                b = re.findall(r"\d+", n.get("bounds", ""))
                if label and len(b) == 4:
                    x1, y1, x2, y2 = map(int, b)
                    res.append((label, (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)))
            return res
        time.sleep(2)
    log("UI tree not readable")
    return []


def find(label, exact=True, ns=None):
    ns = ns if ns is not None else nodes()
    for n in ns:
        if (n[0] == label) if exact else (label.lower() in n[0].lower()):
            return n
    return None


def tap_xy(x, y):
    sh(f"input tap {x} {y}")
    time.sleep(1.5)


def tap(label, exact=True, long=False):
    n = find(label, exact)
    if not n:
        log(f"not on screen: {label!r}; seen: {[x[0] for x in nodes()][:60]}")
        return False
    if long:
        sh(f"input swipe {n[1]} {n[2]} {n[1]} {n[2]} 1200")
    else:
        sh(f"input tap {n[1]} {n[2]}")
    time.sleep(1.8)
    return True


def in_front():
    return PKG in sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")


def tap_scrolling(label, near, exact=True):
    """Taps [label], scrolling the list that holds [near] up to bring it into view."""
    for _ in range(4):
        ns = nodes()
        if find(label, exact, ns):
            return tap(label, exact)
        a = find(near, True, ns) or find(near, False, ns)
        if not a:
            break
        sh(f"input swipe {a[1]} {a[2] + 300} {a[1]} {a[2] - 300} 600")
        time.sleep(1.5)
    log(f"not found after scrolling: {label!r}")
    return False


def back():
    """Closes a dialog or panel; if that left the app, brings it back."""
    sh("input keyevent BACK")
    time.sleep(1.2)
    if not in_front():
        start()


def shot(out, name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    (Path(out) / f"{name}.png").write_bytes(png)
    log(f"saved {name}.png ({len(png) // 1024} KB)")


def dismiss_dialogs():
    for word in ("Not now", "Dismiss", "Close", "Later", "OK", "Allow"):
        if find(word):
            tap(word)


# ---------- the run ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--maps", required=True, help="folder with the region's .pmtiles files")
    ap.add_argument("--region", default="madeira")
    ap.add_argument("--route", required=True)
    ap.add_argument("--name", default="Funchal to Pico do Arieiro")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    log("install", adb("install", "-r", "-g", a.apk, timeout=300).strip())
    for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "POST_NOTIFICATIONS",
              "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "READ_PHONE_STATE", "READ_CALL_LOG", "READ_CONTACTS", "ANSWER_PHONE_CALLS"):
        sh(f"pm grant {PKG} android.permission.{p}")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    sh("settings put system screen_off_timeout 1800000")
    sh("svc power stayon true")
    sh("settings put system time_12_24 24")
    # No camera cutout: in landscape it leaves a black strip down one side of the pictures.
    for line in sh("cmd overlay list").splitlines():
        if "cutout" in line and "[x]" in line:
            sh(f"cmd overlay disable {line.split()[-1]}")
    log("overlays:", [l.strip() for l in sh("cmd overlay list").splitlines() if "cutout" in l])
    # First start creates the app's folders.
    start()
    adb("shell", f"am force-stop {PKG}")

    # The map files, where the app keeps downloaded regions.
    ext = f"/sdcard/Android/data/{PKG}/files/maps"
    sh(f"mkdir -p {ext}")
    files = {}
    for f in sorted(Path(a.maps).glob("*.pmtiles")):
        r = subprocess.run(["adb", "push", str(f), f"{ext}/{f.name}"], capture_output=True, text=True)
        if r.returncode != 0:  # fall back: the app copies its own files in
            adb("push", str(f), f"/data/local/tmp/{f.name}")
            sh(f"chmod 644 /data/local/tmp/{f.name}")
            sh(f"run-as {PKG} cp /data/local/tmp/{f.name} {ext}/{f.name}")
        n = f.name
        key = "contours" if "-relevo-" in n else "limits" if "-limites-" in n else "shade" if "-sombra-" in n else "file"
        files[f"{key}_{a.region}"] = n
        ver = re.search(r"(\d{8})", n)
        if key == "file" and ver:
            files[f"ver_{a.region}"] = ver.group(1)
    files["ids"] = [a.region]
    files[f"on_{a.region}"] = True
    log("map files", files)
    log(sh(f"ls -l {ext}"))

    # The demo route as an app track.
    pts = read_gpx(a.route)
    cum = along(pts)
    log(f"route: {len(pts)} points, {cum[-1] / 1000:.1f} km")
    lines = ",".join(f"[{p[0]:.6f},{p[1]:.6f}" + (f",{p[2]:.1f}]" if p[2] is not None else "]") for p in pts)
    track = '{"id":"demo","name":"%s","color":"#2f9bff","width":2,"visible":true,"lines":[[%s]],"wpts":[]}' % (a.name, lines)
    put_private("files/tracks/demo.json", track)

    rider = Rider(pts, 58)
    rider.start()

    def scene(**kw):
        rider.hold()
        set_state(maps_files=files, **kw)
        lat, lon, ele, brg = at(pts, cum, rider.s)
        send_fix(lat, lon, ele, rider.kmh, brg)
        start()
        dismiss_dialogs()

    # 1. Navigating up the mountain by day.
    rider.jump(cum[-1] * 0.32)
    scene(theme="DAY", track_mode="NAV")
    rider.ride(40)
    shot(out, "nav-day")
    log("location:", sh("dumpsys location | grep -m3 -i 'last location'"))
    log("ui:", [n[0] for n in nodes()])

    # 2. Track mode with pacenotes.
    rider.jump(cum[-1] * 0.55)
    scene(theme="DAY", track_mode="TRACK")
    rider.ride(25)
    shot(out, "pacenotes")

    # 3. Night, 3D.
    rider.jump(cum[-1] * 0.7)
    scene(theme="NIGHT", track_mode="NAV")
    rider.ride(25)
    shot(out, "nav-night")

    # 4. Menus over the map (day, standing still so the tree can be read).
    rider.jump(cum[-1] * 0.4)
    scene(theme="DAY", track_mode="NAV")
    rider.ride(15)
    rider.hold()
    time.sleep(3)
    ns = nodes()
    log("map screen:", [n[0] for n in ns])
    dn, gpx = find("DAY", ns=ns), find("GPX", ns=ns)
    lay = find("Layers", ns=ns)
    if lay:
        dn = gpx = lay
    if dn and gpx:  # the layers button sits between the day/night and GPX buttons
        lx, ly = (dn[1] + gpx[1]) // 2, (dn[2] + gpx[2]) // 2
        tap_xy(lx, ly)
        shot(out, "layers")
        tap_xy(lx, ly)
    if tap(a.name, long=True):
        shot(out, "track-menu")
        if tap_scrolling("Route type", "Stop navigation", exact=False):
            shot(out, "route-type")
        back()
    if tap("☰"):
        shot(out, "map-settings")
        if tap("Detail"):
            shot(out, "map-detail")
        if tap_scrolling("Regions", "Detail"):
            time.sleep(4)
            shot(out, "map-regions")
        back()
    if tap("🔍"):
        sh("input text Funchal")
        time.sleep(1)
        sh("input keyevent ENTER")
        time.sleep(4)
        sh("input keyevent BACK")  # the keyboard down, the results in view
        time.sleep(3)
        log("search:", [n[0] for n in nodes()][:40])
        shot(out, "search")
        if not tap("🔍"):
            back()

    # 5. Dashboard and Settings.
    if tap("Dashboard"):
        time.sleep(3)
        shot(out, "dashboard")
    if tap("Settings") or tap("Settings (", exact=False):
        shot(out, "settings-preferences")
        for name, file in (("Tracks (GPX)", "settings-tracks"), ("Maps", "settings-maps"),
                           ("Diagnostics", "settings-diagnostics"), ("About", "settings-about")):
            if tap_scrolling(name, "Preferences"):
                time.sleep(2)
                shot(out, file)

    # North up, flat.
    rider.jump(cum[-1] * 0.5)
    scene(theme="DAY", track_mode="NAV", follow="NORTH")
    rider.ride(20)
    shot(out, "nav-north")

    # Weather over the map.
    rider.jump(cum[-1] * 0.6)
    scene(theme="DAY", track_mode="NAV", follow="NORTH", weather=True)
    rider.ride(35)
    shot(out, "weather")

    # 6. Portrait.
    rider.jump(cum[-1] * 0.45)
    scene(theme="DAY", track_mode="NAV", orientation="PORTRAIT")
    rider.ride(25)
    shot(out, "nav-portrait")

    rider.hold()
    log("done:", sorted(p.name for p in out.glob("*.png")))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # keep what was taken
        log("failed:", e)
        sys.exit(1)
