"""Phase 2 — authentication and authorization, exercised against the live backend."""
import base64
import sys

sys.path.insert(0, "/Users/mansoor/Desktop/Customer Trace/qa")
from lib import Report, call, code_of, login, token_for  # noqa: E402

r = Report()

ADMIN = "admin@Trynearby"
R1, R2 = "copper@restrnt", "malabar@restrnt"
C1, C2 = "pooja@creator", "aiswarya@creator"

# ── Valid logins ──────────────────────────────────────────────────────────────
for user, portal in [(ADMIN, "trynearby"), (R1, "restaurant"), (R2, "restaurant"),
                     (C1, "creator"), (C2, "creator")]:
    st, body = login(user, portal)
    r.check("P2", "Login", f"valid login {user}", st == 200 and "accessToken" in body,
            f"HTTP {st}")

st, body = login(R1, "creator")
r.check("P2", "Login", "restaurant cannot sign in to the creator portal",
        st in (401, 403), f"HTTP {st} {code_of(body)}")

# ── Rejected logins ───────────────────────────────────────────────────────────
st, _ = login(ADMIN, "trynearby", password="definitely-wrong")
r.check("P2", "Login", "wrong password rejected", st in (401, 423), f"HTTP {st}")

st, _ = login("nobody@nowhere", "trynearby")
r.check("P2", "Login", "unknown user rejected", st in (401, 423), f"HTTP {st}")

# ── Token handling ────────────────────────────────────────────────────────────
admin = token_for(ADMIN, "trynearby")
rest1 = token_for(R1, "restaurant")
rest2 = token_for(R2, "restaurant")
cre1 = token_for(C1, "creator")
cre2 = token_for(C2, "creator")

st, _ = call("GET", "/api/auth/me")
r.check("P2", "Tokens", "no token rejected", st == 401, f"HTTP {st}")

st, _ = call("GET", "/api/auth/me", token="not-a-jwt")
r.check("P2", "Tokens", "malformed token rejected", st == 401, f"HTTP {st}")

# Tamper with the signature: flip the last character of the segment.
head, payload, sig = admin.split(".")
flipped = sig[:-1] + ("A" if sig[-1] != "A" else "B")
st, _ = call("GET", "/api/auth/me", token=f"{head}.{payload}.{flipped}")
r.check("P2", "Tokens", "tampered signature rejected", st == 401, f"HTTP {st}")

# Tamper with the payload (role claims) while keeping a valid signature shape.
forged = base64.urlsafe_b64encode(
    b'{"sub":"1","userId":"forged@x","roles":["ADMIN"]}'
).decode().rstrip("=")
st, _ = call("GET", "/api/admin/bookings", token=f"{head}.{forged}.{sig}")
r.check("P2", "Tokens", "forged role claim rejected", st == 401, f"HTTP {st}")

# ── Role isolation on the report namespaces ────────────────────────────────────
cases = [
    ("creator blocked from platform report", "GET", "/api/reports/platform/summary", cre1, 403),
    ("restaurant blocked from platform report", "GET", "/api/reports/platform/summary", rest1, 403),
    ("admin blocked from restaurant report", "GET", "/api/reports/restaurant/summary", admin, 403),
    ("admin blocked from creator report", "GET", "/api/reports/creator/summary", admin, 403),
    ("creator blocked from restaurant report", "GET", "/api/reports/restaurant/summary", cre1, 403),
    ("creator reads own creator report", "GET", "/api/reports/creator/summary", cre1, 200),
    ("restaurant reads own restaurant report", "GET", "/api/reports/restaurant/summary", rest1, 200),
    ("admin reads platform report", "GET", "/api/reports/platform/summary", admin, 200),
]
for name, method, path, tok, want in cases:
    st, _ = call(method, path, token=tok)
    r.check("P2", "Authorization", name, st == want, f"HTTP {st}")

