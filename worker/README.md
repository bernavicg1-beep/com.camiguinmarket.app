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
wrangler secret put FIREBASE_WEB_API_KEY   # Firebase Web API key (public kini)
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
| `GET` | `/health` | wala | `{ "ok": true }` |

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
