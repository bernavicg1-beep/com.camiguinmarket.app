// Temporary bootstrap: signs in as the admin test account and creates
// admins/{uid} with the service account. Removed after the one-off run.
import { readFileSync } from "node:fs";
import { createSign } from "node:crypto";

const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
const email = process.env.SMOKE_TEST_ADMIN_EMAIL;
const password = process.env.SMOKE_TEST_ADMIN_PASSWORD;
const apiKey = JSON.parse(readFileSync("app/google-services.json", "utf8"))
  .client[0].api_key[0].current_key;

const b64url = (value) =>
  (Buffer.isBuffer(value) ? value : Buffer.from(value))
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

const signIn = await fetch(
  `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
  {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  },
).then((res) => res.json());
if (!signIn.idToken) throw new Error("admin sign-in failed");

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
const token = await fetch("https://oauth2.googleapis.com/token", {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion,
  }),
}).then((res) => res.json());
if (!token.access_token) throw new Error("service token failed");

const res = await fetch(
  `https://firestore.googleapis.com/v1/projects/${sa.project_id}` +
    `/databases/(default)/documents/admins/${signIn.localId}`,
  {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${token.access_token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields: { createdAt: { integerValue: String(now) } } }),
  },
);
console.log(`admins/${signIn.localId}: ${res.status}`);
if (!res.ok) throw new Error((await res.text()).slice(0, 300));
