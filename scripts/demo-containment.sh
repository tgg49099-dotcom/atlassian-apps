#!/usr/bin/env bash
# =============================================================================
# demo-containment.sh — the "containment moment", reproducible in one command
# =============================================================================
#
# WHAT THIS SCRIPT DOES
#   It starts the control plane on a free port of the local machine, with a
#   throw-away journal in a temporary folder and throw-away bearer keys, then
#   it replays the exact scenario the demo video shows:
#
#     1. a legitimate order           -> executed automatically (low risk)
#     2. a destructive order          -> queued for a human, then REFUSED
#     3. a forbidden order            -> refused, and no human can unlock it
#     4. the journal                  -> verified live, then verified OFFLINE
#
#   It touches nothing outside its own temporary folder, opens no outbound
#   connection other than to 127.0.0.1, and deletes the temporary folder at
#   the end.
#
# HOW TO RUN IT
#   git clone https://github.com/tgg49099-dotcom/atlassian-apps.git
#   cd atlassian-apps/agent-rush/signed-orders
#   bash scripts/demo-containment.sh
#
#   It needs Python 3.10+ and curl. Nothing else — the core has no dependency.
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required (Ubuntu 24.04 and macOS both have it)." >&2
    exit 2
fi

PYTHON="${PYTHON:-python3}"
TMP="$(mktemp -d)"
PORT="${PORT:-8757}"
JOURNAL="$TMP/journal.jsonl"
AGENT_KEY="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
OPERATOR_KEY="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    rm -rf "$TMP"
}
trap cleanup EXIT

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

post_order() {
    # $1 = JSON body ; prints the HTTP code, then the decision
    curl -s -o "$TMP/decision.json" -w '%{http_code}' \
        -X POST "http://127.0.0.1:$PORT/orders" \
        -H "Authorization: Bearer $AGENT_KEY" \
        -H 'Content-Type: application/json' \
        -d "$1"
}

show_decision() {
    "$PYTHON" - "$TMP/decision.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    d = json.load(handle)
print("    verdict :", d.get("verdict"))
print("    rule    :", d.get("rule_id"), "(risk:", d.get("risk"), ")")
print("    reason  :", d.get("reason"))
print("    order id:", d.get("order", {}).get("id"))
PY
}

say "0. Starting the control plane on 127.0.0.1:$PORT (throw-away keys, throw-away journal)"
SIGNEDORDERS_AGENT_KEY="$AGENT_KEY" \
SIGNEDORDERS_OPERATOR_KEY="$OPERATOR_KEY" \
    "$PYTHON" -m signedorders serve --host 127.0.0.1 --port "$PORT" --journal "$JOURNAL" \
    >"$TMP/serve.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 40); do
    if curl -s -o /dev/null "http://127.0.0.1:$PORT/health"; then break; fi
    sleep 0.25
done
if ! curl -s -o /dev/null "http://127.0.0.1:$PORT/health"; then
    echo "the server did not start:" >&2
    cat "$TMP/serve.log" >&2
    exit 1
fi
echo "    health  : $(curl -s "http://127.0.0.1:$PORT/health")"

say "1. A legitimate order — the work itself (low risk, executed automatically)"
CODE="$(post_order '{"task_id":"demo","agent_id":"agent-demo","action":"exec","target":"sandbox://sb-demo:/app/run.sh","args":{"command":"pytest -q"}}')"
echo "    HTTP $CODE"
show_decision

say "2. THE CONTAINMENT MOMENT — a destructive order asked by a hostile instruction"
CODE="$(post_order '{"task_id":"demo","agent_id":"agent-demo","action":"delete","target":"sandbox://sb-demo:/srv/data","args":{"command":"rm -rf /srv/data"}}')"
echo "    HTTP $CODE"
show_decision
ORDER_ID="$("$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1]))['order']['id'])" "$TMP/decision.json")"

say "3. The human refuses it — a signed refusal, on the record"
CODE="$(curl -s -o "$TMP/decision.json" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$PORT/orders/$ORDER_ID/reject" \
    -H "Authorization: Bearer $OPERATOR_KEY" \
    -H 'Content-Type: application/json' \
    -d '{"approver":"operator","reason":"this order would destroy the demo data"}')"
echo "    HTTP $CODE"
show_decision

say "4. A forbidden order — refused, and NO human approval can ever unlock it"
CODE="$(post_order '{"task_id":"demo","agent_id":"agent-demo","action":"read","target":"host:///etc/app/secret"}')"
echo "    HTTP $CODE  (403 = refused)"
show_decision

say "5. The journal — verified live, then verified OFFLINE with no secret"
echo "    live    : $(curl -s "http://127.0.0.1:$PORT/journal/verify")"
kill "$SERVER_PID" 2>/dev/null
wait "$SERVER_PID" 2>/dev/null
SERVER_PID=""
echo "    offline : $("$PYTHON" -m signedorders verify "$JOURNAL")"
echo "    exit code = $?"

say "DONE — the blast radius was one order that never ran."
