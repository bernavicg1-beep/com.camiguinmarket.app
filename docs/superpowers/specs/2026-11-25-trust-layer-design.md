---
title: Trust Layer Design
---

# Trust Layer — Design Spec

**Date:** 2026-11-25
**Approach:** B — Surface + soft enforcement + admin/community verification
**Scope:** Phone verification, community verification, reputation, reporting,
moderation, low-rating warnings, verification requests.

## Goal

Increase trust between buyers and sellers without hard bans or payments.
No money changes hands; all signals are soft (badges, warnings, moderation).

## Components

1. **Phone verification** — self-service SMS via Firebase Phone Auth. Sets
   `phoneVerified=true` on the user document. Buyer sees a 📱 badge.
2. **Community verification** — seller requests verification; admin approves.
   Sets `verified=true`, `verifiedBy`, `verifiedAt`. Buyer sees a ✓ badge.
3. **Reputation** — average rating from `ratings/{orderId}`. Sellers below
   2.5 avg (min 3 ratings) get a low-rating banner on their own listings.
4. **Reporting** — buyers report listings; admins triage (open/in_review/
   resolved/dismissed) with priority and resolution notes.
5. **Moderation** — admins delete listings, revoke verification.
6. **Verification requests** — `verificationRequests` collection; seller
   creates pending request; admin approves/rejects.

## Data model

- `users/{uid}`: `phoneVerified`, `verified`, `verifiedBy`, `verifiedAt`
  (trust fields, admin/phone-flow only)
- `ratings/{orderId}`: buyerId, sellerId, rating (1-5), comment, createdAt
- `reports/{id}`: listingId, listingTitle, sellerId, reporterId, reporterName,
  reason, note, status, priority, resolution, createdAt, triagedAt, triagedBy
- `verificationRequests/{id}`: sellerUid, sellerName, status, createdAt,
  reviewedBy, reviewedAt
- `admins/{uid}`: exists-only gate (created via Firebase Console)

## Security rules

- Trust fields on `users/{uid}` writable only by phone-verify flow or admin.
- `ratings` immutable after create; one per order (doc id = orderId).
- `reports` read/update/delete admin-only; create any signed-in user.
- `verificationRequests` read by admin or requesting seller; create by seller
  (status must be "pending"); update/delete admin-only.

## UI surfaces

- Listing card: seller row with VerifiedBadge (compact) + rating summary.
- Listing detail: VerifiedBadge (full) + rating + LowRatingBanner (seller view).
- SellerInfoDialog: reputation, rating breakdown, verified badges.
- Profile: RequestVerificationSection + BlockedSellersScreen entry.
- AdminVerificationScreen: sellers list + verification requests tabs.
- ModerationScreen: listings + reports tabs, revoke verification action.

## Out of scope (Approach B)

- Hard bans / suspensions
- Payments / escrow
- Automated fraud detection
- Email/SMS verification of buyers
