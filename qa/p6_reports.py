"""Phase 6 — report figures cross-checked against persisted database rows."""
import subprocess
import sys

sys.path.insert(0, "/Users/mansoor/Desktop/Customer Trace/qa")
from lib import Report, call, token_for  # noqa: E402

r = Report()


def db(sql):
    out = subprocess.run(
        ["docker", "exec", "ct-mysql", "mysql", "-uroot", "-prootpw", "-N", "-e", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


def q(sql):
    return int(db(sql) or 0)


ADMIN = token_for("admin@Trynearby", "trynearby")
R1 = token_for("copper@restrnt", "restaurant")
R2 = token_for("malabar@restrnt", "restaurant")
C1 = token_for("pooja@creator", "creator")

_, me1 = call("GET", "/api/auth/me", token=R1)
_, me2 = call("GET", "/api/auth/me", token=R2)
REST1, REST2 = me1["restaurantId"], me2["restaurantId"]

WINDOW = "scanned_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"
RWIN = "redeemed_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"

# ── Restaurant report vs database ────────────────────────────────────────────
st, s1 = call("GET", "/api/reports/restaurant/summary", token=R1)
db_scans = q(f"SELECT COUNT(*) FROM customertrace.scan_events WHERE restaurant_id={REST1} "
             f"AND valid=1 AND {WINDOW}")
db_reds = q(f"SELECT COUNT(*) FROM customertrace.redemptions WHERE restaurant_id={REST1} "
            f"AND {RWIN}")
db_cust = q(f"SELECT COUNT(DISTINCT customer_ref) FROM customertrace.redemptions "
            f"WHERE restaurant_id={REST1} AND {RWIN}")

r.check("P6", "Restaurant report", "scan count matches the database",
        s1.get("scans") == db_scans, f"api={s1.get('scans')} db={db_scans}")
r.check("P6", "Restaurant report", "redemption count matches the database",
        s1.get("redemptions") == db_reds, f"api={s1.get('redemptions')} db={db_reds}")
r.check("P6", "Restaurant report", "verified customers match DISTINCT customer_ref",
        s1.get("verifiedCustomers") == db_cust,
        f"api={s1.get('verifiedCustomers')} db={db_cust}")

# The central product claim: verified customers are never a scan count.
r.check("P6", "Core invariant", "verified customers are NOT the scan count",
        s1.get("verifiedCustomers") != s1.get("scans") or s1.get("scans") == 0,
        f"scans={s1.get('scans')} customers={s1.get('verifiedCustomers')}")

# Conversion is a percentage of scans, zero-safe.
expected_conv = 0.0 if not s1.get("scans") else round(
    s1["verifiedCustomers"] * 10000 / s1["scans"]) / 100
r.check("P6", "Restaurant report", "conversion rate is consistent with its inputs",
        abs((s1.get("conversionRatePercent") or 0) - expected_conv) < 0.02,
        f"api={s1.get('conversionRatePercent')} expected={expected_conv}")

# ── Tenant isolation in reports ──────────────────────────────────────────────
st, s2 = call("GET", "/api/reports/restaurant/summary", token=R2)
r.check("P6", "Tenant isolation", "two restaurants report different figures",
        (s1.get("verifiedCustomers"), s1.get("scans")) !=
        (s2.get("verifiedCustomers"), s2.get("scans")),
        f"R1={s1.get('scans')}/{s1.get('verifiedCustomers')} "
        f"R2={s2.get('scans')}/{s2.get('verifiedCustomers')}")

st, s1b = call("GET", "/api/reports/restaurant/summary", token=R1)
r.check("P6", "Tenant isolation", "restaurant figures do not drift between calls",
        s1b.get("scans") == s1.get("scans") and
        s1b.get("verifiedCustomers") == s1.get("verifiedCustomers"), "stable")

st, plat = call("GET", "/api/reports/platform/summary", token=ADMIN)
r.check("P6", "Platform report", "platform scans cover every restaurant",
        plat.get("totalScans") >= (s1.get("scans", 0) + s2.get("scans", 0)),
        f"platform={plat.get('totalScans')} "
        f"r1+r2={s1.get('scans', 0) + s2.get('scans', 0)}")

# ── Date-range boundaries ────────────────────────────────────────────────────
# The backend caps a range at 366 days, so "wide" is the maximum permitted window and
# "narrow" is a single day inside it.
wide_from = "2025-01-01"
wide_to = "2025-12-31"
one_day = "2025-06-15"

st, wide = call("GET", f"/api/reports/restaurant/summary?from={wide_from}&to={wide_to}",
                token=R1)
st, narrow = call("GET", f"/api/reports/restaurant/summary?from={one_day}&to={one_day}",
                  token=R1)
r.check("P6", "Date range", "the maximum permitted range is accepted", st == 200,
        f"HTTP {st} scans={wide.get('scans')}")
r.check("P6", "Date range", "a year-wide range returns at least a single day",
        (wide.get("scans") or 0) >= (narrow.get("scans") or 0),
        f"wide={wide.get('scans')} one-day={narrow.get('scans')}")
r.check("P6", "Date range", "the echoed range matches the request",
        wide.get("from") == wide_from and wide.get("to") == wide_to,
        f"{wide.get('from')}..{wide.get('to')}")

st, empty = call("GET", "/api/reports/restaurant/summary?from=1990-01-01&to=1990-01-02",
                 token=R1)
r.check("P6", "Date range", "an empty range reports zeros, not an error",
        st == 200 and empty.get("scans") == 0 and empty.get("verifiedCustomers") == 0,
        f"HTTP {st} scans={empty.get('scans')}")

st, bad = call("GET", "/api/reports/restaurant/summary?from=2026-05-01&to=2026-01-01", token=R1)
r.check("P6", "Date range", "an inverted range is rejected", st == 400, f"HTTP {st}")

st, _ = call("GET", "/api/reports/restaurant/summary?from=2010-01-01&to=2026-01-01", token=R1)
r.check("P6", "Date range", "an over-long range is rejected", st == 400, f"HTTP {st}")

# ── Creator report is scoped to that creator ─────────────────────────────────
C2 = token_for("aiswarya@creator", "creator")
_, me1c = call("GET", "/api/auth/me", token=C1)
_, me2c = call("GET", "/api/auth/me", token=C2)
st, crep = call("GET", "/api/reports/creator/summary", token=C1)
db_cre_scans = q(f"SELECT COUNT(*) FROM customertrace.scan_events s "
                 f"JOIN customertrace.campaigns c ON c.id=s.campaign_id "
                 f"WHERE c.creator_id={me1c['creatorId']} AND s.valid=1 AND {WINDOW}")
r.check("P6", "Creator report", "creator scans match its own campaigns only",
        crep.get("scans") == db_cre_scans,
        f"api={crep.get('scans')} db={db_cre_scans}")

st, crep2 = call("GET", "/api/reports/creator/summary", token=C2)
r.check("P6", "Creator report", "a second creator reports different figures",
        (crep.get("scans"), crep.get("verifiedCustomers")) !=
        (crep2.get("scans"), crep2.get("verifiedCustomers")),
        f"c1={crep.get('scans')} c2={crep2.get('scans')}")

# ── Per-campaign performance rows ────────────────────────────────────────────
st, perf = call("GET", "/api/reports/creator/campaigns", token=C1)
rows_ok = all(row.get("verifiedCustomers", 0) <= row.get("targetCustomers", 0)
              for row in perf)
r.check("P6", "Per-campaign", "no campaign exceeds its customer target", rows_ok,
        f"{len(perf)} rows checked")
r.check("P6", "Per-campaign", "per-campaign rows expose a promo code field",
        all("promoCode" in row for row in perf), "field present")

st, platform_perf = call("GET", "/api/reports/platform/campaigns", token=ADMIN)
r.check("P6", "Platform report", "platform campaign performance is readable by admin",
        st == 200, f"{len(platform_perf)} rows")

r.dump()
print(f"\nP6 rows: {len(r.rows)}  failures: {sum(1 for x in r.rows if x[3] == 'FAIL')}")