#!/usr/bin/env python3
"""Seed (or remove) deterministic listings for the emulator smoke suite.

The listings belong to a synthetic seller, ``smoke-seed-seller``, not to the
smoke-test account. That matters: the app hides "Add to cart" on a listing the
signed-in user owns, so a listing posted by the test account could never
exercise the cart and checkout flow.

Writes go through the Firestore Admin REST API with a service-account token, so
the synthetic seller never needs a Firebase Auth user and the Firestore rules
(which only allow ``sellerId == request.auth.uid``) are not weakened. The
credential is read from ``FIREBASE_SERVICE_ACCOUNT`` (the JSON itself, or a path
to it) and is never printed.

Usage:
  scripts/seed-smoke-listings.sh seed
  scripts/seed-smoke-listings.sh cleanup

Exit codes: 0 ok, 1 missing/invalid credential when SMOKE_SEED_REQUIRED=1,
2 unknown mode.
"""

import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

SELLER_ID = "smoke-seed-seller"
SELLER_NAME = "Smoke Seed Seller"
CONTACT = "09171234567"
CATEGORY = "Food"

# Fixed document ids keep re-runs idempotent: seeding overwrites, cleanup
# deletes exactly these. Titles share the "Smoke seed" prefix the suite looks
# for, and the coordinates sit around Mambajao so a location fix would not
# filter them out.
LISTINGS = {
    "smoke-seed-bananas": {
        "title": "Smoke seed bananas",
        "description": "Seeded bananas for the cart, checkout and share smoke tests.",
        "price": 250.0,
        "town": "Mambajao",
        "latitude": 9.2505,
        "longitude": 124.7167,
    },
    "smoke-seed-fish": {
        "title": "Smoke seed fresh fish",
        "description": "Seeded fish for the offline feed and nearby-radius smoke tests.",
        "price": 180.0,
        "town": "Mambajao",
        "latitude": 9.2489,
        "longitude": 124.7180,
    },
    "smoke-seed-copra": {
        "title": "Smoke seed copra sack",
        "description": "Seeded copra for the second nearby-radius smoke test listing.",
        "price": 900.0,
        "town": "Catarman",
        "latitude": 9.1397,
        "longitude": 124.6889,
    },
}

FIRESTORE = "https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents"


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def access_token(service_account: dict) -> str:
    """Mints a datastore-scoped OAuth token without extra dependencies."""
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(
        json.dumps(
            {
                "iss": service_account["client_email"],
                "scope": "https://www.googleapis.com/auth/datastore",
                "aud": service_account["token_uri"],
                "iat": now,
                "exp": now + 3600,
            }
        ).encode()
    )
    signing_input = f"{header}.{claims}"

    key_path = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as handle:
            handle.write(service_account["private_key"])
            key_path = handle.name
        os.chmod(key_path, 0o600)
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input.encode(),
            capture_output=True,
            check=True,
        ).stdout
    finally:
        if key_path and os.path.exists(key_path):
            os.unlink(key_path)

    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": f"{signing_input}.{b64url(signature)}",
        }
    ).encode()
    with urllib.request.urlopen(service_account["token_uri"], data=body, timeout=30) as resp:
        return json.load(resp)["access_token"]


def fields_for(spec: dict, created_ms: int) -> dict:
    return {
        "title": {"stringValue": spec["title"]},
        "description": {"stringValue": spec["description"]},
        "price": {"doubleValue": spec["price"]},
        "category": {"stringValue": CATEGORY},
        "town": {"stringValue": spec["town"]},
        "sellerId": {"stringValue": SELLER_ID},
        "sellerName": {"stringValue": SELLER_NAME},
        "contact": {"stringValue": CONTACT},
        "createdAt": {"integerValue": str(created_ms)},
        "latitude": {"doubleValue": spec["latitude"]},
        "longitude": {"doubleValue": spec["longitude"]},
        "imageUrl": {"stringValue": ""},
        "imageUrls": {"arrayValue": {}},
        "views": {"integerValue": "0"},
        "cartAdds": {"integerValue": "0"},
        "isPerishable": {"booleanValue": False},
        "expiresAt": {"integerValue": "0"},
    }


