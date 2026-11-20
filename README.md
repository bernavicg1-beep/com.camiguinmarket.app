# Camiguin Market 🛒

Android app (Kotlin + Jetpack Compose + Firebase) for buying and selling across
Camiguin. The seller can drop a **pickup location on the map**, and the customer
sees it, gets an **A→B route**, and can search for places.

## The map

The app uses **Leaflet with OpenStreetMap tiles** inside a `WebView`, so there is
no Google Maps SDK and no Mapbox account anywhere in the build:

| | Leaflet (OpenStreetMap) |
|---|---|
| How to open | `🗺️ Map`, `View Map` on a listing, or `Open the full map` |
| Technology | Leaflet 1.9.4 in `app/src/main/assets/leaflet_map.html` |
| A→B route | OSRM public server (OpenStreetMap road data) |
| Place search | Nominatim (OpenStreetMap) |
| API key | none — nothing to add to `local.properties` or GitHub secrets |

All seller pins, a search bar on top, and an A→B route work the same way as
before. The map HTML lives in `app/src/main/assets/leaflet_map.html`.

## Map features

- **Pickup pin per listing** — `latitude`/`longitude` (Double) stored in the
  Firestore `listings` document. The seller taps the map to place the pin.
- **View Map / Get Directions** — see the pin inside the app, or hand the
  coordinates to any map app installed on the phone (`geo:` intent).
- **A→B route** — `🅰️ Set A` / `🅱️ Set B` (tap the map), then `🧭 Show the A→B Route`.
  It draws the road polyline, the distance (`km`) and the time (`min`).
- **Search bar** — `Search a place or seller…`: sellers first (name, town,
  category), then a place search in Camiguin when no seller matches.
- **`🛣️ Route`** — a full-screen A→B planner: set the pin, press `Set A` or
  `Set B`, then `Show the A→B Route` (distance and time from OSRM).

## New design (mockup)

Blue theme, logo header, and bottom navigation:

| Tab | What's inside |
|---|---|
| **Home** | Search bar, hero banner, **Shop by Category** grid with icons, and **Featured Products** (thumbnail, price, town, ♡, `Add to Cart`) |
| **Categories** | Category grid + town filter + listing list |
| **Sell** | Opens the post dialog |
| **Cart** | Items in the cart, `+`/`−` quantity, total, and **Checkout** |
| **Profile** | Name, email, listing count, Favorites, **Orders**, **Chats**, Log out, and Delete account |

Also: **Notifications** (when someone adds your listing to their cart, orders, or
messages you), **Chat with seller** with an **inbox** (all threads), and **checkout**.
Icons are Material icons (`material-icons-extended`), no emoji.

### Checkout and orders

- **Checkout** in the Cart creates one `orders` document per cart item (each
  seller gets their own order), then empties the cart and notifies the seller.
- Profile → **Orders** has two filters: *I'm the buyer* and *I'm the seller*.
  Status: `pending` → `confirmed` → `done`.
- The **seller** gets `Confirm` and `Done` buttons (in the list and in the
  detail), and changes appear immediately because the order list is live.
- Tap an order to open the **detail**: photo, price × quantity, total,
  buyer/seller, contact, status timeline, and `Chat with buyer`/`Chat with seller`.
- The **chat unread badge** is shown on the chat icon in the header. Unread is
  tracked in `chats/{chatId}.unread.{uid}` — it grows when someone writes and goes
  back to 0 when the thread is opened.
- When the seller confirms or completes an order, the **buyer gets a notification**.
- **Cancel:** the buyer can cancel while `pending`, the seller at any time before
  `done`; there is a `cancelled` status and the other side is notified.
- The **Sales** summary has filters: **Today** / **This month** / **All**.
- Chat has a **typing indicator** ("typing…", disappears after 7s) and the typing
  signal is throttled to 2.5s so writes don't pile up.
- Profile has a **Sales** summary for the seller: revenue (`done` orders),
  new, confirmed, and done counts.