# ── Role isolation on admin-only routes ────────────────────────────────────────
for name, path, tok in [
    ("restaurant blocked from admin campaigns", "/api/admin/campaigns", rest1),
    ("creator blocked from admin bookings", "/api/admin/bookings", cre1),
    ("restaurant blocked from admin assignments", "/api/admin/assignments", rest1),
    ("creator blocked from admin subscriptions", "/api/admin/subscriptions/1/status", cre1),
]:
    st, _ = call("GET", path, token=tok)
    r.check("P2", "Authorization", name, st in (403, 405), f"HTTP {st}")

# ── Unauthenticated writes must perform no operation ───────────────────────────
st, _ = call("POST", "/api/creator/campaigns",
             body={"restaurantId": 1, "title": "NoAuth", "offerText": "x",
                   "discountPercent": 10, "targetCustomers": 20, "durationDays": 5,
                   "startDate": "2026-12-01", "endDate": "2026-12-10"})
r.check("P2", "Authorization", "unauthenticated campaign create refused", st == 401, f"HTTP {st}")

st, _ = call("POST", "/api/creator/bookings",
             body={"restaurantId": 1, "visitDate": "2026-12-01", "partySize": 2})
r.check("P2", "Authorization", "unauthenticated booking create refused", st == 401, f"HTTP {st}")

st, _ = call("POST", "/api/restaurants/scan/redeem",
             body={"promoCode": "AA1001A", "billPaise": 1000, "channel": "DINE_IN"})
r.check("P2", "Authorization", "unauthenticated redeem refused", st == 401, f"HTTP {st}")

# ── Cross-tenant isolation ────────────────────────────────────────────────────
_, body1 = call("GET", "/api/restaurant/bookings", token=rest1)
_, body2 = call("GET", "/api/restaurant/bookings", token=rest2)
ids1 = {b["id"] for b in (body1 or {}).get("items", [])}
ids2 = {b["id"] for b in (body2 or {}).get("items", [])}
r.check("P2", "Tenant isolation", "two restaurants see disjoint bookings",
        not (ids1 & ids2), f"{sorted(ids1)} vs {sorted(ids2)}")

if ids2:
    st, _ = call("GET", f"/api/restaurant/bookings/{sorted(ids2)[0]}", token=rest1)
    r.check("P2", "Tenant isolation", "restaurant cannot read another's booking",
            st == 404, f"HTTP {st}")

b1 = call("GET", "/api/creator/campaigns", token=cre1)[1] or []
b2 = call("GET", "/api/creator/campaigns", token=cre2)[1] or []
# The backend serialises these lists as a bare array, not a page envelope.
rows = lambda b: b.get("content", []) if isinstance(b, dict) else b
c1 = {c["id"] for c in rows(b1)}
c2 = {c["id"] for c in rows(b2)}
r.check("P2", "Tenant isolation", "two creators see disjoint campaigns",
        not (c1 & c2), f"{sorted(c1)} vs {sorted(c2)}")

# ── Notifications are private ─────────────────────────────────────────────────
_, n1 = call("GET", "/api/notifications", token=rest1)
_, n2 = call("GET", "/api/notifications", token=rest2)
n1_ids = {n["id"] for n in (n1 or {}).get("items", [])}
n2_ids = {n["id"] for n in (n2 or {}).get("items", [])}
r.check("P2", "Tenant isolation", "notification ids do not overlap between users",
        not (n1_ids & n2_ids), f"{len(n1_ids)} vs {len(n2_ids)}")

if n1_ids:
    st, _ = call("POST", f"/api/notifications/{sorted(n1_ids)[0]}/read", token=rest2)
    r.check("P2", "Tenant isolation", "cannot mark another's notification read",
            st == 404, f"HTTP {st}")

# ── /me reflects only the caller's own identity ────────────────────────────────
_, me1 = call("GET", "/api/auth/me", token=rest1)
_, me2 = call("GET", "/api/auth/me", token=rest2)
r.check("P2", "Identity", "each restaurant sees its own restaurantId",
        me1["restaurantId"] != me2["restaurantId"],
        f"{me1['restaurantId']} vs {me2['restaurantId']}")
r.check("P2", "Identity", "/me exposes no password material",
        "password" not in str(me1).lower(), "keys checked")

r.dump()
fails = sum(1 for x in r.rows if x[3] == "FAIL")
print(f"\nP2 rows: {len(r.rows)}  failures: {fails}")