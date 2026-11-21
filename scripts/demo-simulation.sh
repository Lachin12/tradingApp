#!/usr/bin/env bash
# Local, opt-in demo. This script never enables simulation or changes server config.
set -euo pipefail

for required in curl jq; do
    if ! command -v "$required" >/dev/null 2>&1; then
        printf 'Required command missing: %s\n' "$required" >&2
        exit 1
    fi
done

APP_BASE_URL="${OMS_BASE_URL:-http://localhost:8080}"
APP_BASE_URL="${APP_BASE_URL%/}"
SCENARIO_ID='00000000-0000-0000-0000-000000000100'
SUBMISSION_KEY='portfolio-simulator-v1'
RESPONSE_FILE="$(mktemp)"
trap 'rm -f "$RESPONSE_FILE"' EXIT

request() {
    local method="$1" path="$2" payload="${3:-}" key="${4:-}" status
    local -a options=(--silent --show-error --connect-timeout 5 --max-time 30
        --request "$method" --output "$RESPONSE_FILE" --write-out '%{http_code}')
    if [[ -n "$payload" ]]; then
        options+=(--header 'Content-Type: application/json' --data "$payload")
    fi
    if [[ -n "$key" ]]; then
        options+=(--header "Idempotency-Key: $key")
    fi
    status="$(curl "${options[@]}" "$APP_BASE_URL$path")"
    case "$status" in
        200|201) cat "$RESPONSE_FILE" ;;
        *)
            printf 'Request failed: %s %s -> HTTP %s\n' "$method" "$path" "$status" >&2
            cat "$RESPONSE_FILE" >&2
            printf '\n' >&2
            if [[ "$path" == /api/v1/simulation/* && "$status" == 404 ]]; then
                printf 'Enable OMS_SIMULATION_ENABLED=true, restart the app, and confirm the order exists.\n' >&2
            fi
            exit 1
            ;;
    esac
}

submission='{"accountId":"00000000-0000-0000-0000-000000000001","instrumentId":"00000000-0000-0000-0000-000000000002","side":"BUY","type":"MARKET","quantity":"10"}'
order="$(request POST /api/v1/orders "$submission" "$SUBMISSION_KEY")"
ORDER_ID="$(jq -er '.id' <<<"$order")"
printf 'Demo order: %s (%s)\n' "$ORDER_ID" "$(jq -r '.status' <<<"$order")"

step_payload() {
    # Prices and quantities remain decimal strings; no floating-point arithmetic.
    jq -nc --arg scenario "$SCENARIO_ID" --argjson step "$1" \
        --argjson version "$2" --arg quantity "$3" --arg source_time "$4" \
        '{scenarioId:$scenario,step:$step,expectedVersion:$version,
          quantity:$quantity,price:"100.25",executedAt:$source_time}'
}

first_payload="$(step_payload 0 0 4 '2026-01-01T09:00:00Z')"
first="$(request POST "/api/v1/simulation/orders/$ORDER_ID/steps" "$first_payload")"
jq -e '.order.status == "PARTIALLY_FILLED" or .order.status == "FILLED"' <<<"$first" >/dev/null
printf 'Step 0: %s (replay=%s)\n' "$(jq -r '.order.status' <<<"$first")" "$(jq -r '.replay' <<<"$first")"

second_payload="$(step_payload 1 1 6 '2026-01-01T09:00:01Z')"
second="$(request POST "/api/v1/simulation/orders/$ORDER_ID/steps" "$second_payload")"
jq -e '.order.status == "FILLED" and .order.filledQuantity == "10.00000000" and .order.version == 2' <<<"$second" >/dev/null
printf 'Step 1: FILLED (replay=%s)\n' "$(jq -r '.replay' <<<"$second")"

# The original version is stale now. Exact replay must return the current snapshot.
replayed="$(request POST "/api/v1/simulation/orders/$ORDER_ID/steps" "$first_payload")"
FIRST_EXECUTION_ID="$(jq -er '.executionId' <<<"$first")"
jq -e --arg id "$FIRST_EXECUTION_ID" \
    '.replay == true and .executionId == $id and .order.status == "FILLED" and .order.version == 2' \
    <<<"$replayed" >/dev/null

events="$(request GET "/api/v1/orders/$ORDER_ID/events")"
jq -e 'length == 4 and ([.[] | select(.type == "EXECUTED")] | length) == 2' <<<"$events" >/dev/null
printf 'Exact step-0 replay returned current FILLED state; history still has two executions.\n'
printf 'Rerun this script with the same database to replay the same order and execution IDs.\n'
