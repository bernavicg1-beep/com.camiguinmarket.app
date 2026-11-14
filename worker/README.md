# Worker sa mga litrato (Cloudflare R2)

Kini nga Worker maoy nagtipig sa mga litrato sa listing ngadto sa **Cloudflare R2**.
Wala gayud maabot sa app ang R2 credentials — ang Worker maoy mo-verify sa
**Firebase ID token** ug mohimo sa R2 key gikan sa `uid` sa token.

## Setup (usa ra ka beses)

```bash
npm install -g wrangler
wrangler login

cd worker
wrangler r2 bucket create camiguinmarket-images
wrangler secret put FIREBASE_WEB_API_KEY       # Firebase Web API key (public kini)
wrangler secret put FIREBASE_SERVICE_ACCOUNT   # service account JSON (para sa push)
wrangler deploy
```

Pagkahuman sa `deploy`, makakuha ka og URL nga sama niini:

```
https://camiguin-market-images.<subdomain>.workers.dev
```

Ibutang kini sa app:

- **Lokal:** `local.properties` → `R2_UPLOAD_URL=https://…workers.dev`
- **CI:** GitHub → Settings → Secrets → Actions → `R2_UPLOAD_URL`

## Mga endpoint

| Method | Path | Auth | Deskripsyon |
|---|---|---|---|
| `POST` | `/upload` | `Authorization: Bearer <Firebase ID token>` | I-upload ang litrato (image ra, ubos 5 MB). Tubag: `{ "url": "…" }` |
| `GET` | `/i/<key>` | wala | Ibalik ang litrato gikan sa R2 (long cache) |
| `POST` | `/push` | `Authorization: Bearer <Firebase ID token>` | Magpadala og FCM push (`kind`: `order`, `status`, `chat`). Tubag: `{ "sent": n }` |
| `GET` | `/health` | wala | `{ "ok": true, "bucket": true, "push": true }` |

Ang key anaa sa porma `listing_images/{uid}/{timestamp}-{random}.{jpg|png|webp}` —
ang `uid` gikan sa verified nga token, busa dili makasulat ang usa ka user sa
folder sa uban.

**Wala kinahanglana ang public bucket:** ang `/i/` route sa Worker maoy mo-serve
sa litrato, busa pwede nga private ang bucket.

## Local nga testing

```bash
cd worker
wrangler dev --port 8787
curl http://127.0.0.1:8787/health
```

Ang `wrangler dev` naggamit og lokal nga R2 simulation, busa dili makaapekto sa
production data.

## Push notifications (`POST /push`)

Pareho sa upload, **wala gayud maabot sa app ang service account**. Ang Worker:

1. Mo-verify sa Firebase ID token (`FIREBASE_WEB_API_KEY`) → `uid`.
2. Mobasa sa Firestore pinaagi sa service account (REST API) aron makuha ang
   **recipient** — dili kini gikan sa client:
   - `kind=chat` → `chats/{chatId}`; kinahanglan nga participant ang nagpadala,
     ug ang pikas mao ang recipient.
   - `kind=order` → `orders/{orderId}`; kinahanglan nga **buyer** ang nagpadala,
     ug ang **seller** mao ang recipient.
   - `kind=status` → `orders/{orderId}`; kinahanglan nga **seller** ang
     nagpadala, ug ang **buyer** mao ang recipient.
3. Magpadala sa **tanang token** sa `users/{uid}/tokens` pinaagi sa FCM HTTP v1,
   ug mag-delete sa mga patay nga token (`UNREGISTERED`/`INVALID_ARGUMENT`).

Ang JWT (RS256) alang sa OAuth2 gihimo sa Worker gamit ang WebCrypto, ug
gina-cache ang access token sulod sa isolate.

```bash
cd worker
wrangler secret put FIREBASE_SERVICE_ACCOUNT   # i-paste ang JSON sa service account
wrangler deploy
curl https://<worker>/health                   # "push": true
```

Kinahanglan nga dunay **Firebase Admin SDK / Firebase Cloud Messaging API** ug
**Cloud Firestore API** nga naka-enable, ug **`Firebase Admin SDK Administrator
Service Agent`** nga permission ang service account. Wala na kinahanglana ang
Blaze plan o Firebase Functions.
