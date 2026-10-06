#!/usr/bin/env bash
#
# Starts the Customer Trace backend on the host using the values in .env.
#
# Why not `source .env`? DB_URL contains `&` and `?`, which would be parsed as a background
# command. Each line is therefore exported as a single quoted argument instead.
#
# Spring Boot binds all interfaces by default, so this is reachable from a phone on the LAN at
# http://<mac-ip>:8080 without exposing anything to the public internet.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="$ROOT/.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "missing .env — run: python3 scripts/setup-local-env.py" >&2
  exit 1
fi

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) continue ;;
  esac
  key="${line%%=*}"
  value="${line#*=}"
  # Single quoted argument: &, ?, spaces and any secret survive untouched.
  export "$key=$value"
done < "$ENV_FILE"

[ -n "${JWT_SECRET:-}" ] || { echo "JWT_SECRET is empty in .env" >&2; exit 1; }

JAR="$ROOT/backend/target/backend-1.0.0.jar"
[ -f "$JAR" ] || { echo "missing $JAR — run: mvn -f backend/pom.xml clean package" >&2; exit 1; }

echo "starting backend on port ${SERVER_PORT:-8080}"
exec java -jar "$JAR"