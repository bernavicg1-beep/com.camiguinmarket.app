package com.camiguinmarket.app

/** What one saved search has to persist, decided from its stored checkpoint. */
internal data class SavedSearchUpdate(
    val checkpoint: Long,
    /** The notification message to write together with the checkpoint, or null to only advance. */
    val message: String?
)

/** The outcome of checking one saved search. */
internal data class SavedSearchCheck(
    val matches: List<Listing>,
    val latestSeenAt: Long
)

/**
 * Finds the listings newer than the search's checkpoint that it should report.
 * Matching is pure so it can be verified without Firestore.
 */
internal fun evaluateSavedSearch(
    uid: String,
    search: SavedSearch,
    listings: List<Listing>,
    now: Long = System.currentTimeMillis()
): SavedSearchCheck? {
    if (listings.isEmpty()) return null

    val newest = listings.maxOf { it.createdAt }
    // A client clock can put createdAt in the future. Do not let that move the
    // checkpoint ahead of real time, or later listings would be skipped.
    val latestSeenAt = maxOf(search.lastCheckedAt, minOf(newest, now))
    val matches = listings
        .asSequence()
        .filter { listing ->
            !listing.isExpired(now) && !listing.isOutOfStock() &&
                listing.createdAt > search.lastCheckedAt &&
                listing.createdAt <= now &&
                listing.sellerId != uid &&
                (search.query.isBlank() ||
                    listing.title.contains(search.query, ignoreCase = true) ||
                    listing.description.contains(search.query, ignoreCase = true)) &&
                (search.category == "All" ||
                    normalizeCategory(listing.category) == normalizeCategory(search.category)) &&
                (search.town == "All" || listing.town == search.town)
        }
        .distinctBy { it.id }
        .toList()

    return SavedSearchCheck(matches = matches, latestSeenAt = latestSeenAt)
}

/**
 * Decides what one saved search has to write, evaluated against the checkpoint
 * read from Firestore rather than a possibly stale in-memory copy. Returning
 * null means there is nothing to persist. Re-deciding from the stored checkpoint
 * on retry means listings the user was never told about are found again instead
 * of being skipped.
 */
internal fun planSavedSearchUpdate(
    uid: String,
    search: SavedSearch,
    storedCheckpoint: Long,
    listings: List<Listing>,
    now: Long = System.currentTimeMillis()
): SavedSearchUpdate? {
    val checkpoint = maxOf(storedCheckpoint, search.lastCheckedAt)
    val check = evaluateSavedSearch(
        uid = uid,
        search = search.copy(lastCheckedAt = checkpoint),
        listings = listings,
        now = now
    ) ?: return null

    if (check.latestSeenAt <= checkpoint) return null

    return SavedSearchUpdate(
        checkpoint = check.latestSeenAt,
        message = if (check.matches.isEmpty()) null else "🔔 ${check.matches.size} new listings for \"${search.label}\"".take(190)
    )
}

/**
 * Whether a notification may be delivered. Self-notifications are allowed only
 * when explicitly requested (e.g. saved-search alerts, which notify the user
 * about their own search).
 */
internal fun canDeliverNotification(
    toUid: String,
    fromUid: String,
    allowSelfNotification: Boolean = false
): Boolean {
    if (toUid.isBlank()) return false
    if (toUid == fromUid && !allowSelfNotification) return false
    return true
}
