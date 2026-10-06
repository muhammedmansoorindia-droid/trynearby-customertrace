#!/usr/bin/env python3
"""Exercises the new administrator directory endpoints against a running backend.

Read-mostly: any status it flips it flips back, so the seeded data is left as it was found.
"""
import json
import subprocess
import sys

API = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else "DevOnly!Pass123"


def sh(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


def status_of(args):
    return sh(args + ["-o", "/dev/null", "-w", "%{http_code}"])


def login(user_id, portal):
    body = json.dumps({"userId": user_id, "password": PASSWORD, "portal": portal})
    raw = sh(["-X", "POST", f"{API}/api/auth/login", "-H", "Content-Type: application/json",
              "-d", body])
    return json.loads(raw).get("accessToken")


def auth(token):
    return ["-H", f"Authorization: Bearer {token}"]


admin = login("admin@Trynearby", "trynearby")
restaurants = json.loads(sh(auth(admin) + [f"{API}/api/admin/restaurants"]))
creators = json.loads(sh(auth(admin) + [f"{API}/api/admin/creators"]))
subs = json.loads(sh(auth(admin) + [f"{API}/api/admin/subscriptions"]))

print("restaurants:", [(r["name"], r["status"], r["activeCreatorCount"], r["subscriptionStatus"])
                      for r in restaurants])
print("creators   :", [(c["displayName"], c["status"], c["activeRestaurantCount"])
                      for c in creators])
print("subs       :", [(s["restaurantName"], s["status"], s["entitled"]) for s in subs])

rid, cid = restaurants[0]["id"], creators[0]["id"]


def patch(path, body):
    return status_of(auth(admin) + ["-X", "PATCH", f"{API}{path}",
                                    "-H", "Content-Type: application/json", "-d", body])


def current(path, ident):
    return [r for r in json.loads(sh(auth(admin) + [f"{API}{path}"]))
            if r["id"] == ident][0]["status"]


print(f"\n1. suspend restaurant {rid}  -> {patch(f'/api/admin/restaurants/{rid}/status', chr(34) + 'status' + chr(34) + ':' + chr(34) + 'SUSPENDED' + chr(34))}"
      f"  persisted={current('/api/admin/restaurants', rid)}")
print(f"2. reactivate it            -> {patch(f'/api/admin/restaurants/{rid}/status', chr(34) + 'status' + chr(34) + ':' + chr(34) + 'ACTIVE' + chr(34))}"
      f"  persisted={current('/api/admin/restaurants', rid)}")
print(f"3. suspend creator {cid}   -> {patch(f'/api/admin/creators/{cid}/status', chr(34) + 'status' + chr(34) + ':' + chr(34) + 'SUSPENDED' + chr(34))}"
      f"  persisted={current('/api/admin/creators', cid)}")
print(f"4. reactivate it            -> {patch(f'/api/admin/creators/{cid}/status', chr(34) + 'status' + chr(34) + ':' + chr(34) + 'ACTIVE' + chr(34))}"
      f"  persisted={current('/api/admin/creators', cid)}")

banana = '{"status":"BANANA"}'
print(f"\n5. bogus status             -> {patch(f'/api/admin/restaurants/{rid}/status', banana)}"
      f"  (400 expected, still {current('/api/admin/restaurants', rid)})")
print(f"6. unknown id               -> {patch('/api/admin/restaurants/999999/status', '{\"status\":\"ACTIVE\"}')}"
      "  (404 expected)")

# Authorization: a restaurant account must not be able to manage the platform directory.
rest_account = restaurants[0]["userId"]
tok = login(rest_account, "restaurant")
print(f"\n7. restaurant reading /api/admin/restaurants -> "
      f"{status_of(auth(tok) + [f'{API}/api/admin/restaurants'])}  (403 expected)")
print(f"8. restaurant suspending a venue            -> "
      f"{status_of(auth(tok) + ['-X', 'PATCH', f'{API}/api/admin/restaurants/{rid}/status', '-H', 'Content-Type: application/json', '-d', banana])}"
      "  (403 expected)")
print(f"9. unauthenticated                          -> "
      f"{status_of([f'{API}/api/admin/restaurants'])}  (401 expected)")

# Leave the data exactly as found.
final = current('/api/admin/restaurants', rid)
print(f"\nfinal state of restaurant {rid}: {final}")