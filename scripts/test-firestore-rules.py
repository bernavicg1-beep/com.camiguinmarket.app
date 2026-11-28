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


def token(uid, phone=None):
    """Unsigned JWT that the emulator accepts as a signed-in user.

    `phone` adds the `phone_number` claim Firebase Auth sets after a phone
    number is verified, so the rules can trust the badge it backs.
    """
    header = b64({"alg": "none", "typ": "JWT"})
    claims = {
        "sub": uid,
        "user_id": uid,
        "aud": PROJECT,
        "iss": f"https://securetoken.google.com/{PROJECT}",
        "iat": 0,
        "exp": 9999999999,
        "auth_time": 0,
        "firebase": {"sign_in_provider": "password"},
    }
    if phone:
        claims["phone_number"] = phone
        claims["firebase"]["identities"] = {"phone": [phone]}
    payload = b64(claims)
    return f"{header}.{payload}."


def request(method, path, uid=None, body=None, doc_id=None, order_by=None, mask=None,
            phone=None):
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
        req.add_header("Authorization", "Bearer " + token(uid, phone))
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
        # The app writes null while no pickup point is chosen.
        fields["latitude"] = {"nullValue": None}
        fields["longitude"] = {"nullValue": None}
    return {"fields": fields}


def listing(seller, title="Banana", price=25):
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


# --- phone number on the user profile ---------------------------------------
# The badge is only trusted when the ID token carries the verified
# `phone_number` claim; a plain client write must not set it.
check("owner: self-set phoneVerified without a verified phone", "PATCH", "/users/u1", 403,
      uid="u1",
      body={"fields": {"phone": {"stringValue": "+639171234567"},
                       "phoneVerified": {"booleanValue": True}}})
check("owner: set phoneVerified with a verified phone claim", "PATCH", "/users/u1", 200,
      uid="u1", phone="+639171234567",
      body={"fields": {"phone": {"stringValue": "+639171234567"},
                       "phoneVerified": {"booleanValue": True}}})
check("owner: claim a phone number that was not verified", "PATCH", "/users/u1", 403,
      uid="u1", phone="+639171234567",
      body={"fields": {"phone": {"stringValue": "+639000000000"},
                       "phoneVerified": {"booleanValue": True}}})
check("owner: keep the verified phone while editing the profile", "PATCH", "/users/u1", 200,
      uid="u1",
      body={"fields": {"name": {"stringValue": "Nena"},
                       "phone": {"stringValue": "+639171234567"},
                       "phoneVerified": {"booleanValue": True}}})
check("owner: read their own profile document", "GET", "/users/u1", 200, uid="u1")
check("other: write someone else's phone number", "PATCH", "/users/u1", 403, uid="u2",
      body={"fields": {"phone": {"stringValue": "+639000000000"},
                       "phoneVerified": {"booleanValue": True}}})
check("owner: store a field the rules do not allow", "PATCH", "/users/u1", 403, uid="u1",
      body={"fields": {"phone": {"stringValue": "+639171234567"},
                       "isAdmin": {"booleanValue": True}}})

# --- store info: the seller's GCash number -----------------------------------
# The seller saves it in their store info and any signed-in buyer may read it
# (the profile read rule), which is how the payment prompt knows where the
# money goes. Only a well-formed PH mobile is accepted.
check("gcash: seller saves their GCash number", "PATCH", "/users/u2", 200, uid="u2",
      body={"fields": {"gcashNumber": {"stringValue": "09171234567"}}})
check("gcash: a spaced number is rejected", "PATCH", "/users/u2", 403, uid="u2",
      body={"fields": {"gcashNumber": {"stringValue": "0917 123 4567"}}})
check("gcash: a number that is not a PH mobile is rejected", "PATCH", "/users/u2", 403,
      uid="u2", body={"fields": {"gcashNumber": {"stringValue": "12345"}}})
check("gcash: the seller can clear the number", "PATCH", "/users/u2", 200, uid="u2",
      body={"fields": {"gcashNumber": {"stringValue": ""}}})
