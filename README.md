# Camiguin Market 🛒

Android app (Kotlin + Jetpack Compose + Firebase) alang sa pagbaligya ug pagpalit
sa tibuok Camiguin. Ang seller makabutang og **pickup location sa mapa**, ug ang
customer makakita niini, makakuha og **ruta A→B**, ug makapangita og lugar.

## Ang duha ka mapa

| | Option A — Google Map | Option B — Mapbox |
|---|---|---|
| Unsaon pag-abli | `🗺️ Mapa` o `View Map` sa listing | `🌐 Tan-awa sa Mapbox (web)` |
| Teknolohiya | Google Maps SDK + `maps-compose` (Compose) | Mapbox GL JS v3 sa `WebView` |
| Ruta A→B | Google Directions API | Mapbox Directions API |
| Place search | Google Geocoding API | Mapbox Geocoding API |

Pareho ang duha: **tanan seller pin**, **search bar sa ibabaw**, ug **ruta A→B**.
Ang file sa HTML nga gigamit sa Mapbox: `app/src/main/assets/mapbox_map.html`.

## Mga feature sa mapa

- **Pickup pin per listing** — `latitude`/`longitude` (Double) ang gitipigan sa
  Firestore `listings` document. Ang seller mo-pindot sa mapa aron ibutang ang pin.
- **View Map / Get Directions** — makita ang pin sa sulod sa app, o ablihan ang
  Google Maps app alang sa navigation.
- **`📍 Use My Current Location`** — GPS location (kinahanglan ang location permission).
- **Ruta A→B** — `🅰️ Set A` / `🅱️ Set B` (tap sa mapa), dayon `🧭 Ipakita ang Ruta A→B`.
  Magpakita kini sa polyline sa dalan, gilay-on (`km`) ug oras (`min`).
- **Search bar** — `Pangita og lugar o seller…`: seller una (ngalan, lungsod,
  kategorya), dayon place search sa Camiguin kon walay seller nga match.
- **`⚖️ Itandi` (Google vs Mapbox)** — pareho nga Point A ug Point B ang ipasa sa
  duha ka mapa; ipakita ang gilay-on/oras sa matag usa ug ang ilang kalainan
  (`✅ Duol ra kaayo` kon ≤ 0.5 km). Maayo kini alang sa pagsulay ug pagpares.

## Pag-post, pag-edit, ug pagpapas sa listing

- **`+ Baligya`** — mag-post og listing (ngalan, presyo, contact, kategorya, lungsod,
  ug opsyonal nga pickup pin sa mapa).
- **`✏️ Edit`** — makita sa tag-iya ra, sulod sa detalye sa listing. Ma-prefill ang
  tanan nga field (apil ang pickup pin). Ang `sellerId`, `sellerName`, ug `createdAt`
  sa orihinal wala mausab — mao kini ang gikinahanglan sa `firestore.rules` aron
  tugotan ang update.
- **`Papas`** — tag-iya ra usab.

## Gikinahanglan nga API keys (ayaw i-commit!)

1. **Google Maps API key** — sa Google Cloud Console i-enable ang:
   - `Maps SDK for Android`
   - `Directions API` (alang sa ruta A→B)
   - `Geocoding API` (alang sa search bar)

   I-restrict ang key sa package name (`com.camiguinmarket.app`) ug SHA-1.
   Ayaw gamita ang Firebase key niini.
2. **Mapbox public access token** — `mapbox.com` → Account → Tokens (public token).
   Apil na sa free tier ang Directions ug Geocoding API.

Ibutang kini sa `local.properties` (wala kini ma-commit, naa sa `.gitignore`):

```properties
MAPS_API_KEY=ang_imong_google_maps_key
MAPBOX_ACCESS_TOKEN=pk.ang_imong_mapbox_token
```

Ang `app/build.gradle.kts` mokuha niini gikan sa `local.properties`, Gradle
property (`-PMAPS_API_KEY=...`), o environment variable — wala gayud isulat sa code.

Sa GitHub: **Settings → Secrets and variables → Actions**, idugang ang
`MAPS_API_KEY` ug `MAPBOX_ACCESS_TOKEN`.

### Pag-sign sa release APK (opsyonal)

Kon gusto nimo og **installable** nga release APK, idugang kini nga secrets:

| Secret | Unsa kini |
|---|---|
| `KEYSTORE_BASE64` | Ang imong keystore file, gi-encode sa base64: `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | Ang store password |
| `KEY_ALIAS` | Ang key alias |
| `KEY_PASSWORD` | Ang key password |

Kon naa ang `KEYSTORE_BASE64`, i-decode sa CI ang keystore ngadto sa runner temp,
mo-himo og `release` signing config, ug **signed** ang mogawas (`app-release.apk`).
Kon wala, **unsigned** ang APK (`app-release-unsigned.apk`) ug dili kini ma-install.

Sa lokal nga machine, ihatag kini isip environment variables o Gradle properties
(`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`) — o isulat sa `local.properties` nga dili ma-commit:

```properties
RELEASE_KEYSTORE_FILE=/home/ikaw/release.keystore
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

## Firestore security rules

Ang `firestore.rules` maoy nagkontrolar sa acesso sa database:

- **read** — kinahanglan naka-log in (`request.auth != null`).
- **create / update / delete** — ang tag-iya ra (`sellerId == request.auth.uid`),
  ug dili ma-transfer ang listing ngadto sa laing seller.
