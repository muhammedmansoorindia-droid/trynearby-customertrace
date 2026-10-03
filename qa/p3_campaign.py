"""Phase 3 — campaign lifecycle, end to end against the live backend and database."""
import sys
import urllib.request

sys.path.insert(0, "/Users/mansoor/Desktop/Customer Trace/qa")
from lib import Report, call, code_of, token_for  # noqa: E402

r = Report()

ADMIN = token_for("admin@Trynearby", "trynearby")
R1 = token_for("copper@restrnt", "restaurant")
R2 = token_for("malabar@restrnt", "restaurant")
C1 = token_for("pooja@creator", "creator")
C2 = token_for("aiswarya@creator", "creator")

_, me = call("GET", "/api/auth/me", token=C1)
_, me_r1 = call("GET", "/api/auth/me", token=R1)
_, me_r2 = call("GET", "/api/auth/me", token=R2)
REST1, REST2 = me_r1["restaurantId"], me_r2["restaurantId"]
CREATOR = me["creatorId"]

STAMP = "QA-Lifecycle"
created = []


def db(sql):
    """Reads the dev schema directly, so assertions are on persisted state, not HTTP echoes."""
    out = subprocess.run(
        ["docker", "exec", "ct-mysql", "mysql", "-uroot", "-prootpw", "-N", "-e", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


import subprocess  # noqa: E402  (used by db())


def counts(campaign_id):
    scans = db(f"SELECT COUNT(*) FROM customertrace.scan_events WHERE campaign_id={campaign_id}")
    redemptions = db(
        f"SELECT COUNT(*) FROM customertrace.redemptions WHERE campaign_id={campaign_id}")
    return int(scans or 0), int(redemptions or 0)


# ── 1. Creator creates a draft ────────────────────────────────────────────────
st, draft = call("POST", "/api/creator/campaigns", token=C1, body={
    "restaurantId": REST1, "title": f"{STAMP}-draft", "offerText": "10% off",
    "discountPercent": 10, "targetCustomers": 20, "durationDays": 5,
    "startDate": "2026-11-01", "endDate": "2026-11-10"})
r.check("P3", "Create", "creator creates a draft", st == 201 and draft["status"] == "DRAFT",
        f"HTTP {st} {draft.get('status') if isinstance(draft, dict) else draft}")
CID = draft["id"] if st == 201 else None
created.append(CID)

if CID:
    row = db(f"SELECT status FROM customertrace.campaigns WHERE id={CID}")
    r.check("P3", "Create", "draft persisted in the database", row == "DRAFT", f"db={row}")
    r.check("P3", "Create", "draft has no promo code before submit",
            not db(f"SELECT promo_code FROM customertrace.promo_codes WHERE campaign_id={CID}"),
            "no promo_codes row")

# ── Validation on create ──────────────────────────────────────────────────────
bad_cases = [
    ("blank title", {"title": "", "offerText": "x", "discountPercent": 10,
                     "targetCustomers": 20, "durationDays": 5,
                     "startDate": "2026-11-01", "endDate": "2026-11-10"}),
    ("target below 10", {"title": "t", "offerText": "x", "discountPercent": 10,
                         "targetCustomers": 9, "durationDays": 5,
                         "startDate": "2026-11-01", "endDate": "2026-11-10"}),
    ("target above 99", {"title": "t", "offerText": "x", "discountPercent": 10,
                         "targetCustomers": 100, "durationDays": 5,
                         "startDate": "2026-11-01", "endDate": "2026-11-10"}),
    ("duration above 31", {"title": "t", "offerText": "x", "discountPercent": 10,
                           "targetCustomers": 20, "durationDays": 32,
                           "startDate": "2026-11-01", "endDate": "2026-11-10"}),
    ("discount over 100", {"title": "t", "offerText": "x", "discountPercent": 150,
                           "targetCustomers": 20, "durationDays": 5,
                           "startDate": "2026-11-01", "endDate": "2026-11-10"}),
    ("unknown restaurant", {"title": "t", "offerText": "x", "discountPercent": 10,
                            "targetCustomers": 20, "durationDays": 5, "restaurantId": 999999,
                            "startDate": "2026-11-01", "endDate": "2026-11-10"}),
]
for name, payload in bad_cases:
    payload.setdefault("restaurantId", REST1)
    st, body = call("POST", "/api/creator/campaigns", token=C1, body=payload)
    # A bad *field* is a 400; a restaurant id that does not exist is a 404 missing reference.
    want = 404 if name == "unknown restaurant" else 400
    r.check("P3", "Validation", f"create rejects {name}", st == want,
            f"HTTP {st} {code_of(body)}")

st, _ = call("POST", "/api/creator/campaigns", token=R1, body={
    "restaurantId": REST1, "title": "restaurant attempt", "offerText": "x",
    "discountPercent": 10, "targetCustomers": 20, "durationDays": 5,
    "startDate": "2026-11-01", "endDate": "2026-11-10"})
r.check("P3", "Validation", "restaurant cannot create a campaign", st == 403, f"HTTP {st}")

# ── 2. Creator submits; campaign becomes PENDING and a code is minted ─────────
if CID:
    st, sub = call("POST", f"/api/creator/campaigns/{CID}/submit", token=C1)
    r.check("P3", "Submit", "creator submits for review",
            st == 200 and sub["status"] == "PENDING", f"HTTP {st} {sub.get('status')}")
    r.check("P3", "Submit", "promo code minted on submit", bool(sub.get("promoCode")),
            f"code={sub.get('promoCode')}")
    r.check("P3", "Submit", "PENDING persisted in the database",
            db(f"SELECT status FROM customertrace.campaigns WHERE id={CID}") == "PENDING",
            "db=PENDING")

    # Code shape: LLNNDDX — 2 letters, 2 digits (target), 2 digits (days), A-J tier.
    code = sub.get("promoCode") or ""
    letters, nn, dd, tier = code[:2], code[2:4], code[4:6], code[6:]
    r.check("P3", "Promo code", "code is 7 characters", len(code) == 7, f"'{code}'")
    r.check("P3", "Promo code", "chars 1-2 are uppercase letters",
            len(letters) == 2 and letters.isalpha() and letters.isupper(), f"'{letters}'")
    r.check("P3", "Promo code", "chars 3-4 are the customer target (10-99)",
            nn.isdigit() and 10 <= int(nn) <= 99, f"'{nn}' vs target 20")
    r.check("P3", "Promo code", "chars 5-6 are the duration (01-31)",
            dd.isdigit() and 1 <= int(dd) <= 31, f"'{dd}' vs duration 5")
    r.check("P3", "Promo code", "final char is a tier A-J",
            len(tier) == 1 and "A" <= tier <= "J", f"'{tier}'")

    notif = db("SELECT COUNT(*) FROM customertrace.notifications "
               "WHERE type LIKE '%SUBMITTED%'")
    r.check("P3", "Notifications", "submission raised a notification", int(notif or 0) > 0,
            f"count={notif}")

    st2, _ = call("POST", f"/api/creator/campaigns/{CID}/submit", token=C1)
    r.check("P3", "Validation", "duplicate submit refused", st2 in (409, 400), f"HTTP {st2}")

# ── 4. Admin approves: must stay PENDING until the restaurant also approves ────
if CID:
    st, body = call("POST", f"/api/admin/campaigns/{CID}/approve", token=ADMIN, body={})
    r.check("P3", "Dual approval", "admin approves", st == 200, f"HTTP {st} {code_of(body)}")
    st, after = call("GET", f"/api/admin/campaigns/{CID}", token=ADMIN)
    r.check("P3", "Dual approval", "still PENDING after only the admin approved",
            after["status"] == "PENDING", f"status={after['status']}")
    r.check("P3", "Dual approval", "admin decision recorded",
            after.get("adminDecision") == "APPROVED", f"{after.get('adminDecision')}")

    st, _ = call("POST", f"/api/restaurants/campaigns/{CID}/approve", token=R2, body={})
    r.check("P3", "Dual approval", "the wrong restaurant cannot approve", st in (403, 404),
            f"HTTP {st}")
    st, still = call("GET", f"/api/admin/campaigns/{CID}", token=ADMIN)
    r.check("P3", "Dual approval", "wrong restaurant's attempt changed nothing",
            still["status"] == "PENDING", f"status={still['status']}")

    st, _ = call("POST", f"/api/admin/campaigns/{CID}/approve", token=C1, body={})
    r.check("P3", "Dual approval", "creator cannot approve", st == 403, f"HTTP {st}")

    # ── 5. The correct restaurant approves: becomes ACTIVE ────────────────────
    st, body = call("POST", f"/api/restaurants/campaigns/{CID}/approve", token=R1, body={})
    r.check("P3", "Dual approval", "correct restaurant approves", st == 200,
            f"HTTP {st} {code_of(body)}")
    st, live = call("GET", f"/api/admin/campaigns/{CID}", token=ADMIN)
    r.check("P3", "Dual approval", "campaign is ACTIVE after both approvals",
            live["status"] == "ACTIVE", f"status={live['status']}")
    r.check("P3", "Dual approval", "ACTIVE persisted in the database",
            db(f"SELECT status FROM customertrace.campaigns WHERE id={CID}") == "ACTIVE",
            "db=ACTIVE")
    r.check("P3", "Dual approval", "no further approval offered once active",
            live.get("canApprove") is False, f"canApprove={live.get('canApprove')}")

    st, hist = call("GET", f"/api/admin/campaigns/{CID}/history", token=ADMIN)
    r.check("P3", "History", "history records the full decision trail",
            st == 200 and isinstance(hist, list) and len(hist) >= 3,
            f"{len(hist) if isinstance(hist, list) else hist} entries")

# ── 7. Rejection, reason visibility, resubmission ─────────────────────────────
st, rej = call("POST", "/api/creator/campaigns", token=C2, body={
    "restaurantId": REST2, "title": f"{STAMP}-reject", "offerText": "20% off",
    "discountPercent": 20, "targetCustomers": 30, "durationDays": 7,
    "startDate": "2026-11-01", "endDate": "2026-11-12"})
if st == 201:
    RID = rej["id"]
    call("POST", f"/api/creator/campaigns/{RID}/submit", token=C2)
    st, rbody = call("POST", f"/api/admin/campaigns/{RID}/reject", token=ADMIN,
                     body={"reason": "Offer cannot be honoured at peak hours"})
    r.check("P3", "Rejection", "admin rejects with a reason", st == 200,
            f"HTTP {st} {code_of(rbody)}")
    st, rej_after = call("GET", f"/api/creator/campaigns/{RID}", token=C2)
    r.check("P3", "Rejection", "creator sees REJECTED", rej_after["status"] == "REJECTED",
            f"status={rej_after['status']}")
    r.check("P3", "Rejection", "creator sees the rejection reason verbatim",
            rej_after.get("adminReason") == "Offer cannot be honoured at peak hours",
            f"'{rej_after.get('adminReason')}'")
    r.check("P3", "Rejection", "resubmission offered after rejection",
            rej_after.get("canSubmit") is True, f"canSubmit={rej_after.get('canSubmit')}")

    st, _ = call("POST", f"/api/admin/campaigns/{RID}/approve", token=ADMIN, body={})
    r.check("P3", "Rejection", "rejected campaign cannot be approved", st in (409, 400),
            f"HTTP {st}")

    st, resub = call("POST", f"/api/creator/campaigns/{RID}/submit", token=C2)
    r.check("P3", "Rejection", "creator resubmits after rejection",
            st == 200 and resub["status"] == "PENDING", f"HTTP {st} {resub.get('status')}")
    st, again = call("GET", f"/api/admin/campaigns/{RID}", token=ADMIN)
    r.check("P3", "Rejection", "resubmission counted", (again.get("resubmissionCount") or 0) >= 1,
            f"count={again.get('resubmissionCount')}")
    created.append(RID)

# ── 8. Duplicate / invalid decisions ──────────────────────────────────────────
if CID:
    st, _ = call("POST", f"/api/admin/campaigns/{CID}/approve", token=ADMIN, body={})
    r.check("P3", "Validation", "duplicate approval refused", st in (409, 400), f"HTTP {st}")

st, _ = call("POST", "/api/admin/campaigns/999999/approve", token=ADMIN, body={})
r.check("P3", "Validation", "approving an unknown campaign is 404", st == 404, f"HTTP {st}")

# ── 9. Cross-creator access ───────────────────────────────────────────────────
if CID:
    st, _ = call("GET", f"/api/creator/campaigns/{CID}", token=C2)
    r.check("P3", "Tenant isolation", "creator cannot read another's campaign", st == 404,
            f"HTTP {st}")

print("QA campaign ids created:", created)
r.dump()
print(f"\nP3 rows: {len(r.rows)}  failures: {sum(1 for x in r.rows if x[3] == 'FAIL')}")