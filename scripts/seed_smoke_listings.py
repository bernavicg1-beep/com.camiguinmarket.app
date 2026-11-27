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

Each workflow job exports ``SMOKE_SEED_SUFFIX`` so its ids and seller id are its
own. The suffix carries the job, the run id and the attempt, and a digest of
that whole value, so two overlapping runs can never share document ids: without
that, parallel runs overwrite each other's documents and one run's cleanup
deletes the listings another run is still testing against. ``cleanup`` only ever
deletes documents whose id ends with this run's suffix and whose seller id is
this run's exact seller id.

Usage:
  scripts/seed-smoke-listings.sh seed      # write, then report what is readable
  scripts/seed-smoke-listings.sh verify    # read-only: what does the project hold
  scripts/seed-smoke-listings.sh cleanup   # delete this suffix's docs and orders

Exit codes: 0 ok, 1 missing/invalid credential when SMOKE_SEED_REQUIRED=1,
2 unknown mode.
"""

import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

# Titles always start with this: it is what the suite looks for on screen.
TITLE_PREFIX = "Smoke seed"
SELLER_NAME = "Smoke Seed Seller"
CONTACT = "09171234567"
CATEGORY = "Food"

def strict_slug(raw: str) -> str:
    """A short suffix that stays unique after shortening.

    Truncating the raw suffix (job + run) at a fixed length dropped the run
    number for the longer job names, so two overlapping runs of the same job
    produced the same document ids and the same seller id: the newer run
    overwrote the older one's seeds, and the older run's cleanup then deleted
    the newer run's documents while it was still testing against them. The
    digest of the whole raw value keeps two runs distinct even when the readable
    part has to be cut short.
    """
    clean = re.sub(r"[^a-z0-9]+", "-", raw.strip().lower()).strip("-")
    if not clean:
        return ""
    digest = hashlib.sha1(clean.encode()).hexdigest()[:8]
    return f"{clean[:24].strip('-')}-{digest}"


_RAW_SUFFIX = os.environ.get("SMOKE_SEED_SUFFIX", "")
SUFFIX = strict_slug(_RAW_SUFFIX)
SELLER_ID = f"smoke-seed-seller-{SUFFIX}" if SUFFIX else "smoke-seed-seller"
# Every smoke seller shares this prefix, which is what the cleanup sweep uses.
STALE_SELLER_PREFIX = "smoke-seed-seller"
# Seeds older than this were left behind by a run that never reached cleanup.
STALE_AFTER_MS = 2 * 60 * 60 * 1000

# Fixed document ids keep re-runs idempotent: seeding overwrites, cleanup
# deletes exactly these. The coordinates sit around Mambajao so a location fix
# would not filter them out.
LISTINGS = {
    "smoke-seed-bananas": {
        "title": f"{TITLE_PREFIX} bananas",
        "description": "Seeded bananas for the cart, checkout and share smoke tests.",
        "price": 250.0,
        "town": "Mambajao",
        "latitude": 9.2505,
        "longitude": 124.7167,
    },
    "smoke-seed-fish": {
        "title": f"{TITLE_PREFIX} fresh fish",
        "description": "Seeded fish for the offline feed and nearby-radius smoke tests.",
        "price": 180.0,
        "town": "Mambajao",
        "latitude": 9.2489,
        "longitude": 124.7180,
    },
    "smoke-seed-copra": {
        "title": f"{TITLE_PREFIX} copra sack",
        "description": "Seeded copra for the second nearby-radius smoke test listing.",
        "price": 900.0,
        "town": "Catarman",
        "latitude": 9.1397,
        "longitude": 124.6889,
    },
}

FIRESTORE = "https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents"


def doc_id(base: str) -> str:
    return f"{base}-{SUFFIX}" if SUFFIX else base


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
    base = FIRESTORE.format(project=project)
    now_ms = int(time.time() * 1000)
    for index, (name, spec) in enumerate(LISTINGS.items()):
        identifier = doc_id(name)
        url = f"{base}/listings/{identifier}"
        # Slightly increasing timestamps keep the order deterministic.
        payload = {"fields": fields_for(spec, now_ms + index)}
        with request(token, url, "PATCH", payload) as resp:
            written = json.load(resp)
        # A patch that reports success but carries no fields would create an
        # empty document, which an `orderBy("createdAt")` feed silently skips.
        written_fields = sorted((written.get("fields") or {}).keys())
        print(f"seeded {written.get('name', url)} fields={len(written_fields)}")
        if "createdAt" not in written_fields or "title" not in written_fields:
            print(f"::error::listing {identifier} was written without its fields")
            return 1
    print(f"Seeded {len(LISTINGS)} listings for seller {SELLER_ID} in project {project}.")
    return verify(token, project, "after-seed")


def verify(token: str, project: str, label: str) -> int:
    """Reports what the project returns for the feed's own query shape."""
    query = {
        "structuredQuery": {
            "from": [{"collectionId": "listings"}],
            "orderBy": [{"field": {"fieldPath": "createdAt"}, "direction": "DESCENDING"}],
            "limit": 30,
        }
    }
    url = f"{FIRESTORE.format(project=project)}:runQuery"
    try:
        with request(token, url, "POST", query) as resp:
            rows = json.load(resp)
    except (urllib.error.HTTPError, urllib.error.URLError) as err:
        print(f"::warning::Could not verify seeded listings ({label}): {err}")
        return 0
    titles = []
    owners: dict[str, int] = {}
    for row in rows:
        document = row.get("document") or {}
        fields = document.get("fields") or {}
        title = (fields.get("title") or {}).get("stringValue")
        seller = (fields.get("sellerId") or {}).get("stringValue") or "?"
        if title:
            titles.append(title)
            owners[seller] = owners.get(seller, 0) + 1
    seeded = [title for title in titles if title.startswith(TITLE_PREFIX)]
    print(
        f"verify({label}): project={project} seller={SELLER_ID} "
        f"total={len(titles)} seeded={len(seeded)} owners={owners} titles={seeded}"
    )
    return 0


