/**
 * Live integration check for the deployed Worker's /push endpoint.
 *
 * Requires WORKER_URL, SMOKE_TEST_EMAIL, SMOKE_TEST_PASSWORD and
 * FIREBASE_SERVICE_ACCOUNT (the same secrets the smoke tests and Worker use).
 * The test account must have at least one registered FCM token (open the app on
 * a device and sign in as that account).
 *
 * Optional SMOKE_TEST_ADMIN_EMAIL / SMOKE_TEST_ADMIN_PASSWORD add the
 * moderation send. Without them it is skipped with a notice; with them the
 * account must exist in admins/{uid}, otherwise the run fails. Seeded
 * documents and the throwaway buyer are removed on every outcome.
 */

import { readFileSync } from "node:fs";
import { createSign } from "node:crypto";

const WORKER = (process.env.WORKER_URL || "").replace(/\/+$/, "");
const EMAIL = process.env.SMOKE_TEST_EMAIL;
const PASSWORD = process.env.SMOKE_TEST_PASSWORD;
const SA_JSON = process.env.FIREBASE_SERVICE_ACCOUNT;

const missing = Object.entries({
  WORKER_URL: WORKER,
  SMOKE_TEST_EMAIL: EMAIL,
  SMOKE_TEST_PASSWORD: PASSWORD,
  FIREBASE_SERVICE_ACCOUNT: SA_JSON,
})
  .filter(([, value]) => !value)
  .map(([name]) => name);
if (missing.length) {
  console.error(`::error::missing ${missing.join(", ")}`);
  process.exit(1);
}

const sa = JSON.parse(SA_JSON);
const apiKey = JSON.parse(readFileSync("app/google-services.json", "utf8"))
  .client[0].api_key[0].current_key;

const b64url = (value) =>
  (Buffer.isBuffer(value) ? value : Buffer.from(value))
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

async function serviceToken() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/datastore",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    }),
  );
  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const assertion = `${header}.${claims}.${b64url(signer.sign(sa.private_key))}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) throw new Error(`service token failed: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).access_token;
}

const docUrl = (path) =>
  `https://firestore.googleapis.com/v1/projects/${sa.project_id}` +
  `/databases/(default)/documents/${path}`;

async function listDocs(token, path) {
  const res = await fetch(docUrl(path), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`list ${path} failed: ${res.status}`);
  return (await res.json()).documents || [];
}

async function readDoc(token, path) {
  const res = await fetch(docUrl(path), {
    headers: { Authorization: `Bearer ${token}` },
  });
  // A missing document is a real 404; any other failure is a broken read and
  // must not be reported as "not an admin".
  if (res.status === 404) return null;
  if (!res.ok) {
    throw new Error(
      `read ${path} failed: ${res.status} ${(await res.text()).slice(0, 200)}`,
    );
  }
  return res.json();
}

async function writeDoc(token, path, fields) {
  const res = await fetch(docUrl(path), {
    method: "PATCH",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
  if (!res.ok) {
    throw new Error(`write ${path} failed: ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
}

async function deleteDoc(token, path) {
  await fetch(docUrl(path), {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  }).catch(() => {});
}

async function signIn(email = EMAIL, password = PASSWORD) {
  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, returnSecureToken: true }),
    },
  );
  const data = await res.json().catch(() => ({}));
  if (!data.idToken) throw new Error(`sign-in failed: ${JSON.stringify(data).slice(0, 200)}`);
  return data;
}

async function signUp() {
  const email = `live-check+${Date.now()}@example.com`;
  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email,
        password: `LiveCheck-${Date.now()}`,
        returnSecureToken: true,
      }),
    },
  );
  const data = await res.json().catch(() => ({}));
  if (!data.idToken) {
    throw new Error(`throwaway buyer sign-up failed: ${JSON.stringify(data).slice(0, 200)}`);
  }
  return data;
}

async function deleteAccount(idToken) {
  await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    },
  ).catch(() => {});
}

// Sends one /push and requires a real delivery, logging the response first.
async function deliver(kind, token, extra) {
  const res = await fetch(`${WORKER}/push`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ kind, ...extra }),
  });
  const body = await res.json().catch(() => ({}));
  console.log(`${kind}: ${res.status} ${JSON.stringify(body)}`);
  if (res.status !== 200 || !(body.sent >= 1)) {
    throw new Error(`${kind} did not deliver (sent >= 1 required)`);
  }
}

try {
  const service = await serviceToken();
  const account = await signIn();
  const uid = account.localId;
  console.log(`test account: ${uid}`);

  const tokens = await listDocs(service, `users/${uid}/tokens`);
  console.log(`registered FCM tokens: ${tokens.length}`);
  if (tokens.length === 0) {
    console.log(
      "::notice::The test account has no registered FCM token; open the app on a device, " +
        "sign in as the test account, then re-run this workflow.",
    );
    process.exit(0);
  }

  const buyer = await signUp();
  const stamp = Date.now();
  const listingId = `live-check-${stamp}`;
  const ratingId = `live-check-${stamp}`;

  try {
    await writeDoc(service, `listings/${listingId}`, {
      sellerId: { stringValue: uid },
      sellerName: { stringValue: "Live check" },
      title: { stringValue: "Live check listing" },
      price: { doubleValue: 1 },
      category: { stringValue: "Other" },
      town: { stringValue: "Mambajao" },
      description: { stringValue: "Integration check" },
      createdAt: { integerValue: String(stamp) },
    });
    await deliver("cart", buyer.idToken, { listingId });

    await writeDoc(service, `ratings/${ratingId}`, {
      buyerId: { stringValue: buyer.localId },
      sellerId: { stringValue: uid },
      buyerName: { stringValue: "Live check" },
      rating: { integerValue: "5" },
      createdAt: { integerValue: String(stamp) },
    });
    await deliver("rating", buyer.idToken, { orderId: ratingId });

    const adminEmail = process.env.SMOKE_TEST_ADMIN_EMAIL;
    const adminPassword = process.env.SMOKE_TEST_ADMIN_PASSWORD;
    if (!adminEmail || !adminPassword) {
      console.log(
        "::notice::No admin test account (SMOKE_TEST_ADMIN_EMAIL / " +
          "SMOKE_TEST_ADMIN_PASSWORD); skipping moderation.",
      );
    } else {
      const admin = await signIn(adminEmail, adminPassword);
      if (!(await readDoc(service, `admins/${admin.localId}`))) {
        throw new Error(
          `SMOKE_TEST_ADMIN_* is set but admins/${admin.localId} does not exist; ` +
            "moderation was not tested",
        );
      }
      await deliver("moderation", admin.idToken, { sellerId: uid, title: "Live check" });
    }

    console.log("live push integration check passed");
  } finally {
    await deleteDoc(service, `listings/${listingId}`);
    await deleteDoc(service, `ratings/${ratingId}`);
    await deleteAccount(buyer.idToken);
  }
} catch (error) {
  console.error(`::error::${error?.message || error}`);
  process.exit(1);
}
