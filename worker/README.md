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

## Deploying

Manual deploy (as in the setup above):

```bash
cd worker
wrangler deploy
```

Automated deploys run through `.github/workflows/deploy-worker.yml` on every
push to `main` that touches `worker/**` (and on `workflow_dispatch`):

1. Runs `npm test` — a failing suite blocks the deploy.
2. `wrangler deploy`.
3. Reads the workers.dev address from wrangler's output and checks
   `/health`, failing the run unless it answers with `ok` and `bucket`.

Two repository secrets are required (Settings → Secrets and variables →
Actions); without them the job fails early with a message naming the missing
secret:

- `CLOUDFLARE_API_TOKEN` — an API token with the Workers Scripts:Edit permission
  for the account that owns the Worker.
- `CLOUDFLARE_ACCOUNT_ID` — the account id shown in the Workers & Pages dashboard.

A `concurrency` group keeps two deploys from racing for the same script. The
Worker secrets (`FIREBASE_WEB_API_KEY`, `FIREBASE_SERVICE_ACCOUNT`) are already
on the deployed Worker and are untouched by a deploy.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/upload` | `Authorization: Bearer <Firebase ID token>` | Upload a photo (image only, under 5 MB). Returns `{ "url": "…" }` |
| `POST` | `/delete-images` | `Authorization: Bearer <Firebase ID token>` | Delete the caller's own photos (`{ "urls": [...] }`). Returns `{ "deleted": n, "skipped": n }` |
| `GET` | `/i/<key>` | none | Serve a photo from R2 (long cache) |
| `POST` | `/push` | `Authorization: Bearer <Firebase ID token>` | Send an FCM push (`kind`: `order`, `status`, `chat`, `saved`). Returns `{ "sent": n }`. Also sends a Telegram message when the recipient has a connected account |
| `POST` | `/notify` | `Authorization: Bearer <Firebase ID token>` | Write a cross-user notification document to the recipient's inbox (`{ toUid, fromUid, fromName, message, listingId?, listingTitle? }`) |
| `GET` | `/telegram/login` | `Authorization: Bearer <Firebase ID token>` (via `?token=`) | Serve the Telegram Login Widget page |
| `POST` | `/telegram/verify` | `Authorization: Bearer <Firebase ID token>` | Verify the Telegram widget payload (HMAC-SHA256 over sorted fields). Persists `telegramChatId`/`telegramUsername` on `users/{uid}` |
| `GET` | `/health` | none | `{ "ok": true, "images": true, "bucket": true, "push": true, "telegram": true }` |
| cron | `*/15 * * * *` | — | Delete expired perishable listings |

The key has the form `listing_images/{uid}/{timestamp}-{random}.{jpg|png|webp}` —
the `uid` comes from the verified token, so a user cannot write into someone
else's folder.

The app calls `POST /delete-images` when a seller deletes a listing, replaces
its photos, or deletes their account, always **after** the Firestore write
succeeds. Only keys under `listing_images/<caller uid>/` are removed (max 30
URLs per call; the app chunks longer lists); anything else is counted in
`skipped`, so the endpoint cannot be used to delete another seller's photos.

A moderator (`admins/{uid}` exists) may also delete photos in any
`listing_images/` folder, which is how an admin removal from the Moderation
screens cleans up after a listing that belongs to someone else. The admin
lookup only happens when a URL is outside the caller's own folder.

## Tests

```bash
cd worker
npm test          # node:test — no dependencies, no credentials
```

`test/delete-images.test.js` covers `POST /delete-images`: a missing token, the
caller's own photos vs someone else's, the admin branch (an `admins/{uid}`
document found through the service account), the no-service-account case where
nobody is an admin, the single admin lookup per call, the 30-URL cap, and the
wrong method / bad JSON responses.

`test/purge.test.js` covers the cron purge: the `0 < expiresAt <= now` query,
that fresh and non-perishable listings are left alone, photo deletion (with
deduplication, URL decoding and keys outside `listing_images/` ignored), that a
failed document delete keeps its photos for the next run, that R2 failures are
counted without stopping the run, batching (a full batch fetches again, a short
one stops), and the no-service-account case where nothing is touched.

`test/telegram.test.js` covers `GET /telegram/login` and `POST /telegram/verify`:
missing auth, missing hash, valid signature, tampered payload, expired
`auth_date`, bad JSON, wrong method, and the per-uid rate limit.

`test/telegram-notify.test.js` covers the secondary Telegram send on
`POST /push` (saved/chat/order/status) when the recipient has a stored chat id,
and the skip when absent.

`test/telegram-persist.test.js` covers persistence of `telegramChatId` /
`telegramUsername` on `/telegram/verify` with and without a service account.

Every network call is stubbed, so the suite needs no credentials, no emulator,
and runs in CI (`.github/workflows/worker-test.yml`).

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

## Telegram connect + notifications

`GET /telegram/login` serves a small HTML page embedding the Telegram Login
Widget. The app opens it in a WebView with a Firebase ID token in `?token=`;
the widget calls back into `window.Android.onVerified(username)` on success,
and the page also POSTs the full Telegram user object to `/telegram/verify`.

`POST /telegram/verify` recomputes `HMAC-SHA256` over the sorted
`key=value` fields (secret key = `SHA-256` of `TELEGRAM_BOT_TOKEN`) and
compares it to `hash`; tampered or expired (`auth_date` older than 24 h)
payloads are rejected. On success it persists `telegramChatId` and
`telegramUsername` on `users/{uid}` (best effort, needs the service account).

When a recipient has a stored `telegramChatId`, every `POST /push` call also
sends a plain-text message through the Bot API as a secondary channel (in
addition to FCM). Telegram delivery is best effort and never blocks the
response.

Requires the `TELEGRAM_BOT_TOKEN` secret and a bot domain set in BotFather
pointing at the Worker origin.

## Rate limiting

Best-effort in-isolate fixed-window limits (per IP pre-auth, per uid post-auth):

- `/upload`: 60/min each
- `/delete-images`: 60/min IP, 30/min uid
- `/push`, `/notify`: 60/min each
- `/telegram/login`: 30/min IP
- `/telegram/verify`: 30/min IP, 10/min uid

Exceeded requests get `429` with a `Retry-After` header. State is per isolate,
so Cloudflare Rate Limiting rules remain the stronger layer for cross-isolate
enforcement.

## Expired perishable listings (cron)

The app hides a perishable listing once its `expiresAt` passes, but the document
stays in Firestore. A cron trigger (`*/15 * * * *`, see `wrangler.toml`) deletes
those documents for good:

1. `runQuery` on `listings` with the range `0 < expiresAt <= now`, 200 rows at a
   time, up to 10 batches per run. Non-perishable listings (`expiresAt = 0`) and
   older documents without the field are never selected.
2. Deletes each match through the Firestore REST API, treating `404` as done.
3. Deletes the R2 photos of the removed listings (`imageUrl`/`imageUrls` →
   `listing_images/…`, through the `IMAGES` binding), so storage keeps no
   orphans. Photos are only removed after the document is gone, and keys outside
   `listing_images/` are ignored.
4. Logs `purgeExpired {"deleted":n,"failures":n,"photosDeleted":n,"photoFailures":n}`
   (or `skipped` when the service account secret is missing).

It uses the same `FIREBASE_SERVICE_ACCOUNT` secret as push, and no composite
index is needed because the range is on a single field.

```bash
cd worker
wrangler deploy                              # uploads the trigger too
wrangler dev --test-scheduled                # local: curl "http://localhost:8787/__scheduled?cron=*/15+*+*+*+*"
wrangler tail                                # watch the purgeExpired logs
```

Photo cleanup is best effort: a failed R2 delete is counted in `photoFailures`
and does not stop the run, and because the document is already gone that image
would need manual removal. R2 deletes are free of charge.
