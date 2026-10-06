#!/usr/bin/env python3
"""Proves the cookie session survives a reload, the way the browser does it.

Login once, then refresh with BOTH the custom header and the echoed XSRF token.
"""
import json
import subprocess
import sys
import tempfile

ORIGIN = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.4:5173"
API = sys.argv[2] if len(sys.argv) > 2 else "http://192.168.1.4:8080"
USER = sys.argv[3] if len(sys.argv) > 3 else "admin@Trynearby"
PASSWORD = sys.argv[4] if len(sys.argv) > 4 else "DevOnly!Pass123"


def curl(args):
    return subprocess.run(["curl", "-s", *args], capture_output=True, text=True).stdout


jar = tempfile.NamedTemporaryFile(suffix=".jar", delete=False).name

# --- login (no pre-existing session, so nothing is replayed) ---
login = curl(["-X", "POST", f"{API}/api/auth/login",
              "-H", f"Origin: {ORIGIN}", "-H", "Content-Type: application/json",
              "-d", json.dumps({"userId": USER, "password": PASSWORD, "portal": "trynearby"}),
              "-c", jar, "-D", "-"])
print("login status      :", "200" if "accessToken" in login else "FAILED")

# --- read the two cookies curl stored ---
xsrf = refresh = None
for line in open(jar):
    f = line.rstrip("\n").split("\t")
    if len(f) >= 7:
        if f[5] == "XSRF-TOKEN":
            xsrf = f[6]
        if f[5] == "ct_refresh":
            refresh = f[6]
print("XSRF-TOKEN stored :", "yes" if xsrf else "NO")
print("ct_refresh stored :", "yes" if refresh else "NO")

# --- first (and only) refresh: what the fixed frontend sends ---
out = curl(["-o", "/tmp/_rf.json", "-w", "%{http_code}", "-X", "POST", f"{API}/api/auth/refresh",
            "-b", jar, "-H", f"Origin: {ORIGIN}", "-H", "X-CT-CSRF: 1",
            "-H", f"X-XSRF-TOKEN: {xsrf}", "-H", "Content-Type: application/json"])
print("refresh status    :", out)
body = json.load(open("/tmp/_rf.json"))
print("new access token  :", f"{len(body.get('accessToken',''))} chars"
      if body.get("accessToken") else f"MISSING ({body.get('message')})")

# --- negatives: CSRF must still be enforced ---
creator: Portal = "creator"
password: str = "DevOnly!Pass123"


def login(user: str, portal: Portal) -> str:
    body = json.dumps({"userId": user, "password": password, "portal": portal})
    out = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST", f"{API}/api/auth/login",
                "-H", "Content-Type: application/json", "-d", body])
    if out != "200":
        raise SystemExit(f"login failed for {user} ({portal}): HTTP {out}")
    token = curl(["-X", "POST", f"{API}/api/auth/login",
                  "-H", "Content-Type: application/json", "-d", body])
    return json.loads(token)["accessToken"]


def main() -> None:
    admin = login("admin@Trynearby", "trynearby")

    creators = json.loads(curl(["-H", f"Authorization: Bearer {admin}",
                               f"{API}/api/admin/creators"]))
    restaurants = json.loads(curl(["-H", f"Authorization: Bearer {admin}",
                                  f"{API}/api/admin/restaurants"]))
    print(f"admin directory      : {len(creators)} creators, {len(restaurants)} restaurants")

    # A creator with no assignment at all, so the effect of assigning is observable.
    fresh = "wf-verify@creator"
    try:
        ctoken = login(fresh, "creator")
    except SystemExit:
        print(f"skipping: no {fresh} account in this environment")
        return

    before = json.loads(curl(["-H", f"Authorization: Bearer {ctoken}",
                              f"{API}/api/creator/assignments"]))
    print(f"creator assignments before : {len(before)}")

    rid = restaurants[0]["id"]
    cid = creators[0]["id"]
    created = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                    f"{API}/api/admin/assignments",
                    "-H", f"Authorization: Bearer {admin}",
                    "-H", "Content-Type: application/json",
                    "-d", json.dumps({"restaurantId": rid, "creatorId": cid})])
    print(f"assign HTTP               : {created}")

    # Duplicate must be refused, not silently accepted.
    dup = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                f"{API}/api/admin/assignments",
                "-H", f"Authorization: Bearer {admin}",
                "-H", "Content-Type: application/json",
                "-d", json.dumps({"restaurantId": rid, "creatorId": cid})])
    print(f"duplicate HTTP            : {dup} (409 expected)")

    after = json.loads(curl(["-H", f"Authorization: Bearer {ctoken}",
                             f"{API}/api/creator/assignments"]))
    print(f"creator assignments after  : {len(after)}")
    for row in after:
        print(f"  - {row['creatorName']} → {row['restaurantName']} "
              f"(active={row['active']})")


if __name__ == "__main__":
    main()
for label, headers in [
    ("X-CT-CSRF only, no echo", ["-H", "X-CT-CSRF: 1"]),
    ("echo only, no X-CT-CSRF", ["-H", f"X-XSRF-TOKEN: {xsrf}"]),
    ("neither header", []),
    ("forged echo value", ["-H", "X-CT-CSRF: 1",
                           "-H", "X-XSRF-TOKEN: 11111111-2222-3333-4444-555555555555"]),
]:
    code = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                 f"{API}/api/auth/refresh", "-b", jar, "-H", f"Origin: {ORIGIN}",
                 "-H", "Content-Type: application/json", *headers])
    print(f"  {label:26} -> {code}")