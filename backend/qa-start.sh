#!/usr/bin/env bash
# QA launcher: starts the packaged backend against the dev schema.
# The JWT secret and dev password are generated per run and never committed.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export JWT_SECRET="$(openssl rand -base64 48)"
export DEV_ACCOUNT_PASSWORD="QaRun$(date +%H%M%S)Pass1!"
export BOOTSTRAP_ENABLED=true

# Hand the generated password to the test driver via a 600 file, never via git.
umask 077
printf '%s' "$DEV_ACCOUNT_PASSWORD" > /tmp/qa-pass.txt

exec java -jar target/backend-1.0.0.jar