check("gcash: any signed-in buyer reads the seller's number", "GET", "/users/u2", 200, uid="u1")

# --- store info: contact links (Telegram / Messenger / WhatsApp) --------------
# The "Save links" button writes these three together; the rules must accept
# them (they were previously rejected because they were not in the allow-list).
check("links: owner saves telegram/messenger/whatsapp", "PATCH", "/users/u1", 200, uid="u1",
      body={"fields": {"telegram": {"stringValue": "camiguin_market"},
                       "messenger": {"stringValue": "CamiguinMarket"},
                       "whatsapp": {"stringValue": "+639171234567"}}})
check("links: a non-string value is rejected", "PATCH", "/users/u1", 403, uid="u1",
      body={"fields": {"telegram": {"booleanValue": True}}})
check("links: another user cannot write them", "PATCH", "/users/u1", 403, uid="u2",
      body={"fields": {"telegram": {"stringValue": "x"}}})
check("links: telegramVerified/messengerVerified are booleans", "PATCH", "/users/u1", 200, uid="u1",
      body={"fields": {"telegramVerified": {"booleanValue": True},
                       "messengerVerified": {"booleanValue": True}}})

# --- checkout idempotency markers -------------------------------------------
# checkoutCart() reads /checkouts/{id} before writing it; a missing document
# must be readable (404) rather than denied (403).
check("checkout: owner can read a missing marker", "GET", "/checkouts/missing-checkout", 404,
      uid="u1")
check("checkout: owner can create their marker", "POST", "/checkouts", 200,
      uid="u1", body={"fields": {"uid": {"stringValue": "u1"}}}, doc_id="checkout-a")
check("checkout: owner can read their marker", "GET", "/checkouts/checkout-a", 200, uid="u1")
check("checkout: another user cannot read the marker", "GET", "/checkouts/checkout-a", 403,
      uid="u2")
check("checkout: another user cannot create a marker for the owner", "POST", "/checkouts", 403,
      uid="u2", body={"fields": {"uid": {"stringValue": "u1"}}}, doc_id="checkout-b")
check("checkout: owner can update their marker", "PATCH", "/checkouts/checkout-a", 200,
      uid="u1", body={"fields": {"uid": {"stringValue": "u1"}}})
check("checkout: another user cannot update the marker", "PATCH", "/checkouts/checkout-a", 403,
      uid="u2", body={"fields": {"uid": {"stringValue": "u2"}}})
check("checkout: delete is denied", "DELETE", "/checkouts/checkout-a", 403, uid="u1")

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

