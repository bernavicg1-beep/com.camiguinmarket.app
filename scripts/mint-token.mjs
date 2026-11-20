// Temporary: mint a short-lived Firebase custom token for the emulator session.
import { writeFileSync } from "node:fs";
import { createSign } from "node:crypto";

const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
const uid = process.env.UID;
const now = Math.floor(Date.now() / 1000);

const b64url = (value) =>
  (Buffer.isBuffer(value) ? value : Buffer.from(value))
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
const claims = b64url(
  JSON.stringify({
    iss: sa.client_email,
    sub: sa.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat: now,
    exp: now + 600,
    uid,
  }),
);
const signer = createSign("RSA-SHA256");
signer.update(`${header}.${claims}`);
const token = `${header}.${claims}.${b64url(signer.sign(sa.private_key))}`;
writeFileSync("custom-token.txt", token + "\n");
console.log(`custom token written for ${uid}, expires in 600s`);