- Messages show a time (`7:42 PM`) and a **read receipt**: `✓` when sent,
  `✓✓` when read by the other side (`chats/{chatId}.lastRead.{uid}`).
- No composite indexes are required: only `whereEqualTo` queries, sorted in the app.

These features have their own Firestore collections: `carts/{uid}/items`,
`favorites/{uid}/items`, `notifications/{uid}/items`, `orders/{orderId}`, and
`chats/{chatId}/messages`.

### Moderation

When an `admins/{uid}` document exists in Firestore, Profile shows the
**Moderation** screen: all listings, and a moderator can delete any listing
(the seller is notified).

**How to make a moderator:** Firebase Console → Firestore → **Start collection**
`admins` → document ID = the user's `uid` (visible in the Authentication tab) →
field `active` (boolean) = `true`. There is no UI for this in the app because not
just anyone should be able to create a moderator; `admins` is blocked by the rules
(`allow write: if false`).

### Reporting a listing

The listing detail has **Report this listing** (on other people's listings).
The user picks a reason (Not real, Scam or fraud, Inappropriate content,
Wrong category, Other) and can add optional details. The admin sees it in
**Moderation → Reports** and can choose **Delete listing** (resolves the report)
or **Dismiss**. Only the reporter and the admin can read the report.

### Search suggestions and multiple photos

- The **SearchBar** has **recent searches** (latest 8, saved on the device) and
  **suggestions** from listing titles while typing. Press the keyboard search
  action to save the query.
- A listing can carry up to **6 photos** (`imageUrls`). Uploads run in sequence
  and `imageUrl` stays the first photo for the cards. The detail screen scrolls
  the photos horizontally.

### Photo viewer and filters

- Tap a photo in the detail → **fullscreen viewer**: swipe left/right,
  counter (`2 / 5`), and ✕ to close.
- Home and Categories have a **price filter** (All, Under ₱50, ₱50–₱200,
  ₱200–₱500, Over ₱500) and **sort** (Newest, Price ↑, Price ↓).

### Seller rating

After a `done` order, the buyer can give **1–5 stars + a comment** (in the Orders
list or the detail). The doc id is the order id, so there is **one rating per
order** (not editable, `allow update: if false`). The average is shown to the
seller as `⭐ 4.6 (12)` in Profile → Sales.

**Rules:** only the buyer of a `done` order can create it (`get()` on the order),
and any signed-in user can read it.

### Listing reviews, saved searches, and dark mode

- The listing detail shows the seller's **average rating** and up to
  **3 latest comments**.
- **Saved search:** Home (or Categories) has **"Save this search"** — it saves the
  query and category to `savedSearches/{uid}/items`. When the app opens, it checks
  for listings newer than `lastCheckedAt`; if any match, you get a notification
  (client-side, no server). The chip has ✕ to remove it.
- **Dark mode:** toggle in Profile, saved in `ui_prefs` on the device. The header,
  bottom nav, chat bubbles, thumbnails, and stat cards all use theme colors
  (`marketDarkColorScheme`) — no hardcoded white panels.
- **All reviews:** the detail has "See all reviews" → `ReviewsScreen` (average,
  stars, comment, buyer, date).
- **Manage saved searches:** Profile → **Saved searches** — tap to apply the
  filter, rename (✏️), or remove (🗑️).

### Global search, phone verification, and listing views

- **Search icon in the header** → global search screen: query, category, town,
  price, sort, and history — all in one place.
- **Phone number (SMS verification):** Profile → **Phone number**. Enter a
  number, tap **Send code**, then type the 6-digit code that arrives by text.
  The number is saved as `phone` + `phoneVerified` on `users/{uid}` **only after**
  Firebase confirms the code, and the rules let only the owner read or write
  those two fields. Signing up still sends the verification email, but nothing in
  the app is blocked on it.
- Login has **Forgot password?** (`sendPasswordResetEmail`).
- **Listing views:** every time a detail is opened (not by the owner, once per
  session) the listing's `views` is incremented; it is shown as `👁 12 views`.
  The rules allow **exactly +1**, and only on that field.

### Seller views and trending

- The **owner** sees `👁 N views` on their own listing cards, and Profile → Sales
  has a **Views on my listings** stat.
- New sort option: **Trending** (based on views), plus a **🔥 Trending now**
  horizontal list on Home (top 5, views > 0).

### Per-listing stats and trending in Categories

- **Profile → Listing stats**: each listing shows **views**, **cart adds**
  (`cartAdds` field, incremented in the `addToCart` transaction), and the **order
  count**. One `orders` query (`sellerId`), grouped in the app — no index.
- **🔥 Trending now** also appears in the **Categories** tab.

### Views chart, CSV export, and push notifications

- **Listing stats** has a **daily views bar chart** (7 days) built from the
  `viewsByDay` map; `openListing` increments `views` and
  `viewsByDay."YYYY-MM-DD"` in a single update.
- **CSV export:** the Orders screen has a **CSV** button — it writes `orders.csv`
  to the cache and opens the Android share sheet (`FileProvider`).
- **Push notifications (FCM):** the app registers its token at
  `users/{uid}/tokens/{token}` and has a `MarketMessagingService` that shows the
  notification. **Delivery lives in the Cloudflare Worker** (`POST /push`, same as
  the R2 upload): the Worker verifies the ID token, picks the recipient from
  Firestore, and calls FCM with the service account — no secret in the app and no
  Firebase Functions/Blaze required. See [`worker/README.md`](worker/README.md).
- **CSV filter:** the Orders screen has a **date** filter (All / 7 / 30 days) and a
  **status** filter, and the CSV exports only the matching orders (`CSV (n)`).
- Each listing on the Stats screen is **tappable** to show its own 7-day views
  chart, and there is a **monthly revenue** chart (6 months, cancelled orders
  excluded).
- **Push → screen:** tapping a notification opens the app and goes straight to the
  **order detail** or the **chat** (`type=order|chat` + `orderId`/`chatId` extras;
  `singleTop` + `onNewIntent`).
- **Chat unread badge:** the header badge sums `unread.<uid>` across chats, and the
  Inbox has a **per-thread badge** (new messages in bold). Opening a thread resets
  `unread.<uid>` to 0.
- **Deep link debugging:** in debug builds, `readPushTarget` and the order/chat
  opening log under the `CamiguinPush` tag. Testing without a push:
  ```bash
  adb shell am start -n com.camiguinmarket.app/.MainActivity \
    --es type order --es orderId <ORDER_ID>
  adb logcat -s CamiguinPush
  ```
  (singleTop + `onNewIntent`, so it works even when the app is already open.)
- **Sales CSV:** the sales dashboard has a `CSV` button that exports
  **daily revenue** (`date,orders,revenue` + a TOTAL row) for the selected range.
- **Push in the app:** `sendPush(kind, orderId/chatId)` (in `Push.kt`) POSTs to
  `${R2_UPLOAD_URL}/push` after an order, a status update, and a new chat message —
  best-effort, it never blocks the order/chat.
- No more Firebase Functions: `functions/` and its deploy workflow were removed
  because push is hosted in the Worker.

### Sharing and the offline cart

- The listing detail has **Share** (Android share sheet) — title, price, town, and
  contact are sent.
- The cart has a **local cache** (`SharedPreferences`, `cart_cache`): items are
  still visible offline, with a small "Offline" notice. The Firestore offline
  queue handles the writes when the internet comes back.

## Posting, editing, and deleting a listing

- **`+ Sell`** — post a listing (name, price, contact, category, town, and an
  optional pickup pin on the map).
- **`✏️ Edit`** — visible to the owner only, inside the listing detail. Every field
  is prefilled (including the pickup pin). The original `sellerId`, `sellerName`,
  and `createdAt` stay unchanged — the `firestore.rules` update rule needs that.
- **`Delete`** — owner only as well.

## Listing photos (Cloudflare R2)

- **`📷 Choose photos`** in the post dialog, or **`🖼️ Replace photos`** when editing.
  Photos come from the gallery and are uploaded to our **Cloudflare Worker**, which
  stores them in **R2** at `listing_images/{uid}/{timestamp}-{random}.jpg`. The URL
  is saved in the listing's `imageUrl` field.
- The thumbnail appears in lists, and the full photo in the listing detail.
- **No R2 credentials in the app.** The app uses its **Firebase ID token**, and the
  Worker verifies it (Firebase Identity Toolkit) and builds the key from the
  token's `uid` — so a user can only write inside their own folder.
- Worker limits: **image/jpeg, image/png, image/webp** only, and **under 5 MB**.
  The bucket can stay private because the Worker (`/i/…`) serves the images.

**Worker setup:** see [`worker/README.md`](worker/README.md) (about 5 minutes:
`wrangler r2 bucket create`, `wrangler secret put FIREBASE_WEB_API_KEY`,
`wrangler deploy`). Then set the URL in `R2_UPLOAD_URL`.

⚠️ Without `R2_UPLOAD_URL` the app still works — only photo upload fails, and the
dialog shows a clear error message.

## API keys

The **map needs no key**: Leaflet, the OpenStreetMap tiles, OSRM and Nominatim
are all free and keyless. There is no Google Maps or Mapbox entry to fill in
`local.properties` anymore.

What is still needed: a **Firebase project** (`app/google-services.json`) and,
optionally, `R2_UPLOAD_URL` for photo uploads through the Cloudflare Worker.

On GitHub: **Settings → Secrets and variables → Actions**, add `R2_UPLOAD_URL`
and the signing secrets below if you want a signed release.

The build workflow writes a **Build secrets status** table to the run summary. It
shows ✅ for every secret that is set and ⬜ for the ones that are not — values are
never printed. Debug and release builds both run without any map key.

### Signing the release APK (optional)

For an **installable** release APK, add these secrets:

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | Your keystore file, base64 encoded: `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | The store password |
| `KEY_ALIAS` | The key alias |
| `KEY_PASSWORD` | The key password |

When `KEYSTORE_BASE64` is present, CI decodes the keystore to the runner temp,
creates a `release` signing config, and the output is **signed**
(`app-release.apk`). Without it the APK is **unsigned**
(`app-release-unsigned.apk`) and cannot be installed.

On a local machine, pass them as environment variables or Gradle properties
(`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`) — or write them into the uncommitted `local.properties`:

```properties
RELEASE_KEYSTORE_FILE=/home/you/release.keystore
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

## Firestore security rules

`firestore.rules` controls database access:

- **read** — must be signed in (`request.auth != null`).
- **create / update / delete** — owner only (`sellerId == request.auth.uid`), and a
  listing cannot be transferred to another seller.
- **carts / favorites** — a user can only read and write their own.
- **notifications** — owner only can read; any signed-in user can create a
  notification for someone else, but `fromUid` must be themselves.
- **orders** — the buyer creates; only the buyer and seller can read; the seller can
  update the status; the buyer can cancel (`pending` → `cancelled`) only.
- **chats** — only the two participants can read/write; a message requires
  membership in the chat (checked with `get()`).
- **savedSearches** — owner only.
- **users/{uid}/tokens** — FCM tokens, owner only.
- **listings.views / viewsByDay / cartAdds** — any signed-in user can add **+1** to
  those fields only (`hasOnly(['views','viewsByDay'])` or
  `hasOnly(['cartAdds'])`), and `get(field, 0)` is used on both sides so it does not
  error when the field is still missing.
- **ratings** — create only by the buyer of a `done` order (doc id = order id);
  readable by signed-in users; cannot be edited or deleted.
- **reports** — any signed-in user can create their own report; only the reporter
  and an admin can read it; only an admin can update it.
- **admins** — readable by signed-in users (so the app can check) but
  `write: if false`, and `isAdmin()` can delete a listing.
- **default deny** — no other collection is open.

Deploy the rules (once, or after changes):

```bash
firebase deploy --only firestore:rules --project camiguinmarket
```

Or paste the contents of `firestore.rules` into **Firebase Console → Firestore →
Rules** and press **Publish**. **Never** leave it in test mode (`allow read, write:
if true`), otherwise anyone can read and write `listings`.

### Automatic deploy through GitHub Actions

`.github/workflows/firestore-rules.yml` has two jobs:

1. **Validate** — compiles `firestore.rules` in the Firestore emulator, then runs
   `scripts/test-firestore-rules.py` (90 behavioral checks: anonymous denied,
   owner allowed, another user denied, listing not transferable, wrong type for
   `price` denied, **and the exact documents the app writes** — `id = ""`, `price`
   as double, `createdAt` as long, `latitude`/`longitude` as `null` when there is no
   pickup pin, `orderBy createdAt`, and the account deletion query).
   It runs on every push to `main` *and* on every pull request that changes the
   rules. A syntax error or a failing check fails the job — so broken rules cannot
   be deployed.

   Run it locally (needs JDK 21+ for the emulator):

   ```bash
   firebase emulators:exec --only firestore --project demo-camiguin \
     "python3 scripts/test-firestore-rules.py"
   ```
2. **Deploy** — `firebase deploy --only firestore:rules`. It runs only when the
   credential secret is set; otherwise it is **skipped** (not failed) and a note is
   written to the run summary.

Set the credential — **Settings → Secrets and variables → Actions →
New repository secret**. A service account is recommended:

| Secret | What it is | Status |
|---|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | The **entire JSON** key from Firebase Console → ⚙️ Project settings → **Service accounts → Generate new private key** | ✅ recommended |
| `FIREBASE_TOKEN` | Token from `firebase login:ci` | ⚠️ legacy (deprecated by Google) |

If both exist, `FIREBASE_SERVICE_ACCOUNT` wins. The service account generated in
the Firebase Console already has the right role, so no extra permission is needed.

The default project lives in `.firebaserc` (`camiguinmarket`), so `--project` is
not needed every time.

**Important:** those secrets grant full access to the Firebase project — treat them
as passwords. They are never printed by CI and never committed; the service account
key is written only to `$RUNNER_TEMP` and deleted after the job.

## Account deletion (Play Store requirement)

If the app can create accounts, the user must be able to delete one:

- **In the app** — press **🗑️** in the top bar inside the market screen. All of the
  user's Firestore listings are deleted, then the Firebase Auth account.
- **On the web** — `docs/account-deletion.html`. Host it with **GitHub Pages**
  (Settings → Pages → Branch: `main`, folder: `/docs`) and put the URL in Play
  Console → *App content → Data deletion*.

**Important:** replace `YOUR_SUPPORT_EMAIL_HERE` in `docs/account-deletion.html`
with your real support email before publishing the page.

If the user's login is old, Firebase returns
`FirebaseAuthRecentLoginRequiredException` and asks them to log out/log in again —
that is normal Firebase behaviour.

## Building

```bash
gradle assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # release APK (signed with a keystore, unsigned without)
gradle bundleRelease        # release AAB: app/build/outputs/bundle/release/app-release.aab
```

The `.aab` (Android App Bundle) is the format the **Google Play Console** accepts.
With a keystore it is signed; without one it is unsigned and cannot be uploaded to
Play.

`.github/workflows/firestore-rules.yml` deploys the Firestore rules (see *Firestore
security rules* above). `.github/workflows/build.yml` builds the debug APK on every
push to `main` and uploads it as an artifact. When both map secrets are set it also
builds the release variant and uploads it as `CamiguinMarket-release-apk` (APK) and
`CamiguinMarket-release-aab` (AAB for the Play Store).

### Signing the release build

Every update must be signed with the same key, so create the keystore once and
keep it safe — it is the app's identity, and a lost key means the app can never be
updated again unless Google Play App Signing is enabled.

```bash
# Once, on your own machine (not in this repo — *.jks is gitignored):
keytool -genkeypair -v -keystore camiguin-release.jks -alias camiguin \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Camiguin Market, O=Camiguin Market, C=PH"

# One line, no wrapping (Linux):
base64 -w0 camiguin-release.jks > camiguin-release.jks.b64

# macOS:
base64 -i camiguin-release.jks | tr -d '\n' > camiguin-release.jks.b64
```

Add four repository secrets under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the contents of `camiguin-release.jks.b64` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `camiguin` |
| `KEY_PASSWORD` | the key password |

The workflow decodes the keystore into the runner's temp directory (never the
repo), builds `assembleRelease` + `bundleRelease`, and runs `apksigner verify
--print-certs` so a build that is unsigned or signed with another key fails the
run instead of reaching a phone. The keystore is never committed: `*.jks`,
`*.keystore`, `*.jks.b64` and `keystore.properties` are gitignored.

