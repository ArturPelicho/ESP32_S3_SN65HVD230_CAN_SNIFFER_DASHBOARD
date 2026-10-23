#!/usr/bin/env bash
# Builds one map region for the NavExpress app.
#   build_region.sh <id> <version> <planet-url>
# Reads the region from maps/regions.json and writes to out/:
#   <id>-<version>.pmtiles          roads, places, water... (Protomaps basemap, OpenStreetMap data)
#   <id>-relevo-<version>.pmtiles   contour lines every 10 m from the Copernicus 30 m DEM (if the region asks for it)
#   <id>-limites-<version>.pmtiles  speed limits and speed cameras from OpenStreetMap (Geofabrik extract)
#   <id>-sombra-<version>.pmtiles   terrain heights (raster-dem) for the shaded relief, with the contours
set -euo pipefail
ID="$1"; VER="$2"; PLANET="$3"
R=$(jq -c --arg id "$ID" '.[] | select(.id == $id)' maps/regions.json)
read -r W S E N < <(echo "$R" | jq -r '.bbox | map(tostring) | join(" ")')
MAXZ=$(echo "$R" | jq -r '.maxzoom')
mkdir -p out work

echo "== $ID: map extract z0-$MAXZ, bbox $W,$S,$E,$N"
# The planet server sometimes resets long downloads; try a few times.
for try in 1 2 3 4; do
  pmtiles extract "$PLANET" "out/$ID-$VER.pmtiles" --bbox="$W,$S,$E,$N" --maxzoom="$MAXZ" && break
  [ "$try" -lt 4 ] || exit 1
  echo "!! extract failed (try $try), retrying in $((try * 30)) s"; rm -f "out/$ID-$VER.pmtiles"; sleep $((try * 30))
done
pmtiles show "out/$ID-$VER.pmtiles" | head -20 || true   # head closes the pipe early

# Speed limits are a bonus: a failure here must not stop the map from publishing.
maps/build_limits.sh "$ID" "$VER" || { echo "!! $ID: speed limits failed, publishing without them"; rm -f "out/$ID-limites-$VER.pmtiles"; }

if [ "$(echo "$R" | jq -r '.contours')" != "true" ]; then exit 0; fi

echo "== $ID: contour lines"
DEM="work/dem-$ID"; mkdir -p "$DEM"
LAT0=$(python3 -c "import math;print(math.floor($S))"); LAT1=$(python3 -c "import math;print(math.ceil($N)-1)")
LON0=$(python3 -c "import math;print(math.floor($W))"); LON1=$(python3 -c "import math;print(math.ceil($E)-1)")
for lat in $(seq "$LAT0" "$LAT1"); do
  for lon in $(seq "$LON0" "$LON1"); do
    if [ "$lat" -ge 0 ]; then NS=$(printf "N%02d" "$lat"); else NS=$(printf "S%02d" $((-lat))); fi
    if [ "$lon" -ge 0 ]; then EW=$(printf "E%03d" "$lon"); else EW=$(printf "W%03d" $((-lon))); fi
    T="Copernicus_DSM_COG_10_${NS}_00_${EW}_00_DEM"
    # Tiles that are all sea do not exist; skip them.
    curl -sf -o "$DEM/$T.tif" "https://copernicus-dem-30m.s3.amazonaws.com/$T/$T.tif" && echo "  $T" || rm -f "$DEM/$T.tif"
  done
done
ls "$DEM"/*.tif >/dev/null
gdalbuildvrt -q "work/$ID.vrt" "$DEM"/*.tif
# The native ~30 m grid (1 arc second): enough detail for 10 m lines off-road.
gdalwarp -q -overwrite -te "$W" "$S" "$E" "$N" -tr 0.0003 0.0003 -r bilinear "work/$ID.vrt" "work/$ID-30m.tif"
gdal_contour -q -a ele -i 10 "work/$ID-30m.tif" "work/$ID-contours.gpkg"
# ele: height in m; idx: 1 on the 100 m lines (older apps draw those bolder);
# lvl: the coarsest spacing the line belongs to (100, 50, 20 or 10), for the zoom filter below.
ogr2ogr -f GeoJSONSeq "work/$ID-contours.geojsons" "work/$ID-contours.gpkg" -dialect sqlite \
  -sql "SELECT CAST(ele AS INTEGER) AS ele,
          CASE WHEN CAST(ele AS INTEGER) % 100 = 0 THEN 1 ELSE 0 END AS idx,
          CASE WHEN CAST(ele AS INTEGER) % 100 = 0 THEN 100
               WHEN CAST(ele AS INTEGER) % 50 = 0 THEN 50
               WHEN CAST(ele AS INTEGER) % 20 = 0 THEN 20 ELSE 10 END AS lvl,
          geom FROM contour WHERE ele > 0"
# Every 100 m from zoom 11, 50 m from 12, 20 m from 13, 10 m from 14 (the app overzooms z14).
# -j filters cannot do modulo, hence lvl. Simplification 8 is ~4 m at z14: well under the 30 m cells.
tippecanoe -q -f -o "out/$ID-relevo-$VER.pmtiles" -l contours -n "Curvas de nivel $ID" \
  -A "Copernicus DEM GLO-30 (c) DLR/Airbus, ESA" -Z11 -z14 --simplification=8 --no-tile-size-limit \
  -j '{"contours":["any",["==","lvl",100],["all",[">=","lvl",50],[">=","$zoom",12]],["all",[">=","lvl",20],[">=","$zoom",13]],[">=","$zoom",14]]}' \
  "work/$ID-contours.geojsons"
pmtiles show "out/$ID-relevo-$VER.pmtiles" | head -12 || true   # head closes the pipe early

# Shaded relief is a bonus too: without it the map and contours still publish.
echo "== $ID: terrain heights for the shaded relief"
# Up to z11 (about 60 m cells): the map overzooms it smoothly, and Portugal stays near 40 MB.
if python3 maps/build_shade.py "work/$ID.vrt" "work/$ID-sombra.mbtiles" "$W" "$S" "$E" "$N" 5 11 &&
   pmtiles convert "work/$ID-sombra.mbtiles" "out/$ID-sombra-$VER.pmtiles"; then
  pmtiles show "out/$ID-sombra-$VER.pmtiles" | head -12 || true
else
  echo "!! $ID: shaded relief failed, publishing without it"; rm -f "out/$ID-sombra-$VER.pmtiles"
fi
rm -rf "$DEM" work/"$ID"*