def request(token: str, url: str, method: str = "GET", payload: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Authorization": f"Bearer {token}"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    return urllib.request.urlopen(req, timeout=30)


def seed(token: str, project: str) -> int:
    now_ms = int(time.time() * 1000)
    for index, (doc_id, spec) in enumerate(LISTINGS.items()):
        url = f"{FIRESTORE.format(project=project)}/listings/{doc_id}"
        # Slightly increasing timestamps keep the order deterministic.
        payload = {"fields": fields_for(spec, now_ms + index)}
        with request(token, url, "PATCH", payload) as resp:
            resp.read()
        print(f"seeded listings/{doc_id}")
    print(f"Seeded {len(LISTINGS)} listings for seller {SELLER_ID}.")
    return 0


def delete_doc(token: str, url: str) -> None:
    try:
        with request(token, url, "DELETE") as resp:
            resp.read()
    except urllib.error.HTTPError as err:
        if err.code != 404:
            raise


def cleanup(token: str, project: str) -> int:
    base = FIRESTORE.format(project=project)
    for doc_id in LISTINGS:
        delete_doc(token, f"{base}/listings/{doc_id}")
        print(f"removed listings/{doc_id}")

    # Orders written by the cart test point at the seeded listings; remove them
    # so repeated runs do not accumulate junk in the project.
    query = {"structuredQuery": {"from": [{"collectionId": "orders"}],
                                 "where": {"fieldFilter": {
                                     "field": {"fieldPath": "sellerId"},
                                     "op": "EQUAL",
                                     "value": {"stringValue": SELLER_ID}}}}}
    try:
        with request(token, f"{base}:runQuery", "POST", query) as resp:
            rows = json.load(resp)
        removed = 0
        for row in rows:
            doc = row.get("document")
            if doc and doc.get("name"):
                delete_doc(token, f"https://firestore.googleapis.com/v1/{doc['name']}")
                removed += 1
        print(f"Removed {removed} seeded order(s).")
    except (urllib.error.HTTPError, urllib.error.URLError) as err:
        # Cleanup must never hide the test result; report and carry on.
        print(f"::warning::Could not remove seeded orders: {err}")

    print(f"Removed {len(LISTINGS)} seeded listings.")
    return 0


def main(argv: list[str]) -> int:
    mode = argv[1] if len(argv) > 1 else "seed"
    if mode not in ("seed", "cleanup"):
        print(f"Unknown mode '{mode}'. Use 'seed' or 'cleanup'.", file=sys.stderr)
        return 2

    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not raw:
        message = "FIREBASE_SERVICE_ACCOUNT is not set; smoke listings were not seeded."
        if os.environ.get("SMOKE_SEED_REQUIRED") == "1":
            print(f"::error::{message}", file=sys.stderr)
            return 1
        print(f"::notice::{message} Set SMOKE_SEED_REQUIRED=1 to make this fatal.")
        return 0

    try:
        if os.path.isfile(raw):
            with open(raw, encoding="utf-8") as handle:
                service_account = json.load(handle)
        else:
            service_account = json.loads(raw)
    except (OSError, json.JSONDecodeError) as err:
        print(f"::error::FIREBASE_SERVICE_ACCOUNT is not valid JSON: {err}", file=sys.stderr)
        return 1

    missing = [key for key in ("client_email", "private_key", "token_uri") if not service_account.get(key)]
    if missing:
        print(f"::error::FIREBASE_SERVICE_ACCOUNT is missing: {', '.join(missing)}", file=sys.stderr)
        return 1

    project = service_account.get("project_id") or "camiguinmarket"
    token = access_token(service_account)
    return cleanup(token, project) if mode == "cleanup" else seed(token, project)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