To sign locally, put the same values in `local.properties` (it is gitignored):

```properties
RELEASE_KEYSTORE_FILE=/absolute/path/to/camiguin-release.jks
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=camiguin
RELEASE_KEY_PASSWORD=…
```

Never send these values in chat, a commit or a screenshot.

### Publishing a release

`.github/workflows/release.yml` builds the signed APK + AAB and attaches them to
a **GitHub Release**, which gives you a permanent download link (Actions
artifacts expire after 90 days).

Run it from the Actions tab → *Release APK* → *Run workflow*, or push a tag:

```bash
git tag v0.8 && git push origin v0.8
```

On a manual run the tag comes from the app's `versionName` (`v0.8`), so bump
`versionCode`/`versionName` in `app/build.gradle.kts` before releasing. The job
stops early if the four signing secrets are missing, and verifies the APK with
`apksigner` before publishing, so a release is always installable as an update.

## CI smoke tests (emulator)

The `adb-smoke-test.yml` workflow boots an Android emulator and runs the
instrumented smoke tests in `app/src/androidTest/`. The full and subset jobs
need a signed-in test account, so add these repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | What it is |
|---|---|
| `SMOKE_TEST_EMAIL` | Email of an existing Camiguin Market test account |
| `SMOKE_TEST_PASSWORD` | Its password |

