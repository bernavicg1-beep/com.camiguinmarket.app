// Push notifications sa Camiguin Market.
//
// Kon dunay bag-ong order, kausaban sa status, o bag-ong chat message, magpadala
// kini og FCM push ngadto sa mga token sa sakto nga user
// (`users/{uid}/tokens/{token}`, gina-save sa app).
//
// Deploy: `firebase deploy --only functions` (kinahanglan og Blaze plan ug
// service account). Ang browser dili makahimo niini dinhi.

const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const db = getFirestore();

async function tokensOf(uid) {
  if (!uid) return [];
  const snap = await db.collection("users").doc(uid).collection("tokens").get();
  return snap.docs.map((d) => d.get("token")).filter(Boolean);
}

/** Magpadala og push ug maglimpyo sa mga token nga patay na. */
async function push(uid, title, body, data = {}) {
  const tokens = await tokensOf(uid);
  if (!tokens.length) return;

  const res = await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    data,
  });

  const dead = [];
  res.responses.forEach((r, i) => {
    const code = (r.error && r.error.code) || "";
    if (
      code.includes("registration-token-not-registered") ||
      code.includes("invalid-registration-token") ||
      code.includes("invalid-argument")
    ) {
      dead.push(tokens[i]);
    }
  });
  await Promise.all(
    dead.map((t) =>
      db.collection("users").doc(uid).collection("tokens").doc(t).delete().catch(() => {})
    )
  );
}

// Bag-ong order: pahibaloon ang seller.
exports.onOrderCreated = onDocumentCreated("orders/{orderId}", async (event) => {
  const o = event.data && event.data.data();
  if (!o) return;
  const qty = o.quantity || 1;
  await push(
    o.sellerId,
    "Bag-ong order 🛒",
    `${o.buyerName || "Usa ka buyer"} mi-order: ${o.listingTitle} ×${qty}`,
    { orderId: event.params.orderId, type: "order" }
  );
});

// Kausaban sa status: pahibaloon ang buyer.
exports.onOrderStatus = onDocumentUpdated("orders/{orderId}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!before || !after || before.status === after.status) return;

  const action = {
    confirmed: "gikumpirma",
    done: "natapos",
    cancelled: "gikansela",
  }[after.status];
  if (!action) return;

  await push(
    after.buyerId,
    "Order update 📦",
    `${after.sellerName || "Ang seller"} ${action} ang imong order: ${after.listingTitle}`,
    { orderId: event.params.orderId, type: "order" }
  );
});

// Bag-ong chat message: pahibaloon ang pikas nga participant.
exports.onChatMessage = onDocumentCreated(
  "chats/{chatId}/messages/{messageId}",
  async (event) => {
    const m = event.data && event.data.data();
    if (!m) return;

    const chatSnap = await db.collection("chats").doc(event.params.chatId).get();
    const chat = chatSnap.data();
    if (!chat) return;

    const participants = chat.participants || [];
    const other = participants.find((p) => p !== m.from);
    if (!other) return;

    const names = chat.names || {};
    const sender = names[m.from] || "Usa ka user";
    const preview = (m.text || "").slice(0, 120);

    await push(other, `💬 ${sender}`, preview, {
      chatId: event.params.chatId,
      type: "chat",
    });
  }
);
