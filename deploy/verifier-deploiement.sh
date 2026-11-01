#!/usr/bin/env bash
# =============================================================================
# verifier-deploiement.sh — proves that a Signed Orders deployment answers
# =============================================================================
#
# USAGE
#   bash deploy/verifier-deploiement.sh                    # the local VM
#   bash deploy/verifier-deploiement.sh http://203.0.113.10:8000   # from anywhere
#
# WHAT IT CHECKS (nothing else — it never writes on the server)
#   1. GET  /health          -> 200, status "ok", the PUBLIC key is served
#   2. GET  /policy          -> 200, the rule table is not empty
#   3. GET  /journal/verify  -> 200, the hash chain is intact
#   4. GET  /orders          -> 200
#   5. POST /orders without a bearer token -> 401 (the door is closed)
#   6. on the VM itself: the service is active, Docker is there, the env file
#      is 0600 and the journal is writable
#
# It exits 0 when every check passes, 1 otherwise.
# =============================================================================
set -u

URL="${1:-http://127.0.0.1:8000}"
URL="${URL%/}"
FAILED=0

say()  { printf '\n%s\n' "$*"; }
pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; FAILED=1; }

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

say "Signed Orders — deployment check on $URL"

# -- 1. /health --------------------------------------------------------------
HEALTH="$(curl -s "$URL/health")"
if [ "$(code "$URL/health")" = "200" ] \
   && printf '%s' "$HEALTH" | grep -q '"status": *"ok"' \
   && printf '%s' "$HEALTH" | grep -q '"public_key"'; then
    pass "GET /health -> 200, status ok, the public key is served"
else
    fail "GET /health -> $(code "$URL/health") : $HEALTH"
fi
if printf '%s' "$HEALTH" | grep -qi 'private_key\|secret'; then
    fail "/health leaks something that looks like a private key"
else
    pass "no private key in /health"
fi

# -- 2. /policy --------------------------------------------------------------
RULES="$(curl -s "$URL/policy" | grep -o '"id"' | wc -l)"
if [ "$(code "$URL/policy")" = "200" ] && [ "$RULES" -ge 10 ]; then
    pass "GET /policy -> 200, $RULES named rules"
else
    fail "GET /policy -> $(code "$URL/policy"), $RULES rules found"
fi

# -- 3. /journal/verify ------------------------------------------------------
VERIFY="$(curl -s "$URL/journal/verify")"
if [ "$(code "$URL/journal/verify")" = "200" ] && printf '%s' "$VERIFY" | grep -q '"ok": *true'; then
    pass "GET /journal/verify -> 200, the hash chain is intact"
else
    fail "GET /journal/verify -> $(code "$URL/journal/verify") : $VERIFY"
fi

# -- 4. /orders --------------------------------------------------------------
if [ "$(code "$URL/orders")" = "200" ]; then
    pass "GET /orders -> 200"
else
    fail "GET /orders -> $(code "$URL/orders")"
fi

# -- 5. the door must be closed to an anonymous caller ------------------------
ANON="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/orders" \
        -H 'Content-Type: application/json' \
        -d '{"task_id":"probe","agent_id":"probe","action":"exec","target":"sandbox://probe:/x"}')"
if [ "$ANON" = "401" ]; then
    pass "POST /orders without a bearer token -> 401 (the door is closed)"
else
    fail "POST /orders without a bearer token -> $ANON (expected 401)"
fi

# -- 6. the checks that only make sense ON the VM ----------------------------
if [ -f /etc/systemd/system/signedorders.service ]; then
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet signedorders; then
        pass "systemd: signedorders.service is active"
    else
        fail "systemd: signedorders.service is NOT active"
    fi
    if command -v docker >/dev/null 2>&1; then
        pass "docker is installed (the sandbox backend)"
    else
        fail "docker is NOT installed"
    fi
    MODE="$(stat -c '%a' /etc/signedorders/env 2>/dev/null || echo '?')"
    if [ "$MODE" = "600" ]; then
        pass "/etc/signedorders/env is mode 0600"
    else
        fail "/etc/signedorders/env is mode $MODE (expected 600)"
    fi
    if [ -w /var/lib/signedorders ]; then
        pass "/var/lib/signedorders is writable (the journal)"
    else
        fail "/var/lib/signedorders is NOT writable"
    fi
fi

say ""
if [ "$FAILED" = "0" ]; then
    echo "RESULT: every check passed."
else
    echo "RESULT: at least one check failed."
fi
exit "$FAILED"
