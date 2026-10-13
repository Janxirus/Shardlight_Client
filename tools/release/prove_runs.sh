#!/bin/bash
# Starts a packaged app once with --capture and checks that it drew its pages, to prove the package runs.
#
#   tools/release/prove_runs.sh "<path to the app's launcher>" [xvfb]
#
# Draws the eight Settings pages (CAPTURE_ONLY keeps it short), gives it five minutes, uses a throw-away data folder,
# and fails unless it wrote at least five pictures.
# Pass "xvfb" on Linux to run under a virtual display. Needs only bash and perl (both are on the CI runners).
set -euo pipefail
app="$1"
work="$(mktemp -d)"
export CAPTURE_ONLY="${CAPTURE_ONLY:-p4-settings-}"
cmd=("$app" --capture "$work/screens" --data-dir "$work/data")
if [ "${2:-}" = "xvfb" ]; then
  cmd=(xvfb-run -a -s "-screen 0 1400x900x24" "${cmd[@]}")
fi
echo "Starting: ${cmd[*]}"
perl -e 'alarm 300; exec @ARGV' -- "${cmd[@]}" || { echo "::error::the packaged app did not finish (exit $?)"; tail -40 "$work"/data/logs/*.log 2>/dev/null || true; exit 1; }
count=$(find "$work/screens" -name '*.png' | wc -l | tr -d ' ')
echo "The packaged app drew $count pages."
[ "$count" -ge 5 ] || { echo "::error::expected at least 5 pictures, got $count"; exit 1; }
