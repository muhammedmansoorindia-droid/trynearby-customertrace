#!/usr/bin/env python3
"""Writes the local .env for phone testing.

Idempotent and non-destructive:
  * an existing JWT_SECRET is preserved (changing it would invalidate every issued token)
  * existing DB credentials are preserved (the running MySQL volume was created with them)
  * the LAN origin and API base URL are derived from the current Wi-Fi address

Never prints secrets.
"""
import os
import re
import secrets
import subprocess
import sys

# The project root is the directory containing this script's parent (repo/scripts/this.py).
ENV_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env"
)


def lan_ip() -> str:
    """The MacBook's address on the current Wi-Fi/Ethernet network."""
    for iface in ("en0", "en1", "en2"):
        out = subprocess.run(["ipconfig", "getifaddr", iface],
                             capture_output=True, text=True).stdout.strip()
        if out:
            return out
    return ""


def existing() -> dict:
    """Parses any current .env so nothing valid is lost."""
    values = {}
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                values[key.strip()] = val.strip()
    return values


def is_usable_secret(value: str) -> bool:
    """A real secret: long enough and not one of the documented placeholders."""
    if not value or len(value) < 32:
        return False
    return "replace" not in value.lower() and "placeholder" not in value.lower()


current = existing()
ip = lan_ip()

# Preserve a good secret; otherwise mint a fresh 48-byte one. Never logged.
jwt = current.get("JWT_SECRET", "")
if not is_usable_secret(jwt):
    jwt = secrets.token_urlsafe(48)

# The MySQL volume already exists with these credentials; reusing them is what keeps the
# existing data reachable. They are only used if the keys are absent from .env.
db_user = current.get("DB_USERNAME", "ct")
db_pass = current.get("DB_PASSWORD", "ctpw")
db_root = current.get("DB_ROOT_PASSWORD", "rootpw")

# Origins for CORS: loopback plus this machine's LAN address, so the phone is allowed and
# localhost still works. Comma-separated, never "*" (credentials are enabled).
origins = ["http://localhost:5173"]
if ip:
    origins.append(f"http://{ip}:5173")
origins_value = ",".join(dict.fromkeys(origins))

# What the PHONE's browser must call. It cannot resolve "localhost" (that would be the phone),
# so the API base has to be the MacBook's LAN address.
api_base = f"http://{ip}:8080" if ip else "http://localhost:8080"

lines = [
    "# Customer Trace - local development environment.",
    "# Generated locally; git-ignored. Never commit this file.",
    f"# LAN address detected at generation time: {ip or 'none (not on a network?)'}",
    "",
    "# ---- Database (matches the existing ct_mysql_data volume) -------------------",
    f"DB_USERNAME={db_user}",
    f"DB_PASSWORD={db_pass}",
    f"DB_ROOT_PASSWORD={db_root}",
    "DB_URL=jdbc:mysql://localhost:3399/customertrace"
    "?createDatabaseIfNotExist=true&useSSL=false"
    "&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    "DB_POOL_SIZE=10",
    "",
    "# ---- Signing key (generated locally; do not share) --------------------------",
    f"JWT_SECRET={jwt}",
    "JWT_ACCESS_TTL=PT15M",
    "JWT_REFRESH_TTL=P7D",
    "JWT_ISSUER=customer-trace-api",
    "",
    "# ---- Cookies ---------------------------------------------------------------",
    "REFRESH_COOKIE_SECURE=false",
    "REFRESH_COOKIE_SAMESITE=Lax",
    "",
    "# ---- CORS: loopback + this MacBook's LAN origin only ------------------------",
    "CORS_ALLOWED_ORIGINS=" + origins_value,
    "",
    "# ---- Development accounts --------------------------------------------------",
    "BOOTSTRAP_ENABLED=true",
    "DEV_ACCOUNT_PASSWORD=" + current.get("DEV_ACCOUNT_PASSWORD", "DevOnly!Pass123"),
    "",
    "# ---- Misc ------------------------------------------------------------------",
    "SERVER_PORT=8080",
    "LOG_LEVEL=INFO",
    "",
    "# ---- Frontend: what the phone's browser calls ------------------------------",
    f"VITE_API_BASE_URL={api_base}",
    "",
]

with open(ENV_PATH, "w") as fh:
    fh.write("\n".join(lines))
os.chmod(ENV_PATH, 0o600)

# Report only non-secret facts.
print("wrote .env (mode 600)")
print(f"  LAN IP detected        : {ip or 'NONE'}")
print(f"  CORS_ALLOWED_ORIGINS   : {origins_value}")
print(f"  VITE_API_BASE_URL      : {api_base}")
print(f"  JWT_SECRET             : {'preserved' if is_usable_secret(current.get('JWT_SECRET', '')) else 'generated'}")
print(f"  DEV_ACCOUNT_PASSWORD   : {'preserved' if 'DEV_ACCOUNT_PASSWORD' in current else 'set to the documented default'}")
sys.exit(0 if ip else 2)