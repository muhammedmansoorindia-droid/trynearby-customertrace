"""Shared helpers for the Customer Trace QA driver.

Talks to a running backend over HTTP. Reads the generated dev password from
/tmp/qa-pass.txt so no password is ever hard-coded here.
"""
import json
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PASSWORD = open("/tmp/qa-pass.txt").read().strip()


def call(method, path, token=None, body=None, raw=None):
    """Returns (status, parsed_json_or_text). Never raises on HTTP errors."""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if raw is not None:
        data = raw.encode()
        headers["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except json.JSONDecodeError:
            return e.code, text
    except urllib.error.URLError as e:
        return 0, {"message": str(e)}


def login(user_id, portal, password=None):
    return call("POST", "/api/auth/login",
                body={"userId": user_id, "password": password or PASSWORD, "portal": portal})


def token_for(user_id, portal):
    st, body = login(user_id, portal)
    assert st == 200, f"login failed for {user_id}: {st} {body}"
    return body["accessToken"]


def code_of(body):
    return body.get("code") if isinstance(body, dict) else None


class Report:
    """Accumulates PASS/FAIL rows for the final table."""

    def __init__(self):
        self.rows = []

    def check(self, phase, feature, scenario, ok, evidence=""):
        self.rows.append((phase, feature, scenario, "PASS" if ok else "FAIL", evidence))
        return ok

    def note(self, phase, feature, scenario, result, evidence=""):
        self.rows.append((phase, feature, scenario, result, evidence))

    def dump(self):
        width = max(len(r[2]) for r in self.rows)
        for phase, feature, scenario, result, evidence in self.rows:
            print(f"[{result:4}] {feature:26} | {scenario:<{width}} | {evidence}")