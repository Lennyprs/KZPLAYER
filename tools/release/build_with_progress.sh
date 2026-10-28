#!/usr/bin/env bash
# CI visibility only: retain Gradle's exit status, never print secrets or environment.
set -uo pipefail
DIAG="${RUNNER_TEMP:-/tmp}/kz-gradle-diagnostics"
mkdir -p "$DIAG"
LOG="$DIAG/build.log"
: > "$LOG"
INTERVAL="${KZ_HEARTBEAT_SECONDS:-60}"
LIMIT="${KZ_BUILD_LIMIT:-20m}"
monitor() {
  local started now last_size size last_change dumped pid
  started=$(date +%s); last_change=$started; last_size=0; dumped=0
  while true; do
    sleep "$INTERVAL"
    now=$(date +%s)
    size=$(wc -c < "$LOG")
    if [ "$size" != "$last_size" ]; then last_change=$now; last_size=$size; dumped=0; fi
    echo "[Suivi CI] $((now-started)) s ecoulees ; journal ${size} octets ; sans nouvelle sortie depuis $((now-last_change)) s."
    if [ $((now-last_change)) -ge 180 ] && [ "$dumped" = 0 ]; then
      dumped=1
      echo "[Diagnostic CI] Pas de nouvelle sortie depuis 3 minutes : capture des piles Java (sans variables ni secrets)."
      if command -v jps >/dev/null 2>&1 && command -v jcmd >/dev/null 2>&1; then
        for pid in $(jps -l | awk '/GradleDaemon|GradleWrapperMain/ {print $1}'); do
          timeout 15s jcmd "$pid" Thread.print > "$DIAG/threads-${pid}-${now}.txt" 2>&1 || true
        done
      fi
    fi
  done
}
monitor &
MONITOR_PID=$!
cleanup() { kill "$MONITOR_PID" 2>/dev/null || true; wait "$MONITOR_PID" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "[CI] Gradle detaille ; limite ${LIMIT}. Aucun changement des taches, de la signature ou de la version."
timeout --signal=TERM --kill-after=30s "$LIMIT" \
  ./gradlew clean :app:testReleaseUnitTest :app:assembleRelease \
  --no-daemon --stacktrace --info --console=plain 2>&1 | tee "$LOG"
STATUSES=("${PIPESTATUS[@]}")
RESULT=${STATUSES[0]}
if [ "$RESULT" -eq 0 ] && [ "${STATUSES[1]}" -ne 0 ]; then RESULT=${STATUSES[1]}; fi
if [ "$RESULT" -eq 124 ]; then
  echo "[CI] Limite ${LIMIT} atteinte. La compilation n'est pas validee ; consulter le journal et les piles Java dans l'artefact de diagnostic."
fi
exit "$RESULT"
