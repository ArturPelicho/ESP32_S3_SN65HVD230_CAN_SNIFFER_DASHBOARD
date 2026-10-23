#!/usr/bin/env python3
"""Fetches the demo route for the screenshots as GPX: BRouter first (it gives heights), OSRM after.

    route.py OUT.gpx LON,LAT LON,LAT ...
"""
import json
import sys
import urllib.parse
import urllib.request


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "NavExpress-screenshots"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode()


def main():
    out, pts = sys.argv[1], sys.argv[2:]
    for profile in ("car-fast", "car-eco", "moped", "trekking"):
        url = "https://brouter.de/brouter?" + urllib.parse.urlencode(
            {"lonlats": "|".join(pts), "profile": profile, "alternativeidx": 0, "format": "gpx"})
        try:
            gpx = get(url)
            if "<trkpt" in gpx:
                open(out, "w").write(gpx)
                print(f"BRouter {profile}: {gpx.count('<trkpt')} points")
                return
            print(f"BRouter {profile}: {gpx[:200]}")
        except Exception as e:
            print(f"BRouter {profile}: {e}")
    url = "https://router.project-osrm.org/route/v1/driving/" + ";".join(pts) + "?overview=full&geometries=geojson"
    coords = json.loads(get(url))["routes"][0]["geometry"]["coordinates"]
    with open(out, "w") as f:
        f.write('<?xml version="1.0"?>\n<gpx version="1.1" creator="NavExpress"><trk><trkseg>\n')
        for lon, lat in coords:
            f.write(f'<trkpt lat="{lat}" lon="{lon}"></trkpt>\n')
        f.write("</trkseg></trk></gpx>\n")
    print(f"OSRM: {len(coords)} points")


if __name__ == "__main__":
    main()