- **default deny** — wala nay laing collection nga gibuksan.

I-deploy ang rules (usa ra ka beses, o kon may kausaban):

```bash
firebase deploy --only firestore:rules --project camiguinmarket
```

O kaha i-paste ang sulod sa `firestore.rules` sa **Firebase Console → Firestore →
Rules** ug pindota ang **Publish**. **Ayaw** ibilin sa test mode (`allow read, write:
if true`) kay bisan kinsa makabasa ug makasulat sa `listings`.

### Awtomatikong deploy pinaagi sa GitHub Actions

Ang `.github/workflows/firestore-rules.yml` dunay duha ka job:

1. **Validate** — i-compile ang `firestore.rules` sa Firestore emulator, dayon i-run
   ang `scripts/test-firestore-rules.py` (19 ka behavioral check: anonymous denied,
   tag-iya allowed, laing user denied, dili ma-transfer ang listing, sayop nga tipo
   sa `price` denied, **ug ang eksaktong dokumento nga isulat sa app** — `id = ""`,
   `price` nga double, `createdAt` nga long, `latitude`/`longitude` nga `null` kon
   walay pickup pin, `orderBy createdAt`, ug ang query sa account deletion).
   Modagan sa matag push sa `main` *ug* sa matag pull request nga nag-usab sa rules.
   Kon sayop ang syntax o may ni-fail nga check, mo-fail ang job — dili ka
   makadeploy og guba nga rules.

   I-run kini sa lokal (nanginahanglan og JDK 21+ alang sa emulator):

   ```bash
   firebase emulators:exec --only firestore --project demo-camiguin \
     "python3 scripts/test-firestore-rules.py"
   ```
2. **Deploy** — `firebase deploy --only firestore:rules`. Modagan lamang kon na-set
   ang credential secret; kon wala, **gilaktawan** kini (dili mo-fail) ug ibutang
   ang pahinumdom sa run summary.

Pag-set sa credential — **Settings → Secrets and variables → Actions →
New repository secret**. Mas girekomendar ang service account:

| Secret | Unsa kini | Status |
|---|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | Ang **tibuok sulod** sa JSON key gikan sa Firebase Console → ⚙️ Project settings → **Service accounts → Generate new private key** | ✅ girekomendar |
| `FIREBASE_TOKEN` | Token gikan sa `firebase login:ci` | ⚠️ legasiya (deprecated sa Google) |

Kon naa ang duha, `FIREBASE_SERVICE_ACCOUNT` ang gamiton. Ang service account
nga gi-generate sa Firebase Console duna nay saktong role, busa walay laing
i-set nga permission.

Ang default nga project naa sa `.firebaserc` (`camiguinmarket`), busa dili na
kinahanglan ang `--project` matag higayon.

**Importante:** kining duha ka secret naghatag og bug-os nga acesso sa Firebase
project — i-treat kini isip password. Wala kini i-print sa CI, wala usab kini
i-commit; ang service account key isulat sa `$RUNNER_TEMP` ra ug papason sa
runner human sa job.

## Pagpapas sa account (requirement sa Play Store)

Kon ang app makahimo og account, kinahanglan nga ang user makapapas niini:

- **Sa app** — pindota ang **🗑️** sa top bar sulod sa market screen. Mapapas ang
tanan niyang listing sa Firestore, dayon ang Firebase Auth account.
- **Sa web** — `docs/account-deletion.html`. I-host kini via **GitHub Pages**
  (Settings → Pages → Branch: `main`, folder: `/docs`) ug ibutang ang URL sa
  Play Console → *App content → Data deletion*.

**Importante:** ilisi ang `YOUR_SUPPORT_EMAIL_HERE` sa `docs/account-deletion.html`
sa imong tinuod nga support email sa wala pa i-publish ang page.

Kon dugay na ang login sa user, mobalik ang `FirebaseAuthRecentLoginRequiredException`
ug hangyoon siya sa pag-log out/log in pag-usab — normal kini sa Firebase.

## Pag-build

```bash
gradle assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # release APK (signed kon may keystore, kon wala unsigned)
gradle bundleRelease        # release AAB: app/build/outputs/bundle/release/app-release.aab
```

Ang `.aab` (Android App Bundle) mao ang format nga gidawat sa **Google Play Console**.
Kon may keystore, signed kini; kon wala, unsigned ug dili ma-upload sa Play.

Ang `.github/workflows/firestore-rules.yml` maoy nagdeploy sa Firestore rules (tan-awa
ang *Firestore security rules* sa ibabaw). Ang `.github/workflows/build.yml`
mo-build sa debug APK sa matag push sa `main`,
ug mo-upload niini isip artifact. Kon naa ang duha ka secret, mo-build usab kini
sa release variant ug i-upload isip `CamiguinMarket-release-apk` (APK) ug
`CamiguinMarket-release-aab` (AAB alang sa Play Store).

## Wala pa ma-test

Walay device/emulator sa development environment, busa **wala ma-verify ang
runtime**: ang GPS location, pag-tap sa mapa, ang WebView bridge (`onRoute`), ug
ang tinuod nga tubag sa Google/Mapbox API. Ang nasiguro ra: `gradle assembleDebug`
ug `gradle assembleRelease` mo-compile, ug husto ang JavaScript sa HTML page.
