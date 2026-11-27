#!/usr/bin/env bash
# Seeds (or removes) the listings the emulator smoke suite needs.
#
# The listings belong to a synthetic seller so the test account gets a real
# "Add to cart" action; they are written with the Firestore Admin API using
# FIREBASE_SERVICE_ACCOUNT, which is never printed.
#
# Usage:
#   FIREBASE_SERVICE_ACCOUNT='<json>' scripts/seed-smoke-listings.sh seed
#   FIREBASE_SERVICE_ACCOUNT='<json>' scripts/seed-smoke-listings.sh cleanup
#
# Optional:
#   SMOKE_SEED_REQUIRED=1  fail (instead of a notice) when the credential is absent
set -euo pipefail

exec python3 "$(dirname "$0")/seed_smoke_listings.py" "${1:-seed}"
