#!/usr/bin/env python3
"""Behavioral tests for firestore.rules using the Firestore emulator.

CI runs this inside `firebase emulators:exec`, so the emulator is already
at 127.0.0.1:8080. If any check fails it exits with 1 and
prints every result.

No real Firebase credentials are needed: the emulator
accepts an unsigned JWT as the user token.

There are two kinds of checks:
  1. Those that exercise the rules themselves (anon, owner, other user, data types).
  2. Those that mimic the **exact documents the app writes** (`Listing`
     data class) — including `id = ""`, `price` as double, `createdAt` as long,
     and `latitude`/`longitude` as `null` when there is no pickup pin. These
     catch regressions that would break posting, reading, and deleting.
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
    """Unsigned JWT that the emulator accepts as a signed-in user."""
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


def request(method, path, uid=None, body=None, doc_id=None, order_by=None, mask=None):
    params = []
    if doc_id:
        params.append("documentId=" + doc_id)
    if order_by:
        params.append("orderBy=" + order_by.replace(" ", "%20"))
    if mask:
        # Pareho sa app: update() sa usa ra ka field (increment).
        params.append("updateMask.fieldPaths=" + mask)
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
    """Exact fields written by the app's `Listing` data class."""
    fields = {
        "id": {"stringValue": ""},  # the doc id comes from Firestore, empty here
        "title": {"stringValue": "Ripe bananas"},
        "description": {"stringValue": "Fresh from Mambajao"},
        "price": {"doubleValue": 25.5},
        "category": {"stringValue": "Food & Seafood"},
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
check("anon: read the listing list", "GET", "/listings", 403)
check("anon: create a listing", "POST", "/listings", 403, body=listing("u1"), doc_id="anon")

# --- authenticated user ------------------------------------------------------
check("user: read the listing list", "GET", "/listings", 200, uid="u1")
check("user: create their own listing", "POST", "/listings", 200,
      uid="u1", body=listing("u1"), doc_id="doc1")
check("user: create a listing in someone else's name", "POST", "/listings", 403,
      uid="u1", body=listing("u2"), doc_id="doc2")
check("user: wrong type for price", "POST", "/listings", 403, uid="u1",
      body={"fields": {"sellerId": {"stringValue": "u1"},
                       "title": {"stringValue": "x"},
                       "price": {"stringValue": "barato"},
                       "contact": {"stringValue": "1"}}},
      doc_id="doc3")

# --- update ------------------------------------------------------------------
check("user: update their own listing", "PATCH", "/listings/doc1", 200,
      uid="u1", body=listing("u1", title="Ripe bananas"))
check("owner: transfer the listing to another seller", "PATCH", "/listings/doc1", 403,
      uid="u1", body=listing("u2"))

# --- delete ------------------------------------------------------------------
check("other: delete another seller's listing", "DELETE", "/listings/doc1", 403, uid="u2")
check("owner: delete their own listing", "DELETE", "/listings/doc1", 200, uid="u1")

# --- eksaktong data sa app (regression guard) --------------------------------
# Ang app: db.collection("listings").add(Listing(...)) nga walay pickup pin.
check("app: post a listing (latitude/longitude = null)", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="appdoc1")
# Ang app: pareho, apan dunay napiling pickup point sa mapa.
check("app: post a listing with a pickup pin", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1", pickup=True), doc_id="appdoc2")
# Ang app: market list -> orderBy("createdAt", DESCENDING).
check("app: market list (orderBy createdAt desc)", "GET", "/listings", 200,
      uid="u1", order_by="createdAt desc")
# Ang app: open a listing.
check("app: open a listing", "GET", "/listings/appdoc2", 200, uid="u2")
# Ang app: laing account makakita sa listing sa uban.
check("app: another user can read", "GET", "/listings/appdoc1", 200, uid="u2")
# Ang app: laing account dili makapapas.
check("app: another user cannot delete", "DELETE", "/listings/appdoc2", 403, uid="u2")
# Ang app: laing account dili makahimo og listing sa ngalan sa tag-iya.
check("app: another user cannot create in someone else's name", "POST", "/listings", 403,
      uid="u2", body=app_listing("u1"), doc_id="appdoc3")
# Ang app (account deletion): whereEqualTo("sellerId", uid).get().
check("app: account deletion query", "GET", "/listings", 200, uid="u1")
# Ang app: document(id).delete() sa tag-iya.
check("app: owner can delete the listing", "DELETE", "/listings/appdoc1", 200, uid="u1")


# --- cart --------------------------------------------------------------------
cart_doc = {"fields": {
    "listingId": {"stringValue": "appdoc1"},
    "title": {"stringValue": "Saging"},
    "price": {"doubleValue": 25.5},
    "sellerId": {"stringValue": "u2"},
    "quantity": {"integerValue": "1"},
}}
check("cart: owner can write", "POST", "/carts/u1/items", 200,
      uid="u1", body=cart_doc, doc_id="appdoc1")
check("cart: owner can read", "GET", "/carts/u1/items", 200, uid="u1")
check("cart: others cannot read", "GET", "/carts/u1/items", 403, uid="u2")
check("cart: others cannot write", "POST", "/carts/u1/items", 403,
      uid="u2", body=cart_doc, doc_id="x")

# --- paborito ----------------------------------------------------------------
fav_doc = {"fields": {
    "listingId": {"stringValue": "appdoc1"},
    "title": {"stringValue": "Saging"},
    "price": {"doubleValue": 25.5},
    "createdAt": {"integerValue": "1"},
}}
check("favorites: owner can write", "POST", "/favorites/u1/items", 200,
      uid="u1", body=fav_doc, doc_id="appdoc1")
check("favorites: others cannot read", "GET", "/favorites/u1/items", 403, uid="u2")

# --- notifications -----------------------------------------------------------
notif_doc = lambda sender: {"fields": {
    "fromUid": {"stringValue": sender},
    "fromName": {"stringValue": "Nena"},
    "message": {"stringValue": "Nena nagdugang sa cart: Saging"},
    "createdAt": {"integerValue": "1"},
    "read": {"booleanValue": False},
}}
check("notification: can create for someone else", "POST", "/notifications/u2/items", 200,
      uid="u1", body=notif_doc("u1"), doc_id="n1")
check("notification: cannot create in someone else's name", "POST", "/notifications/u2/items", 403,
      uid="u1", body=notif_doc("u9"), doc_id="n2")
check("notification: owner can read", "GET", "/notifications/u2/items", 200, uid="u2")
check("notification: others cannot read", "GET", "/notifications/u2/items", 403, uid="u1")

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
check("chat: can create their own thread", "POST", "/chats", 200,
      uid="u1", body=chat_doc, doc_id="u1_u2")
check("chat: participant can read", "GET", "/chats/u1_u2", 200, uid="u2")
check("chat: outsider cannot read", "GET", "/chats/u1_u2", 403, uid="u3")
check("chat: participant can write a message", "POST", "/chats/u1_u2/messages", 200,
      uid="u2", body=msg_doc("u2"), doc_id="m1")
check("chat: outsider cannot write a message", "POST", "/chats/u1_u2/messages", 403,
      uid="u3", body=msg_doc("u3"), doc_id="m2")
check("chat: can read the messages", "GET", "/chats/u1_u2/messages", 200, uid="u1")


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


check("order: buyer can create", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2"), doc_id="o1")
check("order: cannot create in someone else's name", "POST", "/orders", 403,
      uid="u1", body=order_doc("u3", "u2"), doc_id="o2")
check("order: buyer can read", "GET", "/orders/o1", 200, uid="u1")
check("order: seller can read", "GET", "/orders/o1", 200, uid="u2")
check("order: outsider cannot read", "GET", "/orders/o1", 403, uid="u3")
check("order: seller can update the status", "PATCH", "/orders/o1", 200,
      uid="u2", body=order_doc("u1", "u2", status="confirmed"))
check("order: buyer cannot update", "PATCH", "/orders/o1", 403,
      uid="u1", body=order_doc("u1", "u2", status="done"))

# o3: pending, para makita ang cancel sa buyer.
check("order: second order", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2"), doc_id="o3")
check("order: buyer can cancel while pending", "PATCH", "/orders/o3", 200,
      uid="u1", body=order_doc("u1", "u2", status="cancelled"))
check("order: buyer cannot cancel a confirmed order", "PATCH", "/orders/o1", 403,
      uid="u1", body=order_doc("u1", "u2", status="cancelled"))
check("order: seller can cancel", "PATCH", "/orders/o1", 200,
      uid="u2", body=order_doc("u1", "u2", status="cancelled"))
check("order: outsider cannot cancel", "PATCH", "/orders/o3", 403,
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

check("mod: admins are readable by signed-in users", "GET", "/admins/admin1", 200, uid="u1")
check("mod: cannot write admins from the app", "POST", "/admins", 403,
      uid="u1", body={"fields": {"active": {"booleanValue": True}}}, doc_id="fake")

check("mod: u2 listing for moderation", "POST", "/listings", 200,
      uid="u2", body=app_listing("u2"), doc_id="mod1")
check("mod: non-admin cannot delete someone else's listing", "DELETE", "/listings/mod1", 403,
      uid="u3")
check("mod: admin can delete someone else's listing", "DELETE", "/listings/mod1", 200,
      uid="admin1")


check("mod: listing for non-admin delete", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="mod2")
check("mod: non-admin cannot delete (2)", "DELETE", "/listings/mod2", 403, uid="u3")

# --- reports -----------------------------------------------------------------
def report_doc(listing_id, reporter, reason="Scam o pangilad"):
    return {"fields": {
        "listingId": {"stringValue": listing_id},
        "listingTitle": {"stringValue": "Saging"},
        "sellerId": {"stringValue": "u2"},
        "sellerName": {"stringValue": "Nena"},
        "reporterUid": {"stringValue": reporter},
        "reporterName": {"stringValue": "Juan"},
        "reason": {"stringValue": reason},
        "note": {"stringValue": "Dili tinuod ang presyo."},
        "status": {"stringValue": "open"},
        "createdAt": {"integerValue": "1"},
    }}


check("report: signed-in user can create", "POST", "/reports", 200,
      uid="u1", body=report_doc("appdoc1", "u1"), doc_id="rep1")
check("report: cannot create in someone else's name", "POST", "/reports", 403,
      uid="u1", body=report_doc("appdoc1", "u3"), doc_id="rep2")
check("report: two reports for the list", "POST", "/reports", 200,
      uid="u2", body=report_doc("appdoc1", "u2"), doc_id="rep3")
check("report: reporter can read their own", "GET", "/reports/rep1", 200, uid="u1")
check("report: another user cannot read", "GET", "/reports/rep1", 403, uid="u2")
check("report: admin can read", "GET", "/reports/rep1", 200, uid="admin1")
check("report: admin can list", "GET", "/reports", 200, uid="admin1")
check("report: non-admin cannot update", "PATCH", "/reports/rep1", 403,
      uid="u1", body=report_doc("appdoc1", "u1"))


def _resolved(doc):
    doc = json.loads(json.dumps(doc))
    doc["fields"]["status"] = {"stringValue": "resolved"}
    return doc


check("report: admin can resolve", "PATCH", "/reports/rep1", 200,
      uid="admin1", body=_resolved(report_doc("appdoc1", "u1")))


# --- ratings -----------------------------------------------------------------
def rating_doc(order_id, buyer, seller, stars=5):
    return {"fields": {
        "orderId": {"stringValue": order_id},
        "sellerId": {"stringValue": seller},
        "buyerId": {"stringValue": buyer},
        "buyerName": {"stringValue": "Nena"},
        "rating": {"integerValue": str(stars)},
        "comment": {"stringValue": "Maayo ang seller"},
        "createdAt": {"integerValue": "1"},
    }}


check("rating: order for rating", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2"), doc_id="o4")
check("rating: not done yet, cannot rate", "POST", "/ratings", 403,
      uid="u1", body=rating_doc("o4", "u1", "u2"), doc_id="o4")
check("rating: seller marks done", "PATCH", "/orders/o4", 200,
      uid="u2", body=order_doc("u1", "u2", status="done"))
check("rating: buyer can rate after done", "POST", "/ratings", 200,
      uid="u1", body=rating_doc("o4", "u1", "u2"), doc_id="o4")
check("rating: cannot be edited", "PATCH", "/ratings/o4", 403,
      uid="u1", body=rating_doc("o4", "u1", "u2", stars=1))
check("rating: another user cannot rate", "POST", "/ratings", 403,
      uid="u3", body=rating_doc("o4", "u3", "u2"), doc_id="o5")
check("rating: readable by signed-in users", "GET", "/ratings/o4", 200, uid="u3")


# --- cartAdds ----------------------------------------------------------------
def listing_cart_patch(base, cart_adds, views=1):
    # PATCH dinhi nag-replace sa tibuok doc, busa tipigan ang kasamtangang views.
    doc = json.loads(json.dumps(base))
    doc["fields"]["views"] = {"integerValue": str(views)}
    doc["fields"]["cartAdds"] = {"integerValue": str(cart_adds)}
    return doc


check("cartAdds: listing for cart stats", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="cartdoc1")
check("cartAdds: another user can add +1", "PATCH", "/listings/cartdoc1", 200,
      uid="u2", mask="cartAdds", body=listing_cart_patch(app_listing("u1"), 1))
check("cartAdds: cannot add more than +1", "PATCH", "/listings/cartdoc1", 403,
      uid="u2", mask="cartAdds", body=listing_cart_patch(app_listing("u1"), 7))


# --- viewsByDay ---------------------------------------------------------------
def listing_daily_patch(base, views, day_count, day="2026-11-14"):
    doc = json.loads(json.dumps(base))
    doc["fields"]["views"] = {"integerValue": str(views)}
    doc["fields"]["viewsByDay"] = {
        "mapValue": {"fields": {day: {"integerValue": str(day_count)}}}
    }
    return doc


check("viewsByDay: listing for the chart", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="daydoc1")
check("viewsByDay: can add to the day and total", "PATCH", "/listings/daydoc1", 200,
      uid="u2", mask="views&updateMask.fieldPaths=viewsByDay",
      body=listing_daily_patch(app_listing("u1"), 1, 1))


# --- fcm tokens ---------------------------------------------------------------
token_doc = {"fields": {
    "token": {"stringValue": "abc123"},
    "updatedAt": {"integerValue": "1"},
}}
check("fcm: owner can write the token", "POST", "/users/u1/tokens", 200,
      uid="u1", body=token_doc, doc_id="tok1")
check("fcm: owner can read the token", "GET", "/users/u1/tokens", 200, uid="u1")
check("fcm: others cannot read the token", "GET", "/users/u1/tokens", 403, uid="u2")


# --- saved searches ----------------------------------------------------------
saved_doc = {"fields": {
    "query": {"stringValue": "saging"},
    "category": {"stringValue": "Tanan"},
    "town": {"stringValue": "Tanan"},
    "createdAt": {"integerValue": "1"},
    "lastCheckedAt": {"integerValue": "1"},
}}
check("saved: owner can write", "POST", "/savedSearches/u1/items", 200,
      uid="u1", body=saved_doc, doc_id="s1")
check("saved: owner can read", "GET", "/savedSearches/u1/items", 200, uid="u1")
check("saved: others cannot read", "GET", "/savedSearches/u1/items", 403, uid="u2")
check("saved: others cannot write", "POST", "/savedSearches/u1/items", 403,
      uid="u2", body=saved_doc, doc_id="s2")


# --- views -------------------------------------------------------------------
def listing_with_views(base, views):
    doc = json.loads(json.dumps(base))
    doc["fields"]["views"] = {"integerValue": str(views)}
    return doc


# appdoc1 gibuhat sa miaging mga test; kuhaa ang kasamtangan nga views.
import urllib.request as _req


def listing_views_patch(base, views, title=None):
    doc = listing_with_views(base, views)
    if title is not None:
        doc["fields"]["title"] = {"stringValue": title}
    return doc


# Bag-ong listing kay ang appdoc1 napapas na sa miaging test.
check("views: listing for views", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="viewdoc1")
check("views: another user can add +1", "PATCH", "/listings/viewdoc1", 200,
      uid="u2", mask="views", body=listing_views_patch(app_listing("u1"), 1))
check("views: cannot add more than +1", "PATCH", "/listings/viewdoc1", 403,
      uid="u2", body=listing_views_patch(app_listing("u1"), 3))
check("views: cannot change the title", "PATCH", "/listings/viewdoc1", 403,
      uid="u2", body=listing_views_patch(app_listing("u1"), 2, title="Gi-hack"))


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
    print(f"\n{total - failures}/{total} checks passed")
    if failures:
        print(f"❌ {failures} checks failed — firestore.rules is wrong.")
        return 1
    print("✅ Husto ang firestore.rules.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
