#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

# Agent-facing front end for Compose Driver: renders one composable headlessly (Robolectric, no
# emulator) and exposes its semantics tree, screenshots and input over HTTP. See AGENTS.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/compose-driver"
PORT_FILE="$OUT/port"
PID_FILE="$OUT/server.pid"
LOG="$OUT/server.log"
SCREENS_FILE="$ROOT/app/src/test/java/com/librestatic/opencinecam/driver/DriverScreens.kt"
SCREENS_CLASS="com.librestatic.opencinecam.driver.DriverScreensKt"
SERVER_TEST="com.librestatic.opencinecam.driver.ComposeDriverServer"

usage() {
  cat <<'EOF'
Usage: tools/compose-driver.sh <command> [args]

Server
  start <Screen|fqn> [--device D] [--night] [--theme cine|you] [--port N]
                         Build and serve a composable; waits until it answers.
                         D: phone (default, 411x914dp @420), cover (411x960dp), inner
                         (850x946dp), tablet (800x1280dp), landscape (914x411dp), compact
                         (360x640dp), or raw Robolectric qualifiers.
  stop                   Stop the server.
  status                 Print "ok" when the server answers.
  list                   List the screens in DriverScreens.kt.
  log                    Tail the server log.

Inspect (selectors: tag=<testTag> text=<text> substring=true ignorecase=true)
  tree [selector]        Semantics tree (also saved to build/compose-driver/tree.txt).
  screenshot [name] [selector]
                         PNG to build/compose-driver/screenshots/<name>.png (default: screen).

Act (each accepts a selector, and gif=<ms> to record build/compose-driver/screenshots/<endpoint>.gif)
  click | longClick | doubleClick | scrollTo | textClearance | navigateBack [selector]
  textInput <text> [selector] | textReplacement <text> [selector]
  swipe UP|DOWN|LEFT|RIGHT [selector]
  key <Key> [modifiers=CtrlLeft,...] [selector]
  waitForNode [timeout=ms] <selector> | waitForIdle
  reset [Screen|fqn]     Recreate the content, optionally switching composable without a restart.
  get <endpoint> [k=v ...]
                         Raw call to any Compose Driver endpoint.
EOF
}

port() { cat "$PORT_FILE" 2>/dev/null || echo "${COMPOSE_DRIVER_PORT:-8765}"; }
base() { echo "http://127.0.0.1:$(port)"; }

fqn() {
  case "$1" in
    *.*) echo "$1" ;;
    *) echo "$SCREENS_CLASS.$1" ;;
  esac
}

qualifiers() {
  case "$1" in
    phone) echo "w411dp-h914dp-port-420dpi" ;;
    cover) echo "w411dp-h960dp-port-420dpi" ;;
    inner) echo "w850dp-h946dp-port-420dpi" ;;
    tablet) echo "w800dp-h1280dp-port-xhdpi" ;;
    landscape) echo "w914dp-h411dp-land-420dpi" ;;
    compact) echo "w360dp-h640dp-port-xhdpi" ;;
    *) echo "$1" ;;
  esac
}

# Turns selector shorthands and k=v pairs into curl --data-urlencode arguments.
query_args() {
  local arg key value
  for arg in "$@"; do
    [[ "$arg" == *=* ]] || { echo "compose-driver: expected key=value, got '$arg'" >&2; exit 2; }
    key="${arg%%=*}"
    value="${arg#*=}"
    case "$key" in
      raw:*) key="${key#raw:}" ;; # an endpoint parameter that must not be read as a selector
      tag) key=nodeTag ;;
      text) key=nodeText ;;
      substring) key=nodeTextSubstring ;;
      ignorecase) key=nodeTextIgnoreCase ;;
      gif) key=gifDurationMs ;;
    esac
    printf '%s\0' --data-urlencode "$key=$value"
  done
}

# Saves a binary response to $1; on an error status prints the server's message instead.
fetch() {
  local file="$1" endpoint="$2"
  shift 2
  local status
  status="$(curl -sS -G "$@" "$(base)/$endpoint" -o "$file" -w '%{http_code}')"
  if [[ "$status" != 200 ]]; then
    cat "$file" >&2
    echo >&2
    rm -f "$file"
    return 1
  fi
  echo "$file"
}

