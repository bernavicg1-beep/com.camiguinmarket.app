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
| `POST` | `/delete-images` | `Authorization: Bearer <Firebase ID token>` | Delete the caller's own photos (`{ "urls": [...] }`). Returns `{ "deleted": n, "skipped": n }` |
| `GET` | `/i/<key>` | none | Serve a photo from R2 (long cache) |
| `POST` | `/push` | `Authorization: Bearer <Firebase ID token>` | Send an FCM push (`kind`: `order`, `status`, `chat`). Returns `{ "sent": n }` |
| `GET` | `/health` | none | `{ "ok": true, "bucket": true, "push": true }` |
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
