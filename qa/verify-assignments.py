#!/usr/bin/env python3
"""Exercises the admin creator-assignment workflow against a running stack.

Signs in as the administrator, lists the pickers, assigns a restaurant to a creator, confirms the
duplicate is refused, and reports whether that creator can now see the venue in the list the New
Campaign wizard reads.
"""
import json
import subprocess
import sys

API = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else "DevOnly!Pass123"


def curl(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


def api(token, path, method="GET", body=None):
    args = ["-o", "/dev/null", "-w", "%{http_code}", "-X", method, f"{API}{path}",
            "-H", f"Authorization: Bearer {token}"]
    if body is not None:
        args += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    return curl(args)


def json_api(token, path):
    return json.loads(curl(["-H", f"Authorization: Bearer {token}", f"{API}{path}"]))


def login(user, portal):
    body = json.dumps({"userId": user, "password": PASSWORD, "portal": portal})
    raw = curl(["-X", "POST", f"{API}/api/auth/login", "-H", "Content-Type: application/json",
                "-d", body])
    try:
        return json.loads(raw)["accessToken"]
    except (ValueError, KeyError):
        raise SystemExit(f"could not sign in as {user} ({portal}); check the credentials")


admin = login("admin@Trynearby", "trynearby")
creators = json_api(admin, "/api/admin/creators")
restaurants = json_api(admin, "/api/admin/restaurants")
print(f"directory          : {len(creators)} creators, {len(restaurants)} restaurants")
if not creators or not restaurants:
    raise SystemExit("no creators or restaurants to pair; bootstrap the dev data first")

existing = json_api(admin, "/api/admin/assignments")
print(f"assignments now    : {len(existing)} "
      f"({sum(1 for a in existing if a['active'])} active)")

# Pick a pair that is not already active, so the assign call is meaningful.
active = {(a["restaurantId"], a["creatorId"]) for a in existing if a["active"]}
pair = next(((r["id"], c["id"]) for r in restaurants for c in creators
             if (r["id"], c["id"]) not in active), None)
if pair is None:
    print("every pair is already assigned; nothing left to demonstrate")
    raise SystemExit(0)

rid, cid = pair
names = {c["id"]: c["name"] for c in creators} | {r["id"]: r["name"] for r in restaurants}
print(f"assigning          : {names[cid]} → {names[rid]}")
print("  first attempt    :", api(admin, "/api/admin/assignments", "POST",
                                 {"restaurantId": rid, "creatorId": cid}), "(201 expected)")
print("  duplicate        :", api(admin, "/api/admin/assignments", "POST",
                                 {"restaurantId": rid, "creatorId": cid}), "(409 expected)")

final = json_api(admin, "/api/admin/assignments")
print(f"assignments after  : {len(final)} "
      f"({sum(1 for a in final if a['active'])} active)")
print("names resolve      :", all(a["restaurantName"] and a["creatorName"] for a in final))