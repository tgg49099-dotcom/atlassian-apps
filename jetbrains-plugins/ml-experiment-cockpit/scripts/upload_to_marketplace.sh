#!/usr/bin/env bash
# upload_to_marketplace.sh — Upload the pre-built ML Experiment Cockpit ZIP
# to JetBrains Marketplace.
#
# Usage:
#   export JETBRAINS_MARKETPLACE_TOKEN="perm:..."   # from plugins.jetbrains.com > My Tokens
#   ./scripts/upload_to_marketplace.sh <path-to-plugin.zip> [<channel>] [<isHidden>]
#
# Defaults:
#   channel = "" (stable)
#   isHidden = false
#
# Exit code:
#   0 = success (HTTP 200)
#   non-zero = upload failed (see the response body printed below)
#
# This script uses the public Marketplace Upload API documented at:
#   https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html

set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <plugin.zip> [<channel>] [<isHidden>]"
    echo ""
    echo "Examples:"
    echo "  $0 ./build/distributions/ml-experiment-cockpit-0.0.1.zip"
    echo "  $0 ./build/distributions/ml-experiment-cockpit-0.0.1.zip nightly true"
    echo ""
    echo "Environment variables required:"
    echo "  JETBRAINS_MARKETPLACE_TOKEN    Bearer token starting with 'perm:'"
    echo ""
    echo "Optional environment variables:"
    echo "  PLUGIN_ID                      Numeric plugin ID (from the Marketplace URL)"
    echo "                                 OR PLUGIN_XML_ID (the <id> in plugin.xml)."
    echo "                                 If neither is set, the script will try to read it"
    echo "                                 from the plugin.xml inside the ZIP archive."
    exit 1
fi

PLUGIN_ZIP="$1"
CHANNEL="${2:-}"
IS_HIDDEN="${3:-false}"

if [ -z "${JETBRAINS_MARKETPLACE_TOKEN:-}" ]; then
    echo "ERROR: JETBRAINS_MARKETPLACE_TOKEN environment variable is not set."
    echo "Generate one at https://plugins.jetbrains.com > Vendor Profile > My Tokens."
    exit 2
fi

if [ ! -f "${PLUGIN_ZIP}" ]; then
    echo "ERROR: plugin zip not found: ${PLUGIN_ZIP}"
    exit 3
fi

# Build curl multipart call.
CURL_ARGS=(
    --silent
    --show-error
    --write-out "\nHTTP_STATUS:%{http_code}\n"
    --header "Authorization: Bearer ${JETBRAINS_MARKETPLACE_TOKEN}"
    --form "file=@${PLUGIN_ZIP}"
)

# Determine pluginId / xmlId from arguments, env, or plugin.xml
if [ -n "${PLUGIN_ID:-}" ]; then
    CURL_ARGS+=(--form "pluginId=${PLUGIN_ID}")
    echo "Using PLUGIN_ID=${PLUGIN_ID} from environment."
elif [ -n "${PLUGIN_XML_ID:-}" ]; then
    CURL_ARGS+=(--form "xmlId=${PLUGIN_XML_ID}")
    echo "Using PLUGIN_XML_ID=${PLUGIN_XML_ID} from environment."
else
    # Try to extract plugin.xml from the ZIP and parse <id>.
    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT
    if unzip -q "${PLUGIN_ZIP}" -d "${TMP_DIR}"; then
        PLUGIN_XML=$(find "${TMP_DIR}" -name "plugin.xml" | head -n 1)
        if [ -z "${PLUGIN_XML}" ]; then
            # JetBrains Platform layout: plugin.xml lives inside the main JAR.
            PLUGIN_XML=$(find "${TMP_DIR}" -name "*.jar" -print -quit | while read -r jar; do
                if unzip -p "${jar}" META-INF/plugin.xml 2>/dev/null | head -n 0; then
                    TMP_PXML=$(mktemp)
                    unzip -p "${jar}" META-INF/plugin.xml > "${TMP_PXML}" 2>/dev/null && {
                        echo "${TMP_PXML}"
                        break
                    }
                fi
            done)
        fi
        if [ -n "${PLUGIN_XML}" ]; then
            EXTRACTED_ID=$(grep -oE '<id>[^<]+</id>' "${PLUGIN_XML}" | head -n 1 | sed -E 's|<id>(.*?)</id>|\1|')
            if [ -n "${EXTRACTED_ID}" ]; then
                CURL_ARGS+=(--form "xmlId=${EXTRACTED_ID}")
                echo "Auto-extracted xmlId=${EXTRACTED_ID} from plugin.xml."
            else
                echo "ERROR: could not extract <id> from ${PLUGIN_XML}"
                exit 4
            fi
        else
            echo "ERROR: no plugin.xml found inside ${PLUGIN_ZIP}"
            exit 5
        fi
    else
        echo "ERROR: could not unzip ${PLUGIN_ZIP}"
        exit 6
    fi
fi

if [ -n "${CHANNEL}" ]; then
    CURL_ARGS+=(--form "channel=${CHANNEL}")
    echo "Using channel=${CHANNEL}."
fi

if [ "${IS_HIDDEN}" = "true" ]; then
    CURL_ARGS+=(--form "isHidden=true")
    echo "isHidden=true (plugin will not appear in search results)."
fi

ENDPOINT="https://plugins.jetbrains.com/api/updates/upload"

echo ""
echo "Uploading ${PLUGIN_ZIP} to ${ENDPOINT} ..."
echo ""

OUTPUT=$(curl "${CURL_ARGS[@]}" "${ENDPOINT}")
HTTP_STATUS=$(echo "${OUTPUT}" | grep -oE 'HTTP_STATUS:[0-9]+' | sed 's/HTTP_STATUS://' || echo "0")
BODY=$(echo "${OUTPUT}" | sed '/HTTP_STATUS:/d')

echo "Response body:"
echo "${BODY}"
echo ""
echo "HTTP status: ${HTTP_STATUS}"

if [ "${HTTP_STATUS}" -ge 200 ] && [ "${HTTP_STATUS}" -lt 300 ]; then
    echo ""
    echo "Upload accepted. JetBrains will review and publish the plugin."
    exit 0
else
    echo ""
    echo "Upload failed (HTTP ${HTTP_STATUS}). See response body for error details."
    exit 10
fi