# Trust Layer — Implementation Plan

**Date:** 2026-11-25
**Branch:** feature/trust-layer

## Summary

Implement Approach B trust layer: phone + community verification, seller
reputation, reporting, moderation, low-rating warnings, verification requests.
No hard bans, no payments.

## Files changed

- `Trust.kt` — BlockedSeller, RateLimiter, SellerReputation, SellerVerification,
  VerifiedBadge, triageReport, BlockedSellersScreen.
- `Verification.kt` — RequestVerificationSection, AdminVerificationScreen.
- `Ratings.kt` — SellerRating, submitSellerRating, RateSellerDialog,
  LowRatingBanner (delegates to loadSellerReputation).
- `Moderation.kt` — revoke verification in reports flow.
- `Strings.kt` — trust/admin strings + Cebuano overrides.
- `Extras.kt` — ListingCard seller row with badge + rating; Profile entry.
- `MainActivity.kt` — badges on listing detail, SellerInfoDialog reputation,
  PostDialog nudges, overlays.
- `AndroidManifest.xml` — app-link intent filters.
- `firestore.rules` — admin-only verification fields + verificationRequests.
- `scripts/test-firestore-rules.py` — 14 new trust-layer checks.
- `worker/index.js` — app-link routes + assetlinks.json.
- `worker/test/app-links.test.js` — deep-link fallback tests.
- `README.md` — trust-layer documentation.
- `DeepLinkParsingTest.kt` — deep link parsing tests.

## Verification

- Worker tests: 45/45 pass.
- Python syntax: validated.
- Android: compile via CI (Build APK workflow).
