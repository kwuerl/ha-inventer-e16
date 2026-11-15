#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Korbinian Würl
#
# Starts a throwaway Home Assistant for HomeAssistantIntegrationTest: the integration of this
# repository (real config flow, set-up stub without polling), a helper component that simulates
# DHCP discovery and re-authentication, and the stand-in controller on 127.0.0.1:47820.
#
#   pip install homeassistant              # with the Python version Home Assistant requires
#   companion-app/tools/ha-test/start.sh [config-dir]
#
# Prints HA_TEST_URL, HA_TEST_USER and HA_TEST_PASSWORD for the test; in GitHub Actions they go
# to $GITHUB_ENV. Home Assistant and the stand-in keep running; logs and PIDs are in the config
# directory (stop them with: kill $(cat <config-dir>/*.pid)).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
config=${1:-$(mktemp -d)}
port=${HA_TEST_PORT:-18123}
url="http://127.0.0.1:$port"
user=test-admin
password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')

mkdir -p "$config/custom_components"
rm -rf "$config/custom_components/inventer_e16" "$config/custom_components/test_helpers"
cp -R "$repo/custom_components/inventer_e16" "$config/custom_components/"
cp "$here/inventer_e16_stub.py" "$config/custom_components/inventer_e16/__init__.py"
cp -R "$here/test_helpers" "$config/custom_components/"
sed "s/@PORT@/$port/" "$here/configuration.yaml" > "$config/configuration.yaml"

python3 "$here/../mock_e16.py" --host 127.0.0.1 --no-mdsdp > "$config/mock_e16.log" 2>&1 &
echo $! > "$config/mock_e16.pid"
"${HASS:-hass}" --config "$config" > "$config/hass.log" 2>&1 &
echo $! > "$config/hass.pid"

for _ in $(seq 240); do
  if curl -sf "$url/api/onboarding" > /dev/null 2>&1; then break; fi
  if ! kill -0 "$(cat "$config/hass.pid")" 2> /dev/null; then break; fi
  sleep 1
done
if ! curl -sf "$url/api/onboarding" > /dev/null 2>&1; then
  echo "Home Assistant did not start:" >&2
  tail -n 40 "$config/hass.log" >&2
  exit 1
fi

# The owner account from the first onboarding step; the test signs in with it like a user would.
curl -sf -X POST "$url/api/onboarding/users" -H 'Content-Type: application/json' -d "$(python3 -c '
import json, sys
print(json.dumps({"client_id": sys.argv[1] + "/", "name": "Test admin", "username": sys.argv[2],
                  "password": sys.argv[3], "language": "en"}))' "$url" "$user" "$password")" > /dev/null

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "::add-mask::$password"
  printf 'HA_TEST_URL=%s\nHA_TEST_USER=%s\nHA_TEST_PASSWORD=%s\n' "$url" "$user" "$password" >> "$GITHUB_ENV"
fi
echo "Home Assistant is running in $config"
printf 'export HA_TEST_URL=%s HA_TEST_USER=%s HA_TEST_PASSWORD=%s\n' "$url" "$user" "$password"
