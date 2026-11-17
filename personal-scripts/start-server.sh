#!/usr/bin/env bash
# Start the Komga backend in the background (dev profile, file-based DB in config-dir/ so data persists, default users).
# Usage: personal-scripts/start-server.sh [profiles]   e.g. personal-scripts/start-server.sh dev,noclaim  (in-memory DB, wiped on restart)
set -euo pipefail
cd "$(dirname "$0")/.."   # run from the repo root

PROFILES="${1:-dev,localdb,noclaim}"
PID_FILE=".komga-server.pid"
LOG_FILE="komga-server.log"
# The dev profile listens on 8080 (application-dev.yml); otherwise 25600.
if [[ ",$PROFILES," == *",dev,"* ]]; then PORT=8080; else PORT=25600; fi

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

if [[ -f "$PID_FILE" ]] && kill -0 "$(head -1 "$PID_FILE")" 2>/dev/null; then
  echo "Komga already running (PID $(head -1 "$PID_FILE")). Use personal-scripts/stop-server.sh first."
  exit 1
fi

echo "Starting Komga with profiles '$PROFILES' (JAVA_HOME=$JAVA_HOME)"
nohup ./gradlew --no-daemon :komga:bootRun --args="--spring.profiles.active=$PROFILES" \
  >"$LOG_FILE" 2>&1 &
printf "%s\n%s\n" $! "$PORT" >"$PID_FILE"

echo "PID $!, logging to $LOG_FILE"
echo "Server will be at http://localhost:$PORT once started (first build may take a few minutes)."
echo "Follow logs with: tail -f $LOG_FILE"
