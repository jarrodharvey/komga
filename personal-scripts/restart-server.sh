#!/usr/bin/env bash
# Restart the Komga backend and webui dev server (stop-server.sh, then start-server.sh).
# Usage: personal-scripts/restart-server.sh [profiles]   (passed through to start-server.sh)
set -euo pipefail
DIR="$(dirname "$0")"

"$DIR/stop-server.sh"
"$DIR/start-server.sh" "$@"
