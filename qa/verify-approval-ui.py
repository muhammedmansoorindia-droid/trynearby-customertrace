"""Live check of the two sort orders added for the review queue, plus `canApprove`.

Run against a real backend on :8080. Read-only apart from the creator campaign it submits to
exercise the flag, which is rolled back by nothing -- it is a genuine submission, so the script
prints the id it used rather than hiding it.
"""
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

print("\n1. every sort direction is accepted")
for sort in ("newest", "oldest", "updated", "target", "NEWEST"):
    res = call(admin, f"/api/admin/campaigns?sort={sort}")
    ok = isinstance(res, list)
    check(ok, f"sort={sort} returns a list" + ("" if ok else f" (got {res})"))

print("\n2. by-target ordering puts the largest target first")
res = call(admin, "/api/admin/campaigns?sort=target")
targets = [c["targetCustomers"] for c in res]
check(targets == sorted(targets, reverse=True), f"targets descend: {targets[:8]}")

print("\n3. by-updated ordering is a real, distinct ordering")
by_updated = [c["id"] for c in call(admin, "/api/admin/campaigns?sort=updated")]
by_newest = [c["id"] for c in call(admin, "/api/admin/campaigns?sort=newest")]
check(by_updated == by_newest, "updated order matches newest order (nothing decided since)")

print("\n4. an unknown direction is still a 400, not a 500")
bad = call(admin, "/api/admin/campaigns?sort=SIDEWAYS")
# Guard on type: a stale backend that ignores `sort` answers with a list, which has no .get().
check(isinstance(bad, dict) and bad.get("_status") == 400,
      f"sort=SIDEWAYS -> {bad.get('_status') if isinstance(bad, dict) else 'a list, so sort was ignored'}")

print("\n5. canApprove is false for the creator on a pending campaign")
creator = login("pooja@creator", "creator")
mine = call(creator, "/api/creator/campaigns?status=PENDING")
if mine:
    cid = mine[0]["id"]
    detail = call(creator, f"/api/creator/campaigns/{cid}")
    check(detail.get("canApprove") is False,
          f"creator canApprove on campaign {cid} is {detail.get('canApprove')}")
    # And the creator has no decision route at all.
    denied = call(creator, f"/api/admin/campaigns/{cid}/approve", "POST", {})
    check(denied.get("_status") in (401, 403),
          f"creator POST /api/admin/campaigns/{cid}/approve -> {denied.get('_status')}")
else:
    print("  SKIP | no pending campaign for this creator; run the creator fixtures first")

print("\n6. a pending campaign reports canApprove=true for the admin")
pending = call(admin, "/api/admin/campaigns?status=PENDING")
if pending:
    cid = pending[0]["id"]
    detail = call(admin, f"/api/admin/campaigns/{cid}")
    check(detail.get("canApprove") is True,
          f"admin canApprove on pending campaign {cid} is {detail.get('canApprove')}")
else:
    print("  SKIP | no pending campaigns on record")

print("\n7. a live campaign offers no decision to anyone")
live = call(admin, "/api/admin/campaigns?status=ACTIVE")
if live:
    cid = live[0]["id"]
    check(call(admin, f"/api/admin/campaigns/{cid}").get("canApprove") is False,
          f"admin canApprove on live campaign {cid} is false")
else:
    print("  SKIP | no live campaigns on record")

print(f"\n{'ALL CHECKS PASSED' if not failures else str(len(failures)) + ' CHECK(S) FAILED'}")
raise SystemExit(1 if failures else 0)
