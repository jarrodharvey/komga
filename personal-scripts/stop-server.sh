#!/usr/bin/env bash
# Stop the Komga backend started by start-server.sh.
set -uo pipefail
cd "$(dirname "$0")/.."   # run from the repo root

PID_FILE=".komga-server.pid"
PORT=8080

# Recursively kill a process and its children (Gradle forks a separate JVM for bootRun).
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid"); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null
}

stopped=false
if [[ -f "$PID_FILE" ]]; then
  PID=$(sed -n 1p "$PID_FILE")
  PORT=$(sed -n 2p "$PID_FILE")
  PORT=${PORT:-8080}
  if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping Komga (PID $PID)..."
    kill_tree "$PID"
    stopped=true
  fi
  rm -f "$PID_FILE"
fi

# Fallback: anything still listening on the Komga port.
for _ in {1..15}; do
  LISTENER=$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)
  [[ -z "$LISTENER" ]] && break
  kill $LISTENER 2>/dev/null
  stopped=true
  sleep 1
done
LISTENER=$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)
[[ -n "$LISTENER" ]] && { echo "Force killing PID(s) on port $PORT: $LISTENER"; kill -9 $LISTENER; }

$stopped && echo "Komga stopped." || echo "Komga was not running."
