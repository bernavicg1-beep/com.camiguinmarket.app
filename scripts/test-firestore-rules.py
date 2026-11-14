#!/usr/bin/env python3
"""Behavioral test sa firestore.rules gamit ang Firestore emulator.

Gi-run ni sa CI sulod sa `firebase emulators:exec`, busa anaa na ang emulator
sa 127.0.0.1:8080. Kon adunay ni-fail nga check, mo-exit kini og 1 ug
ipasundayag ang tanan nga resulta.

Wala kini magkinahanglan og tinuod nga Firebase credentials: ang emulator
nagadawat sa unsigned JWT isip user token.

Duha ka matang sa check ang anaa:
  1. Kadtong nagsulay sa mga rules mismo (anon, tag-iya, laing user, tipo sa data).
  2. Kadtong nagsundog sa **eksaktong dokumento nga isulat sa app** (`Listing`
     data class) — apil ang `id = ""`, `price` nga double, `createdAt` nga long,
     ug `latitude`/`longitude` nga `null` kon walay pickup pin. Kini ang
     mopugong sa regression nga makababag sa pag-post, pagbasa, ug pagpapas.
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


def request(method, path, uid=None, body=None, doc_id=None, order_by=None):
    params = []
    if doc_id:
        params.append("documentId=" + doc_id)
    if order_by:
        params.append("orderBy=" + order_by.replace(" ", "%20"))
    url = BASE + path + ("?" + "&".join(params) if params else "")
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


def app_listing(seller, pickup=False):
    """Eksaktong fields nga isulat sa `Listing` data class sa app."""
    fields = {
        "id": {"stringValue": ""},  # ang doc id gikan sa Firestore, walay sulod dinhi
        "title": {"stringValue": "Saging nga hinog"},
        "description": {"stringValue": "Presko gikan sa Mambajao"},
        "price": {"doubleValue": 25.5},
        "category": {"stringValue": "Pagkaon ug Seafood"},
        "town": {"stringValue": "Mambajao"},
        "sellerId": {"stringValue": seller},
        "sellerName": {"stringValue": "Nena"},
        "contact": {"stringValue": "09171234567"},
        "createdAt": {"integerValue": "1763000000000"},
    }
    if pickup:
        fields["latitude"] = {"doubleValue": 9.25}
        fields["longitude"] = {"doubleValue": 124.7}
    else:
        # Ang app nagabutang og null kon wala pa makapili og pickup point.
        fields["latitude"] = {"nullValue": None}
        fields["longitude"] = {"nullValue": None}
    return {"fields": fields}


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

# --- eksaktong data sa app (regression guard) --------------------------------
# Ang app: db.collection("listings").add(Listing(...)) nga walay pickup pin.
check("app: post og listing (latitude/longitude = null)", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="appdoc1")
# Ang app: pareho, apan dunay napiling pickup point sa mapa.
check("app: post og listing nga dunay pickup pin", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1", pickup=True), doc_id="appdoc2")
# Ang app: market list -> orderBy("createdAt", DESCENDING).
check("app: market list (orderBy createdAt desc)", "GET", "/listings", 200,
      uid="u1", order_by="createdAt desc")
# Ang app: ablihan ang usa ka listing.
check("app: ablihan ang usa ka listing", "GET", "/listings/appdoc2", 200, uid="u2")
# Ang app: laing account makakita sa listing sa uban.
check("app: laing user makabasa", "GET", "/listings/appdoc1", 200, uid="u2")
# Ang app: laing account dili makapapas.
check("app: laing user dili makapapas", "DELETE", "/listings/appdoc2", 403, uid="u2")
# Ang app: laing account dili makahimo og listing sa ngalan sa tag-iya.
check("app: laing user dili makahimo sa ngalan sa uban", "POST", "/listings", 403,
      uid="u2", body=app_listing("u1"), doc_id="appdoc3")
# Ang app (account deletion): whereEqualTo("sellerId", uid).get().
check("app: query sa account deletion", "GET", "/listings", 200, uid="u1")
# Ang app: document(id).delete() sa tag-iya.
check("app: tag-iya makapapas sa listing", "DELETE", "/listings/appdoc1", 200, uid="u1")


# --- cart --------------------------------------------------------------------
cart_doc = {"fields": {
    "listingId": {"stringValue": "appdoc1"},
    "title": {"stringValue": "Saging"},
    "price": {"doubleValue": 25.5},
    "sellerId": {"stringValue": "u2"},
    "quantity": {"integerValue": "1"},
}}
check("cart: tag-iya makasulat", "POST", "/carts/u1/items", 200,
      uid="u1", body=cart_doc, doc_id="appdoc1")
check("cart: tag-iya makabasa", "GET", "/carts/u1/items", 200, uid="u1")
check("cart: uban dili makabasa", "GET", "/carts/u1/items", 403, uid="u2")
check("cart: uban dili makasulat", "POST", "/carts/u1/items", 403,
      uid="u2", body=cart_doc, doc_id="x")

# --- paborito ----------------------------------------------------------------
fav_doc = {"fields": {
    "listingId": {"stringValue": "appdoc1"},
    "title": {"stringValue": "Saging"},
    "price": {"doubleValue": 25.5},
    "createdAt": {"integerValue": "1"},
}}
check("paborito: tag-iya makasulat", "POST", "/favorites/u1/items", 200,
      uid="u1", body=fav_doc, doc_id="appdoc1")
check("paborito: uban dili makabasa", "GET", "/favorites/u1/items", 403, uid="u2")

# --- notifications -----------------------------------------------------------
notif_doc = lambda sender: {"fields": {
    "fromUid": {"stringValue": sender},
    "fromName": {"stringValue": "Nena"},
    "message": {"stringValue": "Nena nagdugang sa cart: Saging"},
    "createdAt": {"integerValue": "1"},
    "read": {"booleanValue": False},
}}
check("notif: makahimo para sa uban", "POST", "/notifications/u2/items", 200,
      uid="u1", body=notif_doc("u1"), doc_id="n1")
check("notif: dili makahimo sa ngalan sa uban", "POST", "/notifications/u2/items", 403,
      uid="u1", body=notif_doc("u9"), doc_id="n2")
check("notif: tag-iya makabasa", "GET", "/notifications/u2/items", 200, uid="u2")
check("notif: uban dili makabasa", "GET", "/notifications/u2/items", 403, uid="u1")

# --- chat --------------------------------------------------------------------
chat_doc = {"fields": {
    "participants": {"arrayValue": {"values": [
        {"stringValue": "u1"}, {"stringValue": "u2"}
    ]}},
    "updatedAt": {"integerValue": "1"},
}}
msg_doc = lambda sender: {"fields": {
    "from": {"stringValue": sender},
    "text": {"stringValue": "Pila ang saging?"},
    "createdAt": {"integerValue": "1"},
}}
check("chat: makahimo sa kaugalingong thread", "POST", "/chats", 200,
      uid="u1", body=chat_doc, doc_id="u1_u2")
check("chat: participant makabasa", "GET", "/chats/u1_u2", 200, uid="u2")
check("chat: taga-gawas dili makabasa", "GET", "/chats/u1_u2", 403, uid="u3")
check("chat: participant makasulat og mensahe", "POST", "/chats/u1_u2/messages", 200,
      uid="u2", body=msg_doc("u2"), doc_id="m1")
check("chat: taga-gawas dili makasulat og mensahe", "POST", "/chats/u1_u2/messages", 403,
      uid="u3", body=msg_doc("u3"), doc_id="m2")
check("chat: makabasa sa mga mensahe", "GET", "/chats/u1_u2/messages", 200, uid="u1")


# --- orders ------------------------------------------------------------------
def order_doc(buyer, seller, status="pending"):
    return {"fields": {
        "buyerId": {"stringValue": buyer},
        "buyerName": {"stringValue": "Nena"},
        "sellerId": {"stringValue": seller},
        "listingTitle": {"stringValue": "Saging"},
        "price": {"doubleValue": 25.5},
        "quantity": {"integerValue": "2"},
        "total": {"doubleValue": 51.0},
        "status": {"stringValue": status},
        "createdAt": {"integerValue": "1"},
    }}


check("order: buyer makahimo", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2"), doc_id="o1")
check("order: dili makahimo sa ngalan sa uban", "POST", "/orders", 403,
      uid="u1", body=order_doc("u3", "u2"), doc_id="o2")
check("order: buyer makabasa", "GET", "/orders/o1", 200, uid="u1")
check("order: seller makabasa", "GET", "/orders/o1", 200, uid="u2")
check("order: taga-gawas dili makabasa", "GET", "/orders/o1", 403, uid="u3")
check("order: seller maka-update sa status", "PATCH", "/orders/o1", 200,
      uid="u2", body=order_doc("u1", "u2", status="confirmed"))
check("order: buyer dili maka-update", "PATCH", "/orders/o1", 403,
      uid="u1", body=order_doc("u1", "u2", status="done"))

# o3: pending, para makita ang cancel sa buyer.
check("order: ikaduha nga order", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2"), doc_id="o3")
check("order: buyer makakanselar sa pending", "PATCH", "/orders/o3", 200,
      uid="u1", body=order_doc("u1", "u2", status="cancelled"))
check("order: buyer dili makakanselar og confirmed", "PATCH", "/orders/o1", 403,
      uid="u1", body=order_doc("u1", "u2", status="cancelled"))
check("order: seller makakanselar", "PATCH", "/orders/o1", 200,
      uid="u2", body=order_doc("u1", "u2", status="cancelled"))
check("order: taga-gawas dili maka-cancel", "PATCH", "/orders/o3", 403,
      uid="u3", body=order_doc("u1", "u2", status="cancelled"))


# --- moderation --------------------------------------------------------------
def seed(path, doc_id, body):
    """Pagsulat pinaagi sa emulator owner bypass (dili maagi sa rules)."""
    url = BASE + path + "?documentId=" + doc_id
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer owner")
    with urllib.request.urlopen(req) as resp:
        return resp.status


seed("/admins", "admin1", {"fields": {"active": {"booleanValue": True}}})

check("mod: ang admins mabasa sa naka-log in", "GET", "/admins/admin1", 200, uid="u1")
check("mod: dili makasulat sa admins gikan sa app", "POST", "/admins", 403,
      uid="u1", body={"fields": {"active": {"booleanValue": True}}}, doc_id="fake")

check("mod: listing sa u2 alang sa moderation", "POST", "/listings", 200,
      uid="u2", body=app_listing("u2"), doc_id="mod1")
check("mod: dili-admin dili makapapas og listing sa uban", "DELETE", "/listings/mod1", 403,
      uid="u3")
check("mod: admin makapapas sa listing sa uban", "DELETE", "/listings/mod1", 200,
      uid="admin1")


check("mod: listing para sa non-admin delete", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="mod2")
check("mod: dili-admin dili makapapas (2)", "DELETE", "/listings/mod2", 403, uid="u3")


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
