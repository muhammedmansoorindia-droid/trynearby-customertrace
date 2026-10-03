"""Phase 5 — every role-specific feature, against the live backend and database."""
import subprocess
import sys
import time

sys.path.insert(0, "/Users/mansoor/Desktop/Customer Trace/qa")
from lib import Report, call, code_of, token_for  # noqa: E402

r = Report()
RUN = str(int(time.time()))[-6:]


def db(sql):
    out = subprocess.run(
        ["docker", "exec", "ct-mysql", "mysql", "-uroot", "-prootpw", "-N", "-e", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


ADMIN = token_for("admin@Trynearby", "trynearby")
R1 = token_for("copper@restrnt", "restaurant")
R2 = token_for("malabar@restrnt", "restaurant")
C1 = token_for("pooja@creator", "creator")
C2 = token_for("aiswarya@creator", "creator")

_, me1 = call("GET", "/api/auth/me", token=R1)
_, me2 = call("GET", "/api/auth/me", token=R2)
_, mec = call("GET", "/api/auth/me", token=C1)
REST1, REST2, CREATOR = me1["restaurantId"], me2["restaurantId"], mec["creatorId"]

# ── Administrator surfaces ───────────────────────────────────────────────────
st, plat = call("GET", "/api/reports/platform/summary", token=ADMIN)
r.check("P5", "Admin", "platform summary responds", st == 200,
        f"restaurants={plat.get('totalRestaurants')} creators={plat.get('totalCreators')}")

# Report totals must equal the database, not a cached or fabricated figure.
r.check("P5", "Admin", "platform restaurant count matches the database",
        plat.get("totalRestaurants") == int(db("SELECT COUNT(*) FROM customertrace.restaurants")),
        f"api={plat.get('totalRestaurants')} db ok")
r.check("P5", "Admin", "platform creator count matches the database",
        plat.get("totalCreators") == int(db("SELECT COUNT(*) FROM customertrace.creators")),
        f"api={plat.get('totalCreators')} db ok")
# The report counts only *valid* scans: a rejected presentation of a malformed code is
# recorded with valid=0 and must not inflate reach. Compare against the valid subset.
db_valid_scans = db("SELECT COUNT(*) FROM customertrace.scan_events WHERE valid=1 "
                   "AND scanned_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)")
r.check("P5", "Admin", "platform scan count matches valid scans in the database",
        plat.get("totalScans") == int(db_valid_scans),
        f"api={plat.get('totalScans')} db_valid={db_valid_scans}")
r.check("P5", "Admin", "rejected scans are excluded from reach",
        plat.get("totalScans") < int(db("SELECT COUNT(*) FROM customertrace.scan_events")),
        "invalid scans excluded")

st, trend = call("GET", "/api/reports/platform/trend", token=ADMIN)
r.check("P5", "Admin", "platform trend returns points", st == 200 and "points" in trend,
        f"{len(trend.get('points', []))} days")

st, queue = call("GET", "/api/admin/campaigns?status=PENDING", token=ADMIN)
r.check("P5", "Admin", "pending review queue is filterable", st == 200,
        f"{len(queue) if isinstance(queue, list) else queue} pending")

# ── Assignments ──────────────────────────────────────────────────────────────
st, assigns = call("GET", "/api/admin/assignments", token=ADMIN)
r.check("P5", "Assignments", "admin lists all assignments",
        st == 200 and isinstance(assigns, list),
        f"{len(assigns) if isinstance(assigns, list) else assigns}")

# Ensure the creator is assigned to restaurant 1 so review eligibility is testable.
if not any(a["creatorId"] == CREATOR and a["restaurantId"] == REST1 and a["active"]
           for a in (assigns or [])):
    st, made = call("POST", "/api/admin/assignments", token=ADMIN,
                    body={"restaurantId": REST1, "creatorId": CREATOR})
    r.check("P5", "Assignments", "admin assigns a creator", st == 201, f"HTTP {st}")
    a_id = made.get("id") if isinstance(made, dict) else None
else:
    a_id = next(a["id"] for a in assigns
                if a["creatorId"] == CREATOR and a["restaurantId"] == REST1 and a["active"])
    r.check("P5", "Assignments", "assignment already present for review tests", True,
            f"id={a_id}")

st, dup = call("POST", "/api/admin/assignments", token=ADMIN,
               body={"restaurantId": REST1, "creatorId": CREATOR})
r.check("P5", "Assignments", "duplicate active assignment refused",
        st == 409 and code_of(dup) == "ASSIGNMENT_EXISTS", f"HTTP {st} {code_of(dup)}")

st, _ = call("POST", "/api/admin/assignments", token=ADMIN,
             body={"restaurantId": REST1, "creatorId": 999999})
r.check("P5", "Assignments", "assigning an unknown creator is 404", st == 404, f"HTTP {st}")

st, _ = call("POST", "/api/restaurant/assignments", token=C1, body={"creatorId": CREATOR})
r.check("P5", "Authorization", "creator cannot create assignments", st == 403, f"HTTP {st}")

st, own = call("GET", "/api/restaurant/assignments", token=R1)
r.check("P5", "Assignments", "restaurant sees only its own assignments",
        st == 200 and all(a["restaurantId"] == REST1 for a in own),
        f"{len(own)} rows all own")

# ── Reviews ──────────────────────────────────────────────────────────────────
st, summary = call("GET", f"/api/restaurants/{REST1}/reviews", token=C1)
r.check("P5", "Reviews", "restaurant review profile is readable by a creator", st == 200,
        f"avg={summary.get('averageRating')} count={summary.get('totalReviews')}")

st, again = call("POST", "/api/creator/reviews", token=C1,
                 body={"restaurantId": REST1, "rating": 3, "body": f"{RUN} duplicate attempt"})
r.check("P5", "Reviews", "repeat review handled per the one-per-pair model",
        st in (201, 409), f"HTTP {st} {code_of(again)}")

st, bad_rating = call("POST", "/api/creator/reviews", token=C1,
                      body={"restaurantId": REST2, "rating": 9, "body": "out of range"})
r.check("P5", "Reviews", "rating above 5 refused", st in (400, 403),
        f"HTTP {st} {code_of(bad_rating)}")

st, _ = call("GET", "/api/creator/reviews", token=R1)
r.check("P5", "Authorization", "restaurant cannot read the creator review list", st == 403,
        f"HTTP {st}")

# ── Bookings ─────────────────────────────────────────────────────────────────
visit = "2026-12-20"
st, booking = call("POST", "/api/creator/bookings", token=C1,
                   body={"restaurantId": REST1, "visitDate": visit, "partySize": 3,
                         "note": f"qa {RUN}"})
r.check("P5", "Bookings", "creator requests a booking", st == 201,
        f"HTTP {st} id={booking.get('id') if isinstance(booking, dict) else booking}")
BID = booking.get("id") if isinstance(booking, dict) else None

if BID:
    st, _ = call("PATCH", f"/api/creator/bookings/{BID}/status", token=C1,
                 body={"status": "CONFIRMED"})
    r.check("P5", "Bookings", "creator cannot confirm its own booking", st == 403, f"HTTP {st}")

    st, _ = call("PATCH", f"/api/restaurant/bookings/{BID}/status", token=R1,
                 body={"status": "CONFIRMED"})
    r.check("P5", "Bookings", "restaurant confirms", st == 200, f"HTTP {st}")

    st, detail = call("GET", f"/api/restaurant/bookings/{BID}", token=R1)
    hist = detail.get("history", []) if isinstance(detail, dict) else []
    r.check("P5", "Bookings", "history records REQUESTED then CONFIRMED",
            len(hist) >= 2 and hist[-1].get("toStatus") == "CONFIRMED",
            f"{[h.get('toStatus') for h in hist]}")

    st, _ = call("PATCH", f"/api/restaurant/bookings/{BID}/status", token=R2,
                 body={"status": "COMPLETED"})
    r.check("P5", "Tenant isolation", "wrong restaurant cannot complete the booking",
            st in (403, 404), f"HTTP {st}")

    r.check("P5", "Bookings", "booking status persisted",
            db(f"SELECT status FROM customertrace.bookings WHERE id={BID}") == "CONFIRMED",
            "db=CONFIRMED")

st, past = call("POST", "/api/creator/bookings", token=C1,
                body={"restaurantId": REST1, "visitDate": "2020-01-01", "partySize": 2})
r.check("P5", "Bookings", "past visit date refused", st == 400, f"HTTP {st} {code_of(past)}")

st, _ = call("POST", "/api/creator/bookings", token=C1,
             body={"restaurantId": REST1, "visitDate": visit, "partySize": 0})
r.check("P5", "Bookings", "party size of zero refused", st == 400, f"HTTP {st}")

st, _ = call("POST", "/api/creator/bookings", token=R1,
             body={"restaurantId": REST1, "visitDate": visit, "partySize": 2})
r.check("P5", "Authorization", "restaurant cannot create bookings", st == 403, f"HTTP {st}")

# ── Subscriptions ────────────────────────────────────────────────────────────
st, plans = call("GET", "/api/plans", token=R1)
r.check("P5", "Subscriptions", "plan catalogue is readable", st == 200,
        f"{len(plans) if isinstance(plans, list) else plans} plans")

st, sub = call("GET", "/api/restaurant/subscription", token=R1)
r.check("P5", "Subscriptions", "subscription read returns data or a clean 404",
        st in (200, 404), f"HTTP {st}")

st, _ = call("PATCH", "/api/admin/subscriptions/1/status", token=R1,
             body={"status": "CANCELLED"})
r.check("P5", "Authorization", "restaurant cannot change subscription status",
        st == 403, f"HTTP {st}")

# ── Notifications ────────────────────────────────────────────────────────────
st, notif = call("GET", "/api/notifications?page=0&size=5", token=R1)
r.check("P5", "Notifications", "inbox lists notifications", st == 200,
        f"total={notif.get('total')} unread={notif.get('unreadCount')}")

unread_before = notif.get("unreadCount")
call("POST", "/api/notifications/read-all", token=R1)
st2, after = call("GET", "/api/notifications/unread-count", token=R1)
r.check("P5", "Notifications", "mark-all-read clears the unread count",
        after.get("unreadCount") == 0, f"{unread_before} -> {after.get('unreadCount')}")

st, filtered = call("GET", "/api/notifications?unreadOnly=true", token=R1)
r.check("P5", "Notifications", "unread filter returns nothing once all are read",
        st == 200 and len(filtered.get("items", [])) == 0,
        f"{len(filtered.get('items', []))} unread")

# ── Creator surfaces ─────────────────────────────────────────────────────────
st, crep = call("GET", "/api/reports/creator/summary", token=C1)
r.check("P5", "Creator", "creator summary responds", st == 200,
        f"scans={crep.get('scans')} customers={crep.get('verifiedCustomers')}")

st, creviews = call("GET", "/api/creator/reviews", token=C1)
r.check("P5", "Creator", "creator sees their own reviews", st == 200,
        f"{len(creviews) if isinstance(creviews, list) else creviews} reviews")

r.dump()
print(f"\nP5 rows: {len(r.rows)}  failures: {sum(1 for x in r.rows if x[3] == 'FAIL')}")