def delete_doc(token: str, url: str) -> None:
    try:
        with request(token, url, "DELETE") as resp:
            resp.read()
    except urllib.error.HTTPError as err:
        if err.code != 404:
            raise


def own_listing_ids(token: str, base: str) -> set[str]:
    """Document ids that belong to this run only.

    The seller id carries the run's suffix, so this exact-equality query cannot
    return another job's seeds. Each document is still re-checked against the
    suffix by [cleanup] before it is deleted.
    """
    query = {
        "structuredQuery": {
            "from": [{"collectionId": "listings"}],
            "where": {"fieldFilter": {
                "field": {"fieldPath": "sellerId"},
                "op": "EQUAL",
                "value": {"stringValue": SELLER_ID}}},
            "limit": 100,
        }
    }
    try:
        with request(token, f"{base}:runQuery", "POST", query) as resp:
            rows = json.load(resp)
    except (urllib.error.HTTPError, urllib.error.URLError) as err:
        print(f"::warning::Could not list this run's seeded listings: {err}")
        return set()
    return {
        document["name"].rsplit("/", 1)[-1]
        for document in (row.get("document") or {} for row in rows)
        if document.get("name")
    }


def sweep_leftovers(token: str, base: str) -> None:
    """Removes smoke listings left behind by runs that never reached cleanup.

    A seed is only swept when it belongs to a *different* seller and is older
    than [STALE_AFTER_MS] (2 h, while a job is capped at 30 min), so a parallel
    job or an overlapping run of this job keeps its fixtures for its whole run.
    """
    query = {
        "structuredQuery": {
            "from": [{"collectionId": "listings"}],
            "where": {
                "compositeFilter": {
                    "op": "AND",
                    "filters": [
                        {"fieldFilter": {"field": {"fieldPath": "sellerId"},
                                         "op": "GREATER_THAN_OR_EQUAL",
                                         "value": {"stringValue": STALE_SELLER_PREFIX}}},
                        {"fieldFilter": {"field": {"fieldPath": "sellerId"},
                                         "op": "LESS_THAN_OR_EQUAL",
                                         "value": {"stringValue": STALE_SELLER_PREFIX + "\uf8ff"}}},
                    ],
                }
            },
            "limit": 100,
        }
    }
    try:
        with request(token, f"{base}:runQuery", "POST", query) as resp:
            rows = json.load(resp)
    except (urllib.error.HTTPError, urllib.error.URLError) as err:
        print(f"::warning::Could not sweep stale smoke listings: {err}")
        return
    cutoff = int(time.time() * 1000) - STALE_AFTER_MS
    removed = 0
    kept = 0
    for row in rows:
        document = row.get("document") or {}
        fields = document.get("fields") or {}
        seller = (fields.get("sellerId") or {}).get("stringValue") or ""
        title = (fields.get("title") or {}).get("stringValue") or ""
        created = int((fields.get("createdAt") or {}).get("integerValue") or 0)
        if not document.get("name") or not title.startswith(TITLE_PREFIX):
            continue
        # Never touch this run's own documents, and never touch a seed another
        # run is young enough to still be using.
        if seller == SELLER_ID or not 0 < created < cutoff:
            kept += 1
            continue
        delete_doc(token, f"https://firestore.googleapis.com/v1/{document['name']}")
        removed += 1
    if removed:
        print(f"Removed {removed} stale smoke listing(s) from earlier runs ({kept} kept).")
    else:
        print(f"No stale smoke listings to remove ({kept} kept).")


