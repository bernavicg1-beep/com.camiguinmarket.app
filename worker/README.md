# Worker (Cloudflare R2 + push notifications)

This Worker stores listing photos in **Cloudflare R2** and sends **FCM push
notifications**. No credentials ever reach the app — the Worker verifies the
**Firebase ID token** and builds the R2 key from the token's `uid`.

## Setup (once)

```bash
npm install -g wrangler
wrangler login

cd worker
wrangler r2 bucket create camiguinmarket-images
wrangler secret put FIREBASE_WEB_API_KEY       # Firebase Web API key (public)
wrangler secret put FIREBASE_SERVICE_ACCOUNT   # service account JSON (for push)
wrangler deploy
```

After `deploy` you get a URL like:

```
https://camiguin-market-images.<subdomain>.workers.dev
```

Set it in the app:

- **Local:** `local.properties` → `R2_UPLOAD_URL=https://…workers.dev`
- **CI:** GitHub → Settings → Secrets → Actions → `R2_UPLOAD_URL`

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/upload` | `Authorization: Bearer <Firebase ID token>` | Upload a photo (image only, under 5 MB). Returns `{ "url": "…" }` |
| `GET` | `/i/<key>` | none | Serve a photo from R2 (long cache) |
| `POST` | `/push` | `Authorization: Bearer <Firebase ID token>` | Send an FCM push (`kind`: `order`, `status`, `chat`). Returns `{ "sent": n }` |
| `GET` | `/health` | none | `{ "ok": true, "bucket": true, "push": true }` |

The key has the form `listing_images/{uid}/{timestamp}-{random}.{jpg|png|webp}` —
the `uid` comes from the verified token, so a user cannot write into someone
else's folder.

## Push notifications (`POST /push`)

Like the upload, **the service account never reaches the app**. The Worker:

1. Verifies the Firebase ID token (`FIREBASE_WEB_API_KEY`) → `uid`.
2. Reads Firestore through the service account (REST API) to get the
   **recipient** — never from the client:
   - `kind=chat` → `chats/{chatId}`; the sender must be a participant, and the
     other participant is the recipient.
   - `kind=order` → `orders/{orderId}`; the sender must be the **buyer**, and the
     **seller** is the recipient.
   - `kind=status` → `orders/{orderId}`; the sender must be the **seller**, and the
     **buyer** is the recipient.
3. Sends to **every token** in `users/{uid}/tokens` through FCM HTTP v1, and
   deletes dead tokens (`UNREGISTERED`/`INVALID_ARGUMENT`).

The OAuth2 JWT (RS256) is created inside the Worker with WebCrypto, and the access
token is cached per isolate.

```bash
cd worker
wrangler secret put FIREBASE_SERVICE_ACCOUNT   # paste the service account JSON
wrangler deploy
curl https://<worker>/health                   # "push": true
```

The service account needs the **Firebase Admin SDK / Firebase Cloud Messaging API**
and **Cloud Firestore API** enabled, plus the `Firebase Admin SDK Administrator
Service Agent` role. No Blaze plan or Firebase Functions required.
