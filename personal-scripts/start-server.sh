#!/usr/bin/env bash
# Start the Komga backend and the webui dev server in the background.
# Backend: dev profile, file-based DB in config-dir/ so data persists, default users.
# Webui: Vue dev server on 8081 with live reload, calling the backend on 8080.
# Usage: personal-scripts/start-server.sh [profiles]   e.g. personal-scripts/start-server.sh dev,noclaim  (in-memory DB, wiped on restart)
set -euo pipefail
cd "$(dirname "$0")/.."   # run from the repo root

PROFILES="${1:-dev,localdb,noclaim}"
PID_FILE=".komga-server.pid"
LOG_FILE="komga-server.log"
WEBUI_PID_FILE=".komga-webui.pid"
WEBUI_LOG_FILE="komga-webui.log"
WEBUI_PORT=8081
# The dev profile listens on 8080 (application-dev.yml); otherwise 25600.
if [[ ",$PROFILES," == *",dev,"* ]]; then PORT=8080; else PORT=25600; fi

# --- Backend ---
if [[ -f "$PID_FILE" ]] && kill -0 "$(head -1 "$PID_FILE")" 2>/dev/null; then
  echo "Komga backend already running (PID $(head -1 "$PID_FILE")); leaving it alone."
else
  if [[ -z "${JAVA_HOME:-}" ]]; then
    if JH=$(/usr/libexec/java_home -v 21+ 2>/dev/null); then
      export JAVA_HOME="$JH"
    elif [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
      export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    else
      echo "JDK 21+ not found; set JAVA_HOME." >&2
      exit 1
    fi
  fi

  echo "Starting Komga backend with profiles '$PROFILES' (JAVA_HOME=$JAVA_HOME)"
  nohup ./gradlew --no-daemon :komga:bootRun --args="--spring.profiles.active=$PROFILES" \
    >"$LOG_FILE" 2>&1 &
  printf "%s\n%s\n" $! "$PORT" >"$PID_FILE"
  echo "  PID $!, logging to $LOG_FILE"
  echo "  API at http://localhost:$PORT once started (first build may take a few minutes)."
fi

# --- Webui dev server ---
if lsof -ti tcp:"$WEBUI_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Webui dev server already running on port $WEBUI_PORT; leaving it alone."
else
  if [[ ! -d komga-webui/node_modules ]]; then
    echo "Installing webui dependencies (first run)..."
    (cd komga-webui && npm install)
  fi
  echo "Starting webui dev server"
  nohup npm --prefix komga-webui run serve >"$WEBUI_LOG_FILE" 2>&1 &
  echo $! >"$WEBUI_PID_FILE"
  echo "  PID $!, logging to $WEBUI_LOG_FILE"
  echo "  UI at http://localhost:$WEBUI_PORT once compiled."
fi

echo "Follow logs with: tail -f $LOG_FILE $WEBUI_LOG_FILE"
