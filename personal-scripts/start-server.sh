#!/usr/bin/env bash
# Start the Komga backend (and optionally the webui dev server) in the background.
# Backend: runs the built jar directly (no Gradle left running), rebuilding it first only when
#   sources changed. Dev profile, file-based DB in config-dir/ so data persists, default users.
#   The bundled UI is served at http://localhost:8080 and is rebuilt from komga-webui/ when it changed.
# Webui (--webui): Vue dev server on 8081 with live reload, for frontend work only.
# Usage: personal-scripts/start-server.sh [--webui] [profiles]
#   e.g. personal-scripts/start-server.sh --webui
#        personal-scripts/start-server.sh dev,noclaim   (in-memory DB, wiped on restart)
#   KOMGA_HEAP=1g personal-scripts/start-server.sh       (raise the heap cap, default 512m)
set -euo pipefail
cd "$(dirname "$0")/.."   # run from the repo root

START_WEBUI=false
if [[ "${1:-}" == "--webui" ]]; then START_WEBUI=true; shift; fi
PROFILES="${1:-dev,localdb,noclaim}"
HEAP="${KOMGA_HEAP:-512m}"
PID_FILE=".komga-server.pid"
LOG_FILE="komga-server.log"
WEBUI_PID_FILE=".komga-webui.pid"
WEBUI_LOG_FILE="komga-webui.log"
WEBUI_PORT=8081
BUNDLED_INDEX="komga/src/main/resources/public/index.html"
# The dev profile listens on 8080 (application-dev.yml); otherwise 25600.
if [[ ",$PROFILES," == *",dev,"* ]]; then PORT=8080; else PORT=25600; fi

find_jar() { ls komga/build/libs/komga-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true; }

ensure_webui_deps() {
  if [[ ! -d komga-webui/node_modules ]]; then
    echo "Installing webui dependencies (first run)..."
    (cd komga-webui && npm install)
  fi
}

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

  # Rebuild the bundled webui if its sources are newer than the copy in resources/public.
  GRADLE_TASKS=()
  if [[ ! -f "$BUNDLED_INDEX" ]] || \
     [[ -n "$(find komga-webui/src komga-webui/package.json -newer "$BUNDLED_INDEX" -print -quit)" ]]; then
    ensure_webui_deps
    echo "Building the bundled webui (komga-webui changed)..."
    npm --prefix komga-webui run build
    GRADLE_TASKS+=(:komga:webuiCopyIndex)
  fi

  # Rebuild the jar if it is missing, the webui bundle changed, or backend sources changed.
  JAR=$(find_jar)
  if [[ -z "$JAR" ]] || (( ${#GRADLE_TASKS[@]} )) || \
     [[ -n "$(find komga/src -path komga/src/main/resources/public -prune -o -type f -newer "$JAR" -print -quit)" ]] || \
     [[ komga/build.gradle.kts -nt "$JAR" ]] || [[ gradle.properties -nt "$JAR" ]]; then
    echo "Building the Komga jar (first build may take a few minutes)..."
    # In-process Kotlin compilation so no compile daemon lingers after the build.
    ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process ${GRADLE_TASKS[@]+"${GRADLE_TASKS[@]}"} :komga:bootJar
    JAR=$(find_jar)
    [[ -n "$JAR" ]] || { echo "Build finished but no jar found in komga/build/libs." >&2; exit 1; }
  fi

  echo "Starting Komga backend with profiles '$PROFILES', heap $HEAP"
  nohup "$JAVA_HOME/bin/java" "-Xmx$HEAP" -XX:+UseSerialGC -jar "$JAR" \
    --spring.profiles.active="$PROFILES" >"$LOG_FILE" 2>&1 &
  printf "%s\n%s\n" $! "$PORT" >"$PID_FILE"
  echo "  PID $!, logging to $LOG_FILE"
  echo "  UI and API at http://localhost:$PORT once started."
fi

# --- Webui dev server (opt-in) ---
if $START_WEBUI; then
  if lsof -ti tcp:"$WEBUI_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Webui dev server already running on port $WEBUI_PORT; leaving it alone."
  else
    ensure_webui_deps
    echo "Starting webui dev server"
    nohup npm --prefix komga-webui run serve >"$WEBUI_LOG_FILE" 2>&1 &
    echo $! >"$WEBUI_PID_FILE"
    echo "  PID $!, logging to $WEBUI_LOG_FILE"
    echo "  Dev UI at http://localhost:$WEBUI_PORT once compiled."
  fi
fi

echo "Follow logs with: tail -f $LOG_FILE$($START_WEBUI && echo " $WEBUI_LOG_FILE")"
