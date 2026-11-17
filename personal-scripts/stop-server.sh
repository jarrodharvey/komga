#!/usr/bin/env bash
# Stop the Komga backend and webui dev server started by start-server.sh.
set -uo pipefail
cd "$(dirname "$0")/.."   # run from the repo root

# Recursively kill a process and its children (Gradle forks a separate JVM for bootRun; npm spawns node).
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid"); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null
}

# stop_component <name> <pid file> <default port>
# The PID file holds the PID on line 1 and, optionally, the port on line 2.
stop_component() {
  local name=$1 pid_file=$2 port=$3 pid listener stopped=false
  if [[ -f "$pid_file" ]]; then
    pid=$(sed -n 1p "$pid_file")
    port=$(sed -n 2p "$pid_file")
    port=${port:-$3}
    if kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $name (PID $pid)..."
      kill_tree "$pid"
      stopped=true
    fi
    rm -f "$pid_file"
  fi

  # Fallback: anything still listening on the port.
  for _ in {1..15}; do
    listener=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null)
    [[ -z "$listener" ]] && break
    kill $listener 2>/dev/null
    stopped=true
    sleep 1
  done
  listener=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null)
  [[ -n "$listener" ]] && { echo "Force killing PID(s) on port $port: $listener"; kill -9 $listener; }

  $stopped && echo "$name stopped." || echo "$name was not running."
}

stop_component "Webui dev server" ".komga-webui.pid" 8081
stop_component "Komga backend" ".komga-server.pid" 8080