call() {
  local endpoint="$1"
  shift
  local args=()
  mapfile -d '' args < <(query_args "$@")
  local gif=false arg
  for arg in "$@"; do [[ "$arg" == gif=* || "$arg" == gifDurationMs=* ]] && gif=true; done
  if [[ "$gif" == true ]]; then
    mkdir -p "$OUT/screenshots"
    fetch "$OUT/screenshots/${endpoint//\//-}.gif" "$endpoint" "${args[@]}"
  else
    curl -sS -G "${args[@]}" "$(base)/$endpoint"
    echo
  fi
}

java_major() { "$1/bin/java" -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p'; }

# Compose Driver is compiled for Java 21: pick the oldest JDK >= 21, unless one is given.
driver_java_home() {
  if [[ -n "${COMPOSE_DRIVER_JAVA_HOME:-}" ]]; then
    echo "$COMPOSE_DRIVER_JAVA_HOME"
    return
  fi
  local candidate major best="" best_major=999
  for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/* "$HOME"/.jdks/* /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [[ -n "$candidate" && -x "$candidate/bin/java" ]] || continue
    major="$(java_major "$candidate")"
    [[ "$major" =~ ^[0-9]+$ ]] || continue
    if (( major >= 21 && major < best_major )); then
      best="$candidate"
      best_major="$major"
    fi
  done
  [[ -n "$best" ]] && echo "$best"
}

alive() { [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }
answering() { curl -sf -m 2 "$(base)/status" >/dev/null 2>&1; }

start() {
  local screen="" device=phone night=false theme=cine port_number="${COMPOSE_DRIVER_PORT:-8765}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --device) device="$2"; shift 2 ;;
      --night) night=true; shift ;;
      --theme) theme="$2"; shift 2 ;;
      --port) port_number="$2"; shift 2 ;;
      -*) echo "compose-driver: unknown option $1" >&2; exit 2 ;;
      *) screen="$1"; shift ;;
    esac
  done
  [[ -n "$screen" ]] || { echo "compose-driver: start needs a screen; see 'list'" >&2; exit 2; }
  if alive; then
    echo "compose-driver: already running (pid $(cat "$PID_FILE"), port $(port)); use reset or stop" >&2
    exit 1
  fi
  if curl -s -m 2 "http://127.0.0.1:$port_number/" >/dev/null 2>&1; then
    echo "compose-driver: port $port_number is busy; pass --port" >&2
    exit 1
  fi
  if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -f "$ROOT/local.properties" ]]; then
    echo "compose-driver: set ANDROID_HOME (or ANDROID_SDK_ROOT) to the Android SDK" >&2
    exit 1
  fi

  local java_home
  java_home="$(driver_java_home)" || {
    echo "compose-driver: needs a JDK 21+ for the server JVM; set COMPOSE_DRIVER_JAVA_HOME" >&2
    exit 1
  }

  local quals
  quals="$(qualifiers "$device")"
  if [[ "$night" == true ]]; then
    # Android orders the night qualifier after orientation and before density.
    if [[ "$quals" =~ -(port|land)- ]]; then quals="${quals/-${BASH_REMATCH[1]}-/-${BASH_REMATCH[1]}-night-}"; else quals="$quals-night"; fi
  fi
  mkdir -p "$OUT/screenshots"
  echo "$port_number" > "$PORT_FILE"

  # setsid gives the Gradle client its own process group, so stop can signal the whole tree.
  setsid "$ROOT/gradlew" -p "$ROOT" --console=plain ${COMPOSE_DRIVER_GRADLE_ARGS:-} \
    :app:testDebugUnitTest --rerun --tests "$SERVER_TEST" \
    "-Pcompose.driver.composable=$(fqn "$screen")" \
    "-PcomposeDriver.port=$port_number" \
    "-PcomposeDriver.qualifiers=$quals" \
    "-PcomposeDriver.theme=$theme" \
    "-PcomposeDriver.javaHome=$java_home" \
    > "$LOG" 2>&1 < /dev/null &
  echo $! > "$PID_FILE"

  local timeout="${COMPOSE_DRIVER_START_TIMEOUT:-900}" waited=0
  echo "compose-driver: building and starting $(fqn "$screen") ($quals, theme $theme) on port $port_number; log: $LOG"
  until answering; do
    if ! alive; then
      echo "compose-driver: server exited before answering; last log lines:" >&2
      tail -n 40 "$LOG" >&2
      rm -f "$PID_FILE"
      exit 1
    fi
    if (( waited >= timeout )); then
      echo "compose-driver: no answer after ${timeout}s; see $LOG" >&2
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  # The first request after start waits for the first composition.
  call waitForIdle >/dev/null
  echo "compose-driver: ready at $(base) after ${waited}s"
}

stop() {
  local port_number
  port_number="$(port)"
  if [[ -f "$PID_FILE" ]]; then
    kill -TERM -- "-$(cat "$PID_FILE")" 2>/dev/null || kill -TERM "$(cat "$PID_FILE")" 2>/dev/null || true
  fi
  # A Gradle daemon cancels the build when its client goes away, which ends the test JVM.
  local i
  for i in $(seq 1 15); do
    answering || break
    sleep 1
  done
  if answering; then
    # Last resort: the process listening on the port is the Robolectric test JVM.
    local pids
    pids="$(ss -ltnpH "sport = :$port_number" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u)"
    [[ -n "$pids" ]] && kill $pids 2>/dev/null || true
  fi
  rm -f "$PID_FILE" "$PORT_FILE"
  echo "compose-driver: stopped"
}

screenshot() {
  local name=screen
  if [[ $# -gt 0 && "$1" != *=* ]]; then
    name="$1"
    shift
  fi
  local args=()
  mapfile -d '' args < <(query_args "$@")
  mkdir -p "$OUT/screenshots"
  fetch "$OUT/screenshots/$name.png" screenshot "${args[@]}"
}

tree() {
  mkdir -p "$OUT"
  call printTree "$@" | tee "$OUT/tree.txt"
}

command="${1:-help}"
[[ $# -gt 0 ]] && shift
case "$command" in
  start) start "$@" ;;
  stop) stop ;;
  status) curl -fsS "http://127.0.0.1:$(port)/status" 2>/dev/null && echo || { echo "compose-driver: not running on port $(port)"; exit 1; } ;;
  list) grep -B1 -E '^fun [A-Z]' "$SCREENS_FILE" | grep -E '^(/\*\*|fun )' | sed -E 's/^fun ([A-Za-z]+)\(\).*/  \1/; s/^\/\*\* ?/    /; s/ ?\*\/$//' ;;
  log) tail -n "${1:-60}" "$LOG" ;;
  tree) tree "$@" ;;
  screenshot) screenshot "$@" ;;
  click | longClick | doubleClick | scrollTo | textClearance | navigateBack | waitForIdle | waitForNode)
    call "$command" "$@" ;;
  textInput | textReplacement)
    [[ $# -gt 0 ]] || { echo "compose-driver: $command needs the text" >&2; exit 2; }
    text="$1"
    shift
    call "$command" "raw:text=$text" "$@" ;;
  swipe)
    [[ $# -gt 0 ]] || { echo "compose-driver: swipe needs UP, DOWN, LEFT or RIGHT" >&2; exit 2; }
    direction="$1"
    shift
    call swipe "direction=$direction" "$@" ;;
  key)
    [[ $# -gt 0 ]] || { echo "compose-driver: key needs a Key name, e.g. Enter" >&2; exit 2; }
    key_name="$1"
    shift
    call keyEvent "key=$key_name" "$@" ;;
  reset)
    if [[ $# -gt 0 ]]; then call reset "composable=$(fqn "$1")"; else call reset; fi ;;
  get)
    [[ $# -gt 0 ]] || { echo "compose-driver: get needs an endpoint" >&2; exit 2; }
    endpoint="$1"
    shift
    call "$endpoint" "$@" ;;
  help | -h | --help) usage ;;
  *) echo "compose-driver: unknown command '$command'" >&2; usage >&2; exit 2 ;;
esac
