#!/usr/bin/env python3
"""Speed limits and speed cameras for the Mota Dash map.

Reads osmium GeoJSONSeq exports and writes compact GeoJSONSeq with only what the app needs:
  limits.py ways <in> <out>     lines: kmh, and kmh_f / kmh_b when they differ by direction
  limits.py cameras <in> <out>  points: kmh (if tagged), dir (degrees, if tagged)
"""
import json
import re
import sys

# Implicit limits (OSM "zone" codes) for the countries the app ships.
IMPLICIT = {
    "PT:urban": 50, "PT:rural": 90, "PT:trunk": 100, "PT:motorway": 120,
    "ES:urban": 50, "ES:rural": 90, "ES:trunk": 100, "ES:motorway": 120,
    "ES:zone30": 30, "ES:zone20": 20, "PT:zone30": 30,
}


def kmh(v):
    """'50', '50 mph', 'PT:urban', '50;70' -> km/h, or None."""
    if not v:
        return None
    v = v.strip().split(";")[0].strip()
    if v in IMPLICIT:
        return IMPLICIT[v]
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(mph|km/h|kmh)?", v)
    if not m:
        return None
    n = float(m.group(1))
    if m.group(2) == "mph":
        n *= 1.609344
    n = int(round(n))
    return n if 5 <= n <= 150 else None


def feature(geom, props):
    return json.dumps({"type": "Feature", "geometry": geom, "properties": props}, separators=(",", ":"))


def ways(src, dst):
    n = 0
    with open(src) as f, open(dst, "w") as o:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            ft = json.loads(line)
            if ft["geometry"]["type"] not in ("LineString", "MultiLineString"):
                continue
            t = ft.get("properties") or {}
            if "highway" not in t:
                continue
            base = kmh(t.get("maxspeed"))
            fw = kmh(t.get("maxspeed:forward")) or base
            bw = kmh(t.get("maxspeed:backward")) or base
            if t.get("oneway") in ("yes", "true", "1"):
                bw = None
            elif t.get("oneway") == "-1":
                fw = None
            if fw is None and bw is None:
                continue
            p = {}
            if fw == bw:
                p["kmh"] = fw
            else:
                if fw is not None:
                    p["kmh_f"] = fw
                if bw is not None:
                    p["kmh_b"] = bw
            o.write(feature(ft["geometry"], p) + "\n")
            n += 1
    print(f"limits: {n} ways", file=sys.stderr)


def cameras(src, dst):
    n = 0
    with open(src) as f, open(dst, "w") as o:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            ft = json.loads(line)
            if ft["geometry"]["type"] != "Point":
                continue
            t = ft.get("properties") or {}
            p = {}
            k = kmh(t.get("maxspeed"))
            if k:
                p["kmh"] = k
            d = t.get("direction", "")
            if re.fullmatch(r"\d+(\.\d+)?", d):
                p["dir"] = float(d) % 360
            o.write(feature(ft["geometry"], p) + "\n")
            n += 1
    print(f"limits: {n} speed cameras", file=sys.stderr)


if __name__ == "__main__":
    {"ways": ways, "cameras": cameras}[sys.argv[1]](sys.argv[2], sys.argv[3])
