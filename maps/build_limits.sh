#!/usr/bin/env bash
# Speed limits and speed cameras for one region, from the Geofabrik OpenStreetMap extract.
#   build_limits.sh <id> <version>   ->  out/<id>-limites-<version>.pmtiles
# Layers: "limits" (lines with kmh, or kmh_f/kmh_b by direction), "cameras" (points, kmh and dir if tagged),
# "dirt" (unpaved roads and tracks, g = 1 easy, 2 medium, 3 hard), and from zoom 5 to 8 "lowroads" (trunk to
# tertiary, so roads like the ER show when zoomed out, where the base map has none yet).
set -euo pipefail
ID="$1"; VER="$2"
R=$(jq -c --arg id "$ID" '.[] | select(.id == $id)' maps/regions.json)
read -r W S E N < <(echo "$R" | jq -r '.bbox | map(tostring) | join(" ")')
mkdir -p out work
PBF="work/osm-$ID.pbf"
OSM=$(echo "$R" | jq -r '.osm // empty')
[ -n "$OSM" ] || exit 0
echo "== $ID: speed limits and cameras from $OSM"
curl -sfL --retry 4 --retry-all-errors -o "$PBF" "https://download.geofabrik.de/$OSM-latest.osm.pbf"
osmium extract -b "$W,$S,$E,$N" "$PBF" -o "work/$ID-clip.pbf" --overwrite
osmium tags-filter "work/$ID-clip.pbf" w/maxspeed w/maxspeed:forward w/maxspeed:backward -o "work/$ID-ways.pbf" --overwrite
osmium tags-filter "work/$ID-clip.pbf" n/highway=speed_camera -o "work/$ID-cams.pbf" --overwrite
osmium tags-filter "work/$ID-clip.pbf" w/highway=track w/surface w/smoothness -o "work/$ID-dirt.pbf" --overwrite
osmium tags-filter "work/$ID-clip.pbf" w/highway=trunk,primary,secondary,tertiary -o "work/$ID-low.pbf" --overwrite
osmium export "work/$ID-low.pbf" -f geojsonseq --geometry-types=linestring -o "work/$ID-low.geojsons" --overwrite
python3 maps/limits.py lowroads "work/$ID-low.geojsons" "work/$ID-lowr.geojsons"
osmium export "work/$ID-dirt.pbf" -f geojsonseq --geometry-types=linestring -o "work/$ID-dirt.geojsons" --overwrite
python3 maps/limits.py dirt "work/$ID-dirt.geojsons" "work/$ID-dirtg.geojsons"
osmium export "work/$ID-ways.pbf" -f geojsonseq --geometry-types=linestring -o "work/$ID-ways.geojsons" --overwrite
osmium export "work/$ID-cams.pbf" -f geojsonseq --geometry-types=point -o "work/$ID-cams.geojsons" --overwrite
python3 maps/limits.py ways "work/$ID-ways.geojsons" "work/$ID-limits.geojsons"
python3 maps/limits.py cameras "work/$ID-cams.geojsons" "work/$ID-cameras.geojsons"
tippecanoe -q -f -o "work/$ID-near.mbtiles" -n "Limites $ID" -A "(c) OpenStreetMap contributors" \
  -Z12 -z14 -r1 --no-feature-limit --no-tile-size-limit --no-line-simplification --no-tiny-polygon-reduction \
  -L limits:"work/$ID-limits.geojsons" -L cameras:"work/$ID-cameras.geojsons" -L dirt:"work/$ID-dirtg.geojsons"
tippecanoe -q -f -o "work/$ID-far.mbtiles" -Z5 -z8 -r1 --no-feature-limit --no-tile-size-limit --simplification=4 \
  -L lowroads:"work/$ID-lowr.geojsons"
tile-join -q -f -pk -o "out/$ID-limites-$VER.pmtiles" -n "Limites $ID" -A "(c) OpenStreetMap contributors" \
  "work/$ID-near.mbtiles" "work/$ID-far.mbtiles"
pmtiles show "out/$ID-limites-$VER.pmtiles" | head -12 || true   # head closes the pipe early
rm -f "$PBF" work/"$ID"-*
