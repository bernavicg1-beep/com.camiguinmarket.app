#!/usr/bin/env python3
"""Behavioral test sa firestore.rules gamit ang Firestore emulator.

Gi-run ni sa CI sulod sa `firebase emulators:exec`, busa anaa na ang emulator
sa 127.0.0.1:8080. Kon adunay ni-fail nga check, mo-exit kini og 1 ug
ipasundayag ang tanan nga resulta.

Wala kini magkinahanglan og tinuod nga Firebase credentials: ang emulator
nagadawat sa unsigned JWT isip user token.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request

PROJECT = os.environ.get("FIREBASE_PROJECT", "demo-camiguin")
HOST = os.environ.get("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8080")
BASE = f"http://{HOST}/v1/projects/{PROJECT}/databases/(default)/documents"


def b64(obj):
    raw = json.dumps(obj).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(uid):
    """Unsigned JWT nga giila sa emulator isip naka-log in nga user."""
    header = b64({"alg": "none", "typ": "JWT"})
    payload = b64(
        {
            "sub": uid,
            "user_id": uid,
            "aud": PROJECT,
            "iss": f"https://securetoken.google.com/{PROJECT}",
            "iat": 0,
            "exp": 9999999999,
            "auth_time": 0,
            "firebase": {"sign_in_provider": "password"},
        }
    )
    return f"{header}.{payload}."


def request(method, path, uid=None, body=None, doc_id=None):
    url = BASE + path + (f"?documentId={doc_id}" if doc_id else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if uid:
        req.add_header("Authorization", "Bearer " + token(uid))
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code


def listing(seller, title="Saging", price=25):
    return {
        "fields": {
            "sellerId": {"stringValue": seller},
            "title": {"stringValue": title},
            "price": {"integerValue": str(price)},
            "contact": {"stringValue": "0917"},
        }
    }


CHECKS = []


def check(name, method, path, expected, **kwargs):
    CHECKS.append((name, method, path, expected, kwargs))


# --- anonymous ---------------------------------------------------------------
check("anon: read sa listing list", "GET", "/listings", 403)
check("anon: himo og listing", "POST", "/listings", 403, body=listing("u1"), doc_id="anon")

# --- authenticated user ------------------------------------------------------
check("user: read sa listing list", "GET", "/listings", 200, uid="u1")
check("user: himo sa kaugalingong listing", "POST", "/listings", 200,
      uid="u1", body=listing("u1"), doc_id="doc1")
check("user: himo og listing sa ngalan sa uban", "POST", "/listings", 403,
      uid="u1", body=listing("u2"), doc_id="doc2")
check("user: sayop nga tipo sa price", "POST", "/listings", 403, uid="u1",
      body={"fields": {"sellerId": {"stringValue": "u1"},
                       "title": {"stringValue": "x"},
                       "price": {"stringValue": "barato"},
                       "contact": {"stringValue": "1"}}},
      doc_id="doc3")

# --- update ------------------------------------------------------------------
check("user: usbon ang kaugalingong listing", "PATCH", "/listings/doc1", 200,
      uid="u1", body=listing("u1", title="Saging nga hinog"))
check("owner: i-transfer ang listing sa laing seller", "PATCH", "/listings/doc1", 403,
      uid="u1", body=listing("u2"))

# --- delete ------------------------------------------------------------------
check("uban: papason ang listing sa laing seller", "DELETE", "/listings/doc1", 403, uid="u2")
check("tag-iya: papason ang kaugalingong listing", "DELETE", "/listings/doc1", 200, uid="u1")


def main():
    failures = 0
    print(f"Firestore emulator: {HOST}  (project: {PROJECT})")
    for name, method, path, expected, kwargs in CHECKS:
        actual = request(method, path, **kwargs)
        ok = actual == expected
        if not ok:
            failures += 1
        print(f"{'PASS' if ok else 'FAIL'}  [{actual}] {name} (gipaabot: {expected})")

    total = len(CHECKS)
    print(f"\n{total - failures}/{total} ka check ang milusot")
    if failures:
        print(f"❌ {failures} ka check ang ni-fail — sayop ang firestore.rules.")
        return 1
    print("✅ Husto ang firestore.rules.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