# --- exact documents the app writes (regression guard) -----------------------
# The app: db.collection("listings").add(Listing(...)) without a pickup pin.
check("app: post a listing (latitude/longitude = null)", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="appdoc1")
# The app: the same, but with a pickup point chosen on the map.
check("app: post a listing with a pickup pin", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1", pickup=True), doc_id="appdoc2")
# The app: market list -> orderBy("createdAt", DESCENDING).
check("app: market list (orderBy createdAt desc)", "GET", "/listings", 200,
      uid="u1", order_by="createdAt desc")
# The app: open a listing.
check("app: open a listing", "GET", "/listings/appdoc2", 200, uid="u2")
# The app: another account can see other people's listings.
check("app: another user can read", "GET", "/listings/appdoc1", 200, uid="u2")
# The app: another account cannot delete it.
check("app: another user cannot delete", "DELETE", "/listings/appdoc2", 403, uid="u2")
# The app: another account cannot post a listing in the owner's name.
check("app: another user cannot create in someone else's name", "POST", "/listings", 403,
      uid="u2", body=app_listing("u1"), doc_id="appdoc3")
# The app (account deletion): whereEqualTo("sellerId", uid).get().
check("app: account deletion query", "GET", "/listings", 200, uid="u1")
# The app: document(id).delete() by the owner.
check("app: owner can delete the listing", "DELETE", "/listings/appdoc1", 200, uid="u1")


# --- cart --------------------------------------------------------------------
cart_doc = {"fields": {
    "listingId": {"stringValue": "appdoc1"},
    "title": {"stringValue": "Banana"},
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
    "title": {"stringValue": "Banana"},
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
    "message": {"stringValue": "Nena added to cart: Banana"},
    "createdAt": {"integerValue": "1"},
    "read": {"booleanValue": False},
}}
check("notification: can create in own inbox", "POST", "/notifications/u1/items", 200,
      uid="u1", body=notif_doc("u1"), doc_id="n1")
check("notification: cannot create in someone else's inbox", "POST", "/notifications/u2/items", 403,
      uid="u1", body=notif_doc("u1"), doc_id="n2")
check("notification: cannot create in someone else's name", "POST", "/notifications/u1/items", 403,
      uid="u1", body=notif_doc("u9"), doc_id="n3")
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
    "text": {"stringValue": "How much is the banana?"},
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
def order_doc(buyer, seller, status="pending", method="cash", paid="cod", ref=""):
    """Exact fields written by the app's `Order` data class at checkout."""
    return {"fields": {
        "buyerId": {"stringValue": buyer},
        "buyerName": {"stringValue": "Nena"},
        "sellerId": {"stringValue": seller},
        "listingTitle": {"stringValue": "Banana"},
        "price": {"doubleValue": 25.5},
        "quantity": {"integerValue": "2"},
        "total": {"doubleValue": 51.0},
        "status": {"stringValue": status},
        "paymentMethod": {"stringValue": method},
        "paymentStatus": {"stringValue": paid},
        "paymentRef": {"stringValue": ref},
        "paidAt": {"integerValue": "0"},
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


# --- GCash payments ----------------------------------------------------------
check("gcash: buyer can create an unpaid GCash order", "POST", "/orders", 200,
      uid="u1", body=order_doc("u1", "u2", method="gcash", paid="unpaid"), doc_id="og1")
check("gcash: buyer cannot create an already-paid order", "POST", "/orders", 403,
      uid="u1", body=order_doc("u1", "u2", method="gcash", paid="paid"), doc_id="og2")
check("gcash: a cash order must start as cod", "POST", "/orders", 403,
      uid="u1", body=order_doc("u1", "u2", method="cash", paid="unpaid"), doc_id="og3")
check("gcash: buyer records the reference (still not paid)", "PATCH", "/orders/og1", 200,
      uid="u1", body=order_doc("u1", "u2", method="gcash", paid="pending", ref="1234567890"))
check("gcash: buyer cannot mark the order paid", "PATCH", "/orders/og1", 403,
      uid="u1",
      body=order_doc("u1", "u2", method="gcash", paid="paid", ref="1234567890"))
check("gcash: only the seller verifies the payment", "PATCH", "/orders/og1", 200,
      uid="u2",
      body=order_doc("u1", "u2", method="gcash", paid="paid", ref="1234567890"))
check("gcash: the payment method cannot change after checkout", "PATCH", "/orders/og1", 403,
      uid="u2", body=order_doc("u1", "u2", method="cash", paid="cod"))
check("gcash: a cash order cannot be marked paid", "PATCH", "/orders/o1", 403,
      uid="u2", body=order_doc("u1", "u2", status="confirmed", method="cash", paid="paid"))


def legacy_order_doc(buyer, seller):
    """An order from an app version released before GCash: no payment fields."""
    body = order_doc(buyer, seller)
    for field in ("paymentMethod", "paymentStatus", "paymentRef", "paidAt"):
        body["fields"].pop(field, None)
    return body


check("gcash: an older app can still place an order", "POST", "/orders", 200,
      uid="u1", body=legacy_order_doc("u1", "u2"), doc_id="olegacy")
check("gcash: an older order can still change status", "PATCH", "/orders/olegacy", 200,
      uid="u2", body=legacy_order_doc("u1", "u2"))


# --- moderation --------------------------------------------------------------
def seed(path, doc_id, body):
    """Write through the emulator owner bypass (skips the rules)."""
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

# `admins` is written from the Firebase Console only, so the app must have no
# write path at all: no self-promotion, no overwriting a marker, no deletion —
# not even for someone who is already an admin. Otherwise a signed-in user
# could grant themselves moderation powers (or lock every admin out).
check("admin: a user cannot create a marker for themselves", "POST", "/admins", 403,
      uid="u3", doc_id="u3", body={"fields": {"active": {"booleanValue": True}}})
check("admin: a user cannot create a marker for someone else", "POST", "/admins", 403,
      uid="u3", doc_id="admin2", body={"fields": {"active": {"booleanValue": True}}})
check("admin: a user cannot overwrite an existing marker", "PATCH", "/admins/admin1", 403,
      uid="u3", body={"fields": {"active": {"booleanValue": True}}})
check("admin: a user cannot delete an existing marker", "DELETE", "/admins/admin1", 403,
      uid="u3")
check("admin: an admin cannot add another admin from the app", "POST", "/admins", 403,
      uid="admin1", doc_id="admin2", body={"fields": {"active": {"booleanValue": True}}})
check("admin: an admin cannot revoke their own marker from the app", "DELETE",
      "/admins/admin1", 403, uid="admin1")

check("mod: u2 listing for moderation", "POST", "/listings", 200,
      uid="u2", body=app_listing("u2"), doc_id="mod1")
check("mod: non-admin cannot delete someone else's listing", "DELETE", "/listings/mod1", 403,
      uid="u3")
check("mod: admin can delete someone else's listing", "DELETE", "/listings/mod1", 200,
      uid="admin1")


check("mod: listing for non-admin delete", "POST", "/listings", 200,
      uid="u1", body=app_listing("u1"), doc_id="mod2")
check("mod: non-admin cannot delete (2)", "DELETE", "/listings/mod2", 403, uid="u3")

# --- admin photo deletion + the Worker's admins check -------------------------
def app_listing_with_photos(seller, photos):
    """The app's `Listing` as written by PostDialog, with photos attached."""
    doc = app_listing(seller)
    doc["fields"]["imageUrl"] = {"stringValue": photos[0] if photos else ""}
    doc["fields"]["imageUrls"] = {
        "arrayValue": {"values": [{"stringValue": u} for u in photos]}
    }
    # Perishable fields from the same dialog (0 = never expires).
    doc["fields"]["isPerishable"] = {"booleanValue": False}
    doc["fields"]["expiresAt"] = {"integerValue": "0"}
    return doc


PHOTO_BASE = "https://worker.example/i/listing_images"
photos = [f"{PHOTO_BASE}/u2/p{i}.jpg" for i in range(6)]

# Photo cleanup in the app runs only after the document delete succeeds, so the
# rules must: (a) accept a listing that carries photos, and (b) allow the admin
# (never a normal user) to delete someone else's listing.
check("photos: seller posts a listing with 6 photos", "POST", "/listings", 200,
      uid="u2", body=app_listing_with_photos("u2", photos), doc_id="photodoc1")
check("photos: seven photos are rejected", "POST", "/listings", 403, uid="u2",
      body=app_listing_with_photos("u2", photos + [f"{PHOTO_BASE}/u2/p6.jpg"]),
      doc_id="photodoc2")
check("photos: another user can open the listing", "GET", "/listings/photodoc1", 200,
      uid="u3")
check("photos: non-admin cannot delete the listing", "DELETE", "/listings/photodoc1", 403,
      uid="u3")
check("photos: admin can delete the listing (photo cleanup runs after)", "DELETE",
      "/listings/photodoc1", 200, uid="admin1")

# The Worker decides "admin" by reading `admins/{uid}` with the service account;
# these checks pin what that lookup can see for each kind of caller.
check("worker: admin lookup finds the marker", "GET", "/admins/admin1", 200,
      uid="admin1")
check("worker: a normal user's lookup finds no marker", "GET", "/admins/u1", 404,
      uid="u1")
check("worker: anonymous admin lookup is denied", "GET", "/admins/admin1", 403)
check("worker: a user cannot make themselves an admin", "POST", "/admins", 403,
      uid="u3", body={"fields": {"active": {"booleanValue": True}}}, doc_id="u3")


# --- reports -----------------------------------------------------------------
def report_doc(listing_id, reporter, reason="Scam o pangilad"):
    return {"fields": {
        "listingId": {"stringValue": listing_id},
        "listingTitle": {"stringValue": "Banana"},
        "sellerId": {"stringValue": "u2"},
        "sellerName": {"stringValue": "Nena"},
        "reporterUid": {"stringValue": reporter},
        "reporterName": {"stringValue": "Juan"},
        "reason": {"stringValue": reason},
        "note": {"stringValue": "The price is not real."},
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
    # PATCH replaces the whole document here, so keep the current views.
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
    "query": {"stringValue": "banana"},
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


# appdoc1 was created by the earlier tests; read its current views.
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



# --- trust layer: user verification fields -----------------------------------
# Owner cannot self-verify: `verified`/`verifiedBy`/`verifiedAt` are not in the
# owner allow-list.
check("trust: owner cannot set verified on own profile", "PATCH", "/users/u1", 403,
      uid="u1", body={"fields": {"uid": {"stringValue": "u1"},
                                 "verified": {"booleanValue": True},
                                 "verifiedBy": {"stringValue": "self"},
                                 "verifiedAt": {"integerValue": "1"}}})
check("trust: owner cannot add verified to an allowed update", "PATCH", "/users/u1", 403,
      uid="u1", body={"fields": {"phone": {"stringValue": "+639171234567"},
                                 "phoneVerified": {"booleanValue": True},
                                 "verified": {"booleanValue": True}}})

# Seed a user doc so an admin can update its verification fields.
seed("/users", "trustuser", {"fields": {"uid": {"stringValue": "trustuser"},
                                        "name": {"stringValue": "Trust User"},
                                        "email": {"stringValue": "t@example.com"}}})

check("trust: admin can set verified fields", "PATCH", "/users/trustuser", 200,
      uid="admin1", mask="verified&updateMask.fieldPaths=verifiedBy&updateMask.fieldPaths=verifiedAt",
      body={"fields": {"verified": {"booleanValue": True},
                       "verifiedBy": {"stringValue": "admin:admin1"},
                       "verifiedAt": {"integerValue": "123"}}})
check("trust: admin cannot change non-verification fields", "PATCH", "/users/trustuser", 403,
      uid="admin1", mask="name",
      body={"fields": {"name": {"stringValue": "Hacked"}}})
check("trust: non-admin cannot set verified fields", "PATCH", "/users/trustuser", 403,
      uid="u1", mask="verified&updateMask.fieldPaths=verifiedBy&updateMask.fieldPaths=verifiedAt",
      body={"fields": {"verified": {"booleanValue": True},
                       "verifiedBy": {"stringValue": "admin:u1"},
                       "verifiedAt": {"integerValue": "1"}}})
check("trust: admin can revoke verification", "PATCH", "/users/trustuser", 200,
      uid="admin1", mask="verified&updateMask.fieldPaths=verifiedBy&updateMask.fieldPaths=verifiedAt",
      body={"fields": {"verified": {"booleanValue": False},
                       "verifiedBy": {"stringValue": ""},
                       "verifiedAt": {"integerValue": "0"}}})


# --- trust layer: verificationRequests ---------------------------------------
vr_doc = lambda seller, name="Nena": {"fields": {
    "sellerUid": {"stringValue": seller},
    "sellerName": {"stringValue": name},
    "status": {"stringValue": "pending"},
    "createdAt": {"integerValue": "1"},
}}

check("vr: seller can create own request", "POST", "/verificationRequests", 200,
      uid="u1", body=vr_doc("u1"), doc_id="vr1")
check("vr: cannot create in someone else's name", "POST", "/verificationRequests", 403,
      uid="u1", body=vr_doc("u2"), doc_id="vr2")
check("vr: status must be pending", "POST", "/verificationRequests", 403,
      uid="u1", body={"fields": {"sellerUid": {"stringValue": "u1"},
                                 "sellerName": {"stringValue": "Nena"},
                                 "status": {"stringValue": "approved"},
                                 "createdAt": {"integerValue": "1"}}},
      doc_id="vr3")
check("vr: requester can read own", "GET", "/verificationRequests/vr1", 200, uid="u1")
check("vr: other user cannot read", "GET", "/verificationRequests/vr1", 403, uid="u2")
check("vr: admin can read", "GET", "/verificationRequests/vr1", 200, uid="admin1")
check("vr: admin can list", "GET", "/verificationRequests", 200, uid="admin1")
check("vr: non-admin cannot update", "PATCH", "/verificationRequests/vr1", 403,
      uid="u1", body={"fields": {"status": {"stringValue": "approved"}}})
check("vr: admin can approve", "PATCH", "/verificationRequests/vr1", 200,
      uid="admin1", body={"fields": {"status": {"stringValue": "approved"},
                                    "reviewedBy": {"stringValue": "admin1"},
                                    "reviewedAt": {"integerValue": "2"}}})
check("vr: cannot be deleted (even by admin)", "DELETE", "/verificationRequests/vr1", 403,
      uid="admin1")


# --- announcements (admin dashboard) ----------------------------------------
ann_doc = lambda title="Market day", message="Buy local this Saturday.": {"fields": {
    "title": {"stringValue": title},
    "message": {"stringValue": message},
    "authorUid": {"stringValue": "admin1"},
    "authorName": {"stringValue": "Admin"},
    "active": {"booleanValue": True},
    "createdAt": {"integerValue": "1"},
}}

check("ann: admin posts an announcement", "POST", "/announcements", 200,
      uid="admin1", body=ann_doc(), doc_id="ann1")
check("ann: a normal user cannot post", "POST", "/announcements", 403, uid="u1",
      body=ann_doc(), doc_id="ann2")
check("ann: an empty title is rejected", "POST", "/announcements", 403, uid="admin1",
      body=ann_doc(title=""), doc_id="ann3")
check("ann: an over-long message is rejected", "POST", "/announcements", 403, uid="admin1",
      body=ann_doc(message="x" * 501), doc_id="ann4")
check("ann: any signed-in user reads them", "GET", "/announcements/ann1", 200, uid="u1")
check("ann: anonymous cannot read them", "GET", "/announcements/ann1", 403)
check("ann: a normal user cannot hide one", "PATCH", "/announcements/ann1", 403, uid="u1",
      body={"fields": {"active": {"booleanValue": False}}})
check("ann: a normal user cannot delete one", "DELETE", "/announcements/ann1", 403, uid="u1")
check("ann: the admin can hide one", "PATCH", "/announcements/ann1", 200, uid="admin1",
      body={"fields": {"active": {"booleanValue": False}}})
check("ann: the admin can delete one", "DELETE", "/announcements/ann1", 200, uid="admin1")


# --- regression: a verified seller can still save their GCash number ---------
# The owner branch used to compare every key of the merged document, so the
# `verified` fields an admin writes made every profile save answer
# PERMISSION_DENIED. Saving only `gcashNumber` (an updateMask, like the app's
# set(merge)) must succeed for a verified seller, and the owner still must not
# be able to change the verification fields themselves.
seed("/users", "verifiedseller", {"fields": {
    "uid": {"stringValue": "verifiedseller"},
    "name": {"stringValue": "Verified Seller"},
    "verified": {"booleanValue": True},
    "verifiedBy": {"stringValue": "admin:admin1"},
    "verifiedAt": {"integerValue": "1"},
}})
check("gcash: a verified seller can still save their number", "PATCH",
      "/users/verifiedseller", 200, uid="verifiedseller", mask="gcashNumber",
      body={"fields": {"gcashNumber": {"stringValue": "09171234567"}}})
check("gcash: a verified seller cannot unverify themselves", "PATCH",
      "/users/verifiedseller", 403, uid="verifiedseller", mask="verified",
      body={"fields": {"verified": {"booleanValue": False}}})

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
