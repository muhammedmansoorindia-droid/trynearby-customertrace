# Acceptance test for the reported admin-approval failure, run against a real backend + database.
#
# The failure: /trynearby/campaigns showed no Approve/Reject in the details modal. The UI renders
# from the LIST response, so this asserts the flag on the LIST row -- not the detail endpoint, which
# was already correct and masked the bug.
#
# Usage: CT_API=http://localhost:8081 python3 qa/verify-admin-approve-list.py
import json
import os
import pathlib
import urllib.error
import urllib.request

API = os.environ.get("CT_API", "http://localhost:8080")
PW = pathlib.Path("/tmp/devpw").read_text().strip()

failures = []


def check(cond, label):
    print(("  OK   " if cond else "  FAIL ") + "| " + label)
    if not cond:
        failures.append(label)


def login(user, portal="x"):
    body = json.dumps({"userId": user, "password": PW, "portal": portal}).encode()
    req = urllib.request.Request(
        f"{API}/api/auth/login", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())["accessToken"]


def call(tok, path, method="GET", body=None):
    headers = {"Authorization": f"Bearer {tok}"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(f"{API}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read() or b"[]")
    except urllib.error.HTTPError as exc:
        return {"_status": exc.code, "_body": json.loads(exc.read() or b"{}")}


admin = login("admin@Trynearby", "admin")
creator = login("pooja@creator", "creator")

print("\n1. the admin LIST row carries canApprove=true for a pending campaign")
rows = call(admin, "/api/admin/campaigns?status=PENDING")
check(isinstance(rows, list) and len(rows) > 0, f"pending rows exist ({len(rows) if isinstance(rows, list) else rows})")
decidable = [r for r in rows if r.get("canApprove")]
check(len(decidable) > 0,
      f"at least one pending row is marked decidable ({len(decidable)} of {len(rows)})")

if decidable:
    row = decidable[0]
    print(f"      id={row['id']} code={row.get('promoCode')} "
          f"admin={row.get('adminDecision', 'PENDING')} "
          f"rest={row.get('restaurantDecision', 'PENDING')} status={row['status']}")

    print("\n2. the LIST row agrees with the DETAIL endpoint")
    detail = call(admin, f"/api/admin/campaigns/{row['id']}")
    check(detail.get("canApprove") == row.get("canApprove"),
          f"list={row.get('canApprove')} detail={detail.get('canApprove')} (they must match)")

print("\n3. the restaurant's approval does not suppress the admin's controls")
# Prefer the exact reported shape: TryNearby PENDING while the restaurant has already approved.
# That combination is reachable because the restaurant's own gate only requires PENDING status and
# no prior restaurant decision -- the backend does not force TryNearby to go first.
reported = [r for r in rows
            if r.get("restaurantDecision") == "APPROVED" and r.get("adminDecision") is None]
target = reported[0] if reported else decidable[0]
if reported:
    print(f"      using the reported shape: id={target['id']} code={target.get('promoCode')} "
          f"admin=PENDING restaurant=APPROVED")
    check(target.get("canApprove") is True,
          "admin is decidable while TryNearby=PENDING and restaurant=APPROVED")
else:
    print("      SKIP | no campaign in the reported shape; using a plain pending one")

print("\n4. the admin can actually decide it through the real endpoint")
cid = target["id"]
approved = call(admin, f"/api/admin/campaigns/{cid}/approve", "POST", {})
check(not (isinstance(approved, dict) and "_status" in approved),
      f"POST /api/admin/campaigns/{cid}/approve -> "
      f"{approved.get('_status') if isinstance(approved, dict) and '_status' in approved else 200}")
if not (isinstance(approved, dict) and "_status" in approved):
    check(approved.get("adminDecision") == "APPROVED", "adminDecision is now APPROVED")
    # The dual-approval rule: live only once the restaurant has also approved.
    if approved.get("restaurantDecision") == "APPROVED":
        check(approved.get("status") == "ACTIVE",
              "both approved -> campaign is ACTIVE (the acceptance criterion)")
        check(approved.get("activatedAt") is not None, "activatedAt is set")
    else:
        check(approved.get("status") == "PENDING",
              "only TryNearby approved -> still PENDING")

    print("\n5. a duplicate approval is refused")
    again = call(admin, f"/api/admin/campaigns/{cid}/approve", "POST", {})
    check(isinstance(again, dict) and again.get("_status") in (400, 409),
          f"second approve -> {again.get('_status') if isinstance(again, dict) else 'accepted!'}")

    print("\n6. the row no longer offers a decision once TryNearby has decided")
    after = [r for r in call(admin, "/api/admin/campaigns?status=PENDING")
             if r["id"] == cid]
    check(not after or after[0].get("canApprove") is not True,
          "the decided campaign is no longer decidable by the admin")

print("\n7. a creator is never offered a decision in their own list")
mine = call(creator, "/api/creator/campaigns")
if isinstance(mine, list) and mine:
    check(all(r.get("canApprove") is False for r in mine),
          "no creator campaign is marked decidable")
    denied = call(creator, f"/api/admin/campaigns/{mine[0]['id']}/approve", "POST", {})
    check(isinstance(denied, dict) and denied.get("_status") in (401, 403),
          f"creator POST admin approve -> {denied.get('_status')}")
else:
    print("  SKIP | creator has no campaigns on record")

print(f"\n{'ALL CHECKS PASSED' if not failures else str(len(failures)) + ' CHECK(S) FAILED'}")
raise SystemExit(1 if failures else 0)
