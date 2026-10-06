#!/usr/bin/env python3
"""Walks the whole Admin → Creator Assignment → New Campaign path against a running stack.

Assigns a restaurant to a creator, creates a campaign, deactivates the assignment, then shows the
new campaign is refused while the historical campaigns survive.
"""
import json
import subprocess
import sys

API = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else "DevOnly!Pass123"


def sh(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


def auth(token):
    return ["-H", f"Authorization: Bearer {token}"]


def login(user, portal):
    body = json.dumps({"userId": user, "password": PASSWORD, "portal": portal})
    raw = sh(["-X", "POST", f"{API}/api/auth/login", "-H", "Content-Type: application/json",
              "-d", body])
    return json.loads(raw).get("accessToken")


def db(sql):
    return subprocess.run(
        ["docker", "exec", "ct-mysql", "mysql", "-uroot", "-prootpw", "-N", "-e", sql],
        capture_output=True, text=True).stdout.strip()


admin = login("admin@Trynearby", "trynearby")
restaurants = json.loads(sh(auth(admin) + [f"{API}/api/admin/restaurants"]))
creators = json.loads(sh(auth(admin) + [f"{API}/api/admin/creators"]))
rows = json.loads(sh(auth(admin) + [f"{API}/api/admin/assignments"]))

# Pick a creator/restaurant pair that is not already active, so the create is meaningful.
active = {(a["restaurantId"], a["creatorId"]) for a in rows if a["active"]}
pair = next(((r["id"], c["id"]) for r in restaurants for c in creators
             if (r["id"], c["id"]) not in active), None)
if pair is None:
    print("every pair is already assigned; nothing left to demonstrate")
    raise SystemExit(0)
rid, cid = pair
rname = next(r["name"] for r in restaurants if r["id"] == rid)
cname = next(c["name"] for c in creators if c["id"] == cid)
print(f"1. admin assigns {rname} to {cname}")

status = sh(auth(admin) + ["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                           f"{API}/api/admin/assignments", "-H", "Content-Type: application/json",
                           "-d", json.dumps({"restaurantId": rid, "creatorId": cid})])
print(f"   POST /api/admin/assignments -> {status} (201 expected)")

aid = next(a["id"] for a in json.loads(sh(auth(admin) + [f"{API}/api/admin/assignments"]))
           if a["restaurantId"] == rid and a["creatorId"] == cid)
row = next(a for a in json.loads(sh(auth(admin) + [f"{API}/api/admin/assignments"])) if a["id"] == aid)
print(f"   persisted row: active={row['active']} names={row['restaurantName']!r}/{row['creatorName']!r}")

account = db(f"SELECT u.user_id FROM customertrace.creators c "
             f"JOIN customertrace.users u ON u.id=c.user_id WHERE c.id={cid}")
creator = login(account, "creator")
print(f"\n2. creator signs in as {account}")

campaign = json.dumps({"restaurantId": rid, "offerText": "E2E check", "discountPercent": 5,
                       "targetCustomers": 20, "durationDays": 5,
                       "startDate": "2026-09-01", "endDate": "2026-09-20"})
code = sh(auth(creator) + ["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                          f"{API}/api/creator/campaigns", "-H", "Content-Type: application/json",
                          "-d", campaign])
print(f"   campaign for the assigned venue    -> {code} (201 expected)")

vis = json.loads(sh(auth(creator) + [f"{API}/api/creator/assignments"]))
print(f"   wizard dropdown will offer: {[v['restaurantName'] for v in vis if v['active']]}")

before = db(f"SELECT COUNT(*) FROM customertrace.campaigns WHERE restaurant_id={rid}")
print(f"\n3. admin deactivates assignment {aid}")
print("   DELETE /api/admin/assignments/"
      f"{aid} -> {sh(auth(admin) + ['-o', '/dev/null', '-w', '%{http_code}', '-X', 'DELETE', f'{API}/api/admin/assignments/{aid}'])}")

code = sh(auth(creator) + ["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                          f"{API}/api/creator/campaigns", "-H", "Content-Type: application/json",
                          "-d", campaign])
print(f"   campaign for the deactivated venue -> {code} (403 expected)")

vis = json.loads(sh(auth(creator) + [f"{API}/api/creator/assignments"]))
print(f"   wizard dropdown now offers: {[v['restaurantName'] for v in vis if v['active']] or 'nothing'}")

after = db(f"SELECT COUNT(*) FROM customertrace.campaigns WHERE restaurant_id={rid}")
print(f"\n4. history preserved at that venue: {before} -> {after} campaigns")
print(f"   assignment row still exists: "
      f"{db(f'SELECT COUNT(*) FROM customertrace.restaurant_creator_assignments WHERE id={aid}')}")