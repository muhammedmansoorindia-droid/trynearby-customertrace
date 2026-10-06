#!/usr/bin/env python3
"""Walks the real dual-approval workflow end to end against a running backend.

Creates a campaign as a creator, finds it in the admin queue by search, approves it, approves it as
the restaurant, and checks the creator sees it live. Then repeats with an admin rejection and a
restaurant rejection to prove neither can be rescued into going live.
"""
import json
import subprocess
import sys

API = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
PASSWORD = open("/tmp/devpw").read().strip()

CREATOR = "pooja@creator"
RESTAURANT = "copper@restrnt"
ADMIN = "admin@Trynearby"

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(f"  {'PASS' if ok else 'FAIL'}  {label}: {actual}"
          + ("" if ok else f" (expected {expected})"))
    if not ok:
        failures.append(label)
    return ok


def sh(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


def status(args):
    return sh(args + ["-o", "/dev/null", "-w", "%{http_code}"])


def login(user_id, portal):
    body = json.dumps({"userId": user_id, "password": PASSWORD, "portal": portal})
    raw = sh(["-X", "POST", f"{API}/api/auth/login",
              "-H", "Content-Type: application/json", "-d", body])
    return json.loads(raw)["accessToken"]


def auth(token):
    return ["-H", f"Authorization: Bearer {token}"]


def post(token, path, body=None):
    args = auth(token) + ["-X", "POST", f"{API}{path}"]
    if body is not None:
        args += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    return json.loads(sh(args))


def get(token, path):
    return json.loads(sh(auth(token) + [f"{API}{path}"]))


admin = login(ADMIN, "trynearby")
creator = login(CREATOR, "creator")
restaurant = login(RESTAURANT, "restaurant")

# 1. The creator may only target an actively assigned restaurant.
assigned = get(creator, "/api/creator/assignments")
active = [a for a in assigned if a["active"]]
print(f"\n1. creator assignments: {[(a['restaurantName'], a['active']) for a in assigned]}")
check("creator has at least one active assignment", len(active) > 0, True)
rid = active[0]["restaurantId"]


def new_campaign(offer):
    """Creates and submits a campaign; returns its id, status and generated code."""
    created = post(creator, "/api/creator/campaigns", {
        "restaurantId": rid, "offerText": offer, "discountPercent": 3,
        "targetCustomers": 10, "durationDays": 5,
        "startDate": "2026-12-01", "endDate": "2026-12-20",
    })
    submitted = post(creator, f"/api/creator/campaigns/{created['id']}/submit")
    return submitted["id"], submitted["status"], submitted["promoCode"]

print("\n2. create + submit as the creator")
live_id, state, code = new_campaign("E2E happy path")
check("new campaign is PENDING after submit", state, "PENDING")
print(f"      generated code = {code}")

print("\n3. admin queue: search finds it under Pending Review")
found = get(admin, f"/api/admin/campaigns?status=PENDING&q={code}&sort=newest")
check("search by code returns the campaign", live_id in [c["id"] for c in found], True)
check("it appears in the pending queue",
      live_id in [c["id"] for c in get(admin, "/api/admin/campaigns?status=PENDING")], True)

print("\n4. admin approves; the restaurant now holds it up")
approved = post(admin, f"/api/admin/campaigns/{live_id}/approve")
check("still PENDING after admin approval alone", approved["status"], "PENDING")
check("admin decision recorded", approved["adminDecision"], "APPROVED")
# Null fields are omitted rather than serialised as null, so a missing key means "no decision yet".
check("restaurant decision outstanding", approved.get("restaurantDecision") in (None, ""), True)

print("\n5. restaurant approves; the campaign goes live")
decided = post(restaurant, f"/api/restaurants/campaigns/{live_id}/approve")
check("campaign is ACTIVE", decided["status"], "ACTIVE")
check("restaurant decision recorded", decided["restaurantDecision"], "APPROVED")

print("\n6. the creator sees it live")
row = next(c for c in get(creator, f"/api/creator/campaigns?q={code}") if c["id"] == live_id)
check("creator sees ACTIVE", row["status"], "ACTIVE")
check("canSubmit is false", row["canSubmit"], False)

print("\n7. a duplicate approval is refused")
check("second admin approval is 409",
      status(auth(admin) + ["-X", "POST", f"{API}/api/admin/campaigns/{live_id}/approve",
                            "-H", "Content-Type: application/json", "-d", "{}"]), "409")

print("\n8. an admin rejection cannot be rescued by the restaurant")
rej_id, _, rej_code = new_campaign("E2E admin rejection")
rejected = post(admin, f"/api/admin/campaigns/{rej_id}/reject",
                {"reason": "Cannot be verified at settlement."})
check("status is REJECTED", rejected["status"], "REJECTED")
check("reason recorded verbatim", rejected["adminReason"], "Cannot be verified at settlement.")
check("restaurant cannot approve a rejected campaign",
      status(auth(restaurant) + ["-X", "POST", f"{API}/api/restaurants/campaigns/{rej_id}/approve",
                                 "-H", "Content-Type: application/json", "-d", "{}"]), "409")
check("still REJECTED", get(admin, f"/api/admin/campaigns/{rej_id}")["status"], "REJECTED")
creator_view = next(c for c in get(creator, f"/api/creator/campaigns?q={rej_code}")
                    if c["id"] == rej_id)
check("creator sees REJECTED", creator_view["status"], "REJECTED")
check("creator sees the reason", creator_view["adminReason"],
      "Cannot be verified at settlement.")
check("creator may revise and resubmit", creator_view["canSubmit"], True)

print("\n9. a restaurant rejection after an admin approval")
rest_rej_id, _, _ = new_campaign("E2E restaurant rejection")
post(admin, f"/api/admin/campaigns/{rest_rej_id}/approve")
rest_rejected = post(restaurant, f"/api/restaurants/campaigns/{rest_rej_id}/reject",
                     {"reason": "Not during peak hours."})
check("status is REJECTED", rest_rejected["status"], "REJECTED")
check("restaurant reason recorded", rest_rejected["restaurantReason"], "Not during peak hours.")

print("\n10. cross-tenant and role isolation")
check("restaurant cannot reach another venue's campaign",
      status(auth(restaurant) + ["-X", "POST", f"{API}/api/restaurants/campaigns/999999/approve",
                                 "-H", "Content-Type: application/json", "-d", "{}"]), "404")
check("creator cannot approve",
      status(auth(creator) + ["-X", "POST", f"{API}/api/admin/campaigns/{live_id}/approve",
                              "-H", "Content-Type: application/json", "-d", "{}"]), "403")
check("an unknown sort is a 400, not a 500",
      status(auth(admin) + [f"{API}/api/admin/campaigns?sort=SIDEWAYS"]), "400")

print("\n11. the restaurant queue contains only its own campaigns")
mine_rest = get(restaurant, "/api/restaurants/campaigns?size=100")
check("every row belongs to this venue",
      all(c["restaurantId"] == rid for c in mine_rest), True)

print("\n" + ("ALL CHECKS PASSED" if not failures else f"FAILURES: {failures}"))
sys.exit(1 if failures else 0)