def cleanup(token: str, project: str) -> int:
    base = FIRESTORE.format(project=project)
    # This run's documents only: the derived ids plus whatever the seller id
    # query returns. A parallel job has a different suffix, so its seeds are
    # never in this set, and the suffix check below refuses anything that is not
    # provably ours.
    targets = {doc_id(name) for name in LISTINGS} | own_listing_ids(token, base)
    removed = 0
    for identifier in sorted(targets):
        if SUFFIX and not identifier.endswith(f"-{SUFFIX}"):
            print(f"::warning::Skipping listings/{identifier}: it belongs to another run")
            continue
        delete_doc(token, f"{base}/listings/{identifier}")
        removed += 1
        print(f"removed listings/{identifier}")
    print(f"Removed {removed} seeded listing(s) for seller {SELLER_ID}.")

    sweep_leftovers(token, base)

    # Orders written by the cart test point at the seeded listings; remove them
    # so repeated runs do not accumulate junk in the project.
    query = {"structuredQuery": {"from": [{"collectionId": "orders"}],
                                 "where": {"fieldFilter": {
                                     "field": {"fieldPath": "sellerId"},
                                     "op": "EQUAL",
                                     "value": {"stringValue": SELLER_ID}}}},
             "limit": 100}
    try:
        with request(token, f"{base}:runQuery", "POST", query) as resp:
            rows = json.load(resp)
        removed = 0
        for row in rows:
            document = row.get("document") or {}
            seller = (((document.get("fields") or {}).get("sellerId") or {})
                      .get("stringValue"))
            # Belt and braces, as for the listings: only this run's orders go.
            if document.get("name") and seller == SELLER_ID:
                delete_doc(token, f"https://firestore.googleapis.com/v1/{document['name']}")
                removed += 1
        print(f"Removed {removed} seeded order(s).")
    except (urllib.error.HTTPError, urllib.error.URLError) as err:
        # Cleanup must never hide the test result; report and carry on.
        print(f"::warning::Could not remove seeded orders: {err}")

    return 0


def main(argv: list[str]) -> int:
    mode = argv[1] if len(argv) > 1 else "seed"
    if mode not in ("seed", "verify", "cleanup"):
        print(f"Unknown mode '{mode}'. Use 'seed', 'verify' or 'cleanup'.", file=sys.stderr)
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
    if mode == "cleanup":
        return cleanup(token, project)
    if mode == "verify":
        return verify(token, project, "on-demand")
    return seed(token, project)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
