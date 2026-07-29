#!/bin/sh
set -eu
umask 077

BASE=${UAT_BASE_URL:-http://127.0.0.1:6090}
UAT_ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
ARTIFACT_DIR=${UAT_ARTIFACT_DIR:-/var/log/huicui-uat}

fail() {
  echo "uat verify: FAIL: $*" >&2
  exit 1
}

curl_ok() {
  curl --fail-with-body --silent --show-error --max-time 20 "$@"
}

curl_status() {
  curl --silent --show-error --max-time 20 "$@"
}

if [ "${UAT_DEV_PASSWORD+x}" = x ]; then
  DEV_PASSWORD=$UAT_DEV_PASSWORD
else
  [ -f "$UAT_ENV_FILE" ] || fail "environment file not found: $UAT_ENV_FILE"
  DEV_PASSWORD=$(awk '
    index($0, "UAT_DEV_PASSWORD=") == 1 {
      sub(/^UAT_DEV_PASSWORD=/, "")
      print
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$UAT_ENV_FILE") || fail "UAT_DEV_PASSWORD is missing from $UAT_ENV_FILE"
fi
[ -n "$DEV_PASSWORD" ] || fail 'UAT_DEV_PASSWORD must not be empty'

mkdir -p "$ARTIFACT_DIR"

home_status=$(curl_ok -o "$ARTIFACT_DIR/home.html" -w '%{http_code}' "$BASE/")
[ "$home_status" = 200 ] || fail "home returned HTTP $home_status, expected 200"

health_status=$(curl_ok -o "$ARTIFACT_DIR/health.json" -w '%{http_code}' "$BASE/v1/actuator/health")
[ "$health_status" = 200 ] || fail "health returned HTTP $health_status, expected 200"
python3 -I - "$ARTIFACT_DIR/health.json" <<'PY' || fail 'health status is not UP'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    health = json.load(stream)
assert health.get("status") == "UP", health
PY

unauth_status=$(curl_status -o "$ARTIFACT_DIR/unauthenticated-me.json" -w '%{http_code}' "$BASE/v1/me")
[ "$unauth_status" = 401 ] || fail "unauthenticated /v1/me returned HTTP $unauth_status, expected 401"

login() {
  UAT_LOGIN_USERNAME=$1 UAT_LOGIN_PASSWORD=$DEV_PASSWORD \
    python3 -I -c '
import json
import os
print(json.dumps({
    "mode": "password",
    "username": os.environ["UAT_LOGIN_USERNAME"],
    "password": os.environ["UAT_LOGIN_PASSWORD"],
}))
' | curl_ok \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$BASE/v1/auth/login"
}

extract_token() {
  python3 -I -c '
import json
import sys
response = json.load(sys.stdin)
token = response.get("token")
if not isinstance(token, str) or not token:
    raise SystemExit("login response did not contain a token")
print(token)
'
}

sa_token=
for account in admin plat_se cuihu_pl cuihu_pc jx_vl jx_co1
do
  login_response=$(login "$account") || fail "login failed for $account"
  token=$(printf '%s' "$login_response" | extract_token) || fail "login returned no token for $account"
  if [ "$account" = admin ]; then
    sa_token=$token
  fi
done
[ -n "$sa_token" ] || fail 'SA token was not captured'

auth_config=$(mktemp "$ARTIFACT_DIR/curl-auth.XXXXXX")
trap 'rm -f "$auth_config"' EXIT HUP INT TERM
printf 'header = "Authorization: Bearer %s"\n' "$sa_token" >"$auth_config"
sa_token=

curl_ok \
  --config "$auth_config" \
  -o "$ARTIFACT_DIR/me.json" \
  "$BASE/v1/me"

platform_org_id=$(python3 -I - "$ARTIFACT_DIR/me.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    me = json.load(stream)
org_id = me.get("org", {}).get("id")
if not isinstance(org_id, str) or not org_id:
    raise SystemExit("SA /v1/me did not contain org.id")
print(org_id)
PY
) || fail 'could not read the platform organization id'

curl_ok \
  --config "$auth_config" \
  -o "$ARTIFACT_DIR/orgs.json" \
  "$BASE/v1/orgs?page=1&size=50"
curl_ok \
  --config "$auth_config" \
  -o "$ARTIFACT_DIR/members.json" \
  "$BASE/v1/members?orgId=$platform_org_id&page=1&size=50"

python3 -I - "$ARTIFACT_DIR/orgs.json" "$ARTIFACT_DIR/members.json" <<'PY' || fail 'seed data contract failed'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    orgs = json.load(stream)["items"]
with open(sys.argv[2], encoding="utf-8") as stream:
    members = json.load(stream)["items"]

cuihu = next((org for org in orgs if org.get("name") == "翠湖物业"), None)
assert cuihu is not None, "翠湖物业 is missing"
assert cuihu.get("ownerUsername") == "cuihu_pl", cuihu
assert cuihu.get("ownerPhone") == "13900000001", cuihu

by_username = {member.get("username"): member for member in members}
for username, phone, role in (
    ("admin", "13800000000", "SA"),
    ("plat_se", "13800000001", "SE"),
):
    member = by_username.get(username)
    assert member is not None, f"{username} is missing"
    assert member.get("phone") == phone, member
    assert member.get("role") == role, member

assert all(member.get("role") in {"SA", "SE"} for member in members), members
PY

echo "uat verify: PASS ($BASE)"
