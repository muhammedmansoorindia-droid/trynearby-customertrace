#!/usr/bin/env python3
"""Proves the generated-code rules end to end against a running stack.

Logs in as a creator, submits a campaign at each discount, and reports the generated code, whether
its letter matches the discount, and whether the code is also the campaign's name.
"""
import json
import subprocess
import sys

API = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
CREATOR = sys.argv[2] if len(sys.argv) > 2 else "aiswarya@creator"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "DevOnly!Pass123"

LETTERS = {pct: chr(ord("B") + pct - 2) for pct in range(2, 11)}
TARGET, DAYS = 25, 14
# An elapsed month, so a previous run's ledger rows cannot mask this one's result.
START, END = "2026-08-01", "2026-08-31"


def curl(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


def auth(token):
    return ["-H", f"Authorization: Bearer {token}"]


token = json.loads(curl([
    "-X", "POST", f"{API}/api/auth/login", "-H", "Content-Type: application/json",
    "-d", json.dumps({"userId": CREATOR, "password": PASSWORD, "portal": "creator"}),
])).get("accessToken")
print("creator login   :", "ok" if token else "FAILED")
if not token:
    raise SystemExit(1)

assignments = json.loads(curl([*auth(token), f"{API}/api/creator/assignments"]))
active = [a for a in assignments if a["active"]]
print(f"assigned venues : {len(active)}")
if not active:
    raise SystemExit("no active assignment; assign one in the admin portal first")
rid = active[0]["restaurantId"]
print(f"using restaurant #{rid}\n")

results = []
for discount in sorted(LETTERS):
    payload = {"restaurantId": rid, "offerText": f"{discount}% off", "discountPercent": discount,
               "targetCustomers": TARGET, "durationDays": DAYS,
               "startDate": START, "endDate": END}
    status = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                   f"{API}/api/creator/campaigns", *auth(token),
                   "-H", "Content-Type: application/json", "-d", json.dumps(payload)])
    if status != "201":
        print(f"  {discount:>2}%: draft refused (HTTP {status})")
        continue

    draft = json.loads(curl(["-X", "POST", f"{API}/api/creator/campaigns", *auth(token),
                             "-H", "Content-Type: application/json", "-d", json.dumps(payload)]))
    cid = draft["id"]
    status = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                   f"{API}/api/creator/campaigns/{cid}/submit", *auth(token)])
    detail = json.loads(curl([*auth(token), f"{API}/api/creator/campaigns/{cid}"]))
    code = detail.get("promoCode")
    if status != "200":
        print(f"  {discount:>2}% (letter {LETTERS[discount]}): submit HTTP {status}"
              f"  {detail.get('message', '')}")
        continue
    results.append((discount, code, detail.get("title")))
    print(f"  {discount:>2}% (letter {LETTERS[discount]}) code={code}"
          f"  letter_ok={code[6] == LETTERS[discount]}"
          f"  segments_ok={code[2:4] == '25' and code[4:6] == '14'}")

print()
for discount, code, title in results:
    print(f"  {discount}% code {code} is the campaign name: {title == code}")
print(f"\nrestricted (7-10%) issued: {sum(1 for d, _, _ in results if d >= 7)} of 4 possible")