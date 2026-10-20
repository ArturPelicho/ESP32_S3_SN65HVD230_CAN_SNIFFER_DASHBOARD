#!/usr/bin/env python3
"""Terrain heights as raster-dem tiles (Mapbox RGB encoding, lossless WebP) for the app's shaded relief.

    build_shade.py <dem.vrt|tif> <out.mbtiles> <west> <south> <east> <north> <minzoom> <maxzoom>

Height = -10000 + (R * 65536 + G * 256 + B) * 0.1 m. Sea and missing DEM read as 0 m;
tiles that are all sea are left out (the app draws nothing there).
"""
import io
import math
import sqlite3
import sys

import numpy as np
import rasterio
from PIL import Image
from rasterio.enums import Resampling
from rasterio.transform import from_bounds
from rasterio.vrt import WarpedVRT

ORIGIN = math.pi * 6378137.0
SIZE = 256


def tile_range(w, s, e, n, z):
    k = 2 ** z

    def xy(lon, lat):
        x = int((lon + 180) / 360 * k)
        y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * k)
        return min(max(x, 0), k - 1), min(max(y, 0), k - 1)

    x0, y0 = xy(w, n)
    x1, y1 = xy(e, s)
    return range(x0, x1 + 1), range(y0, y1 + 1)


def mercator_bounds(z, x, y):
    t = 2 * ORIGIN / 2 ** z
    return -ORIGIN + x * t, ORIGIN - (y + 1) * t, -ORIGIN + (x + 1) * t, ORIGIN - y * t


def main():
    src_path, out = sys.argv[1], sys.argv[2]
    w, s, e, n = map(float, sys.argv[3:7])
    z0, z1 = int(sys.argv[7]), int(sys.argv[8])
    db = sqlite3.connect(out)
    db.executescript("""
        DROP TABLE IF EXISTS tiles; DROP TABLE IF EXISTS metadata;
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
    """)
    meta = {"name": "Relevo (sombreado)", "format": "webp", "type": "baselayer",
            "minzoom": str(z0), "maxzoom": str(z1), "bounds": f"{w},{s},{e},{n}",
            "attribution": "Copernicus DEM GLO-30 (c) DLR/Airbus, ESA", "encoding": "mapbox"}
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    count = 0
    with rasterio.open(src_path) as src:
        for z in range(z0, z1 + 1):
            xs, ys = tile_range(w, s, e, n, z)
            for x in xs:
                for y in ys:
                    b = mercator_bounds(z, x, y)
                    with WarpedVRT(src, crs="EPSG:3857", transform=from_bounds(*b, SIZE, SIZE),
                                   width=SIZE, height=SIZE, resampling=Resampling.bilinear,
                                   src_nodata=src.nodata, nodata=-32768) as v:
                        h = v.read(1).astype(np.float64)
                    h[(h < -500) | ~np.isfinite(h)] = 0
                    if not (h > 0.5).any():
                        continue
                    # Whole metres are plenty for shading and compress about a third smaller than 0.1 m.
                    val = np.clip(np.round(h) * 10 + 100000, 0, 2 ** 24 - 1).astype(np.uint32)
                    rgb = np.dstack([(val >> 16) & 255, (val >> 8) & 255, val & 255]).astype(np.uint8)
                    buf = io.BytesIO()
                    Image.fromarray(rgb, "RGB").save(buf, "WEBP", lossless=True, quality=100, method=4)
                    db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, x, 2 ** z - 1 - y, buf.getvalue()))
                    count += 1
            db.commit()
            print(f"  z{z}: {count} tiles so far", flush=True)
    db.commit()
    db.close()
    if count == 0:
        sys.exit("no land tiles")


if __name__ == "__main__":
    main()
