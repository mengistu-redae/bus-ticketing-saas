#!/bin/bash
# Usage: ./get-token.sh <username> <password>
# e.g. ./get-token.sh demo-agent changeme123
set -e
USERNAME="$1"
PASSWORD="$2"
CLIENT_UUID="4cac8c0e-4654-4a9a-88e3-e7f3b2eb3d85"
CLIENT_SECRET="27TgTAErOd6YJL38NFBeuQ8f5xG2KWyg"

ADMIN_TOKEN=$(curl -s -X POST "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" -d "username=admin" -d "password=admin" -d "grant_type=password" \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

ENABLE_CFG=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/admin/realms/bustix/clients/$CLIENT_UUID" \
  | python -c "import sys,json;c=json.load(sys.stdin);c['directAccessGrantsEnabled']=True;print(json.dumps(c))")
curl -s -o /dev/null -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  --data "$ENABLE_CFG" "http://localhost:8080/admin/realms/bustix/clients/$CLIENT_UUID"

TOKEN=$(curl -s -X POST "http://localhost:8080/realms/bustix/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=bus-ticketing-bff" -d "client_secret=$CLIENT_SECRET" \
  -d "username=$USERNAME" -d "password=$PASSWORD" -d "grant_type=password" -d "scope=openid organization" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")

DISABLE_CFG=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/admin/realms/bustix/clients/$CLIENT_UUID" \
  | python -c "import sys,json;c=json.load(sys.stdin);c['directAccessGrantsEnabled']=False;print(json.dumps(c))")
curl -s -o /dev/null -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  --data "$DISABLE_CFG" "http://localhost:8080/admin/realms/bustix/clients/$CLIENT_UUID"

echo "$TOKEN"