Without them the smoke jobs fail fast with a clear error; the `fake-adb`
self-test and the lint job still run without these secrets. The test account's
data may be mutated by the smoke flows (listings, cart, orders), so use a
dedicated account rather than a personal one.

### Local emulator run

The `camiguin-a36` AVD (Android 36, `google_apis`, x86_64) boots reliably with
graphics acceleration disabled, which avoids the `surfaceflinger`
`hasReadColorBufferDma` crash seen on the Android 37 images:

```bash
$ANDROID_HOME/emulator/emulator -avd camiguin-a36 \
  -no-snapshot -no-audio -gpu off -no-window &
# Wait until both boot and the package service are up:
#   adb shell getprop sys.boot_completed   -> 1
#   adb shell pm list packages             -> lists packages

SMOKE_TEST_EMAIL=... SMOKE_TEST_PASSWORD=... \
  GRADLE_BIN=gradle ANDROID_SERIAL=emulator-5554 \
  bash scripts/run-smoke-tests.sh
```

`scripts/run-smoke-tests.sh` builds and installs both APKs, runs the
instrumentation, and collects artifacts on failure. Without a signed-in session
and without `SMOKE_TEST_*`, every test is skipped (`assumeTrue`), so pass the
test-account credentials to actually exercise the flows.

## Not tested yet

Runtime behaviour on a physical device is unverified (the local emulator above
covers the smoke flows): tapping and dragging the Leaflet pin, the WebView bridge
(`onPick` / `onMarker`), the live OSRM route and Nominatim search responses,
photo upload to R2 (photos are downscaled to 1600 px / JPEG 80 before upload,
so a listing photo is a few hundred KB instead of several MB), and SMS phone
verification (needs Firebase **Phone** sign-in enabled plus the app's SHA-1
fingerprint registered). What is confirmed: `gradle assembleDebug` and
`gradle assembleRelease` compile, the Worker tests pass (`node --test`), and the
JavaScript in `leaflet_map.html` parses.
