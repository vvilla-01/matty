#!/usr/bin/env bash
# Copies the Matty plugin's client contract (its fixtures and its compatibility record)
# from the plugin's checkout into Matty's tests (app/src/test/resources/villanet). The fixtures are what the
# plugin really answers, written by its own tests (contract/ in its repository);
# Matty's unit tests decode these copies with the models the app uses, and
# VillaNetContractTest fails while the copies and the plugin's files differ.
# Run this after the plugin's contract changes, then make the app's tests pass.
#
#   VILLANET_REPO   the plugin's checkout (default: "../Broadcast Box Plugin" beside Matty)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${VILLANET_REPO:-$HERE/../../Broadcast Box Plugin}"
SRC="$REPO/contract/fixtures"
DST="$HERE/../app/src/test/resources/villanet"
[ -d "$SRC" ] || { echo "No contract fixtures at $SRC (set VILLANET_REPO)"; exit 1; }
mkdir -p "$DST"
rm -f "$DST"/*.json
cp "$SRC"/*.json "$DST"/
# The compatibility record (which contract, and the first plugin version that spoke it) travels too:
# VillaNetContractTest holds the app's own two figures to it.
cp "$REPO/contract/contract.json" "$DST/contract.json"
# Which state of the plugin these came from, for whoever reads a failing test later.
{
  printf 'commit %s\n' "$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  if [ -n "$(git -C "$REPO" status --porcelain 2>/dev/null)" ]; then echo "with uncommitted changes"; fi
} > "$DST/SOURCE"
echo "Copied $(ls "$DST"/*.json | wc -l) files from $REPO/contract ($(tr '\n' ' ' < "$DST/SOURCE"))"
