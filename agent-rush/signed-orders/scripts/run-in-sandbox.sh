#!/bin/sh
# =============================================================================
# run-in-sandbox.sh — the script that runs INSIDE the fresh sandbox.
#
# It is copied into the workspace of the task and is the entrypoint of the
# container:
#
#   docker run --rm --network=none --memory=256m --cpus=1 --read-only \
#     --cap-drop=ALL --security-opt=no-new-privileges --user=65534:65534 \
#     --tmpfs=/tmp:rw,noexec,nosuid,size=64m \
#     --volume <workspace>:/work:rw --workdir=/work \
#     debian:bookworm-slim /work/run-in-sandbox.sh <command...>
#
# Rules of this script (they are checked by tests/test_sandbox.py):
#   * it never reaches the network — there is no curl, no wget, no nc here;
#   * it inherits no environment variable from the control plane — the
#     container is started without a single --env;
#   * it writes only inside the mounted workspace (/work), which is the only
#     writable place of the sandbox;
#   * it always prints exactly one JSON line on stdout, so the executor can
#     journal the result without parsing anything else.
#
# POSIX sh only: the image is debian:bookworm-slim, and the script must run
# before anything else is installed.
# =============================================================================
set -eu

WORKDIR="${SIGNEDORDERS_WORKDIR:-/work}"
LOGDIR="$WORKDIR/.signedorders"
TIMEOUT_SECONDS="${SIGNEDORDERS_TIMEOUT_SECONDS:-30}"

mkdir -p "$LOGDIR"

if [ "$#" -eq 0 ]; then
    printf '{"kind":"sandbox_result","exit_code":64,"duration_seconds":0}\n'
    exit 64
fi

START=$(date +%s)

# The command runs with no privilege, no network and a wall-clock timeout.
# `timeout` sends SIGKILL, so an infinite loop cannot survive it.
set +e
timeout --signal=KILL "${TIMEOUT_SECONDS}" "$@" >"$LOGDIR/stdout.log" 2>"$LOGDIR/stderr.log"
EXIT_CODE=$?
set -e

END=$(date +%s)
DURATION=$((END - START))

# One JSON line, on stdout, for the executor.  The command itself is never
# interpolated here: the executor reads it from the journal.
printf '{"kind":"sandbox_result","exit_code":%d,"duration_seconds":%d}\n' \
    "$EXIT_CODE" "$DURATION"

exit "$EXIT_CODE"
