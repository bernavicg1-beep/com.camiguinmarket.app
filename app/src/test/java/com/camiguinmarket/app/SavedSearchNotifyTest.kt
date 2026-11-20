package com.camiguinmarket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker notifies the user about their own saved search, so that write skips
 * notifyUser. These tests pin what a run writes for one search — the alert, the
 * listing ids it covers and the checkpoint stored with it — and the guard every
 * other notification still goes through.
 *
 * The decision is made from the stored checkpoint, which is what lets the caller
 * write the notification and the checkpoint in one transaction: a failed write
 * leaves the old checkpoint behind, and the retry decides the same thing again.
 */
class SavedSearchNotifyTest {
    private val uid = "buyer"

    private fun listing(id: String, title: String = "Fresh Tuna", createdAt: Long = 200) = Listing(
        id = id,
        title = title,
        description = "",
        createdAt = createdAt,
        sellerId = "seller",
        category = "Food & Seafood",
        town = "Mambajao"
    )

    private fun search(id: String = "search-1", query: String = "tuna", lastCheckedAt: Long = 100) =
        SavedSearch(id = id, query = query, lastCheckedAt = lastCheckedAt)

    @Test
    fun planAlertsEachNewMatchAndStoresTheCheckpointWithIt() {
        val update = planSavedSearchUpdate(
            uid = uid,
            search = search(),
            storedCheckpoint = 100,
            listings = listOf(listing("a", createdAt = 200), listing("b", createdAt = 300)),
            now = 1_000
        )

        assertEquals("🔔 2 new listings for \"tuna\"", update?.message)
        assertEquals(300L, update?.checkpoint)
    }

    @Test
    fun planWritesNothingWhenTheStoredCheckpointAlreadyCoversTheFeed() {
        // The in-memory copy is stale (0); the stored checkpoint is what decides,
        // so listings already alerted by an earlier run are not reported again.
        val update = planSavedSearchUpdate(
            uid = uid,
            search = search(lastCheckedAt = 0),
            storedCheckpoint = 500,
            listings = listOf(listing("seen", createdAt = 200), listing("also-seen", createdAt = 300)),
            now = 1_000
        )

        assertNull(update)
    }

    @Test
    fun planAdvancesWithoutAnAlertWhenNewerListingsDoNotMatch() {
        val update = planSavedSearchUpdate(
            uid = uid,
            search = search(),
            storedCheckpoint = 100,
            listings = listOf(listing("bread", title = "Bread", createdAt = 500)),
            now = 1_000
        )

        assertNull(update?.message)
        assertEquals(500L, update?.checkpoint)
    }

    @Test
    fun planKeepsAFutureInMemoryCheckpointFromMovingBackwards() {
        val update = planSavedSearchUpdate(
            uid = uid,
            search = search(lastCheckedAt = 900),
            storedCheckpoint = 100,
            listings = listOf(listing("a", createdAt = 600)),
            now = 1_000
        )

        // 600 is not newer than the checkpoint this run honours, so nothing is
        // written — and the checkpoint is certainly not stored as 600.
        assertNull(update)
    }

    @Test
    fun aSelfAddressedNotificationNeedsTheWaiver() {
        assertTrue(canDeliverNotification("uid-1", "uid-1", allowSelfNotification = true))
        assertFalse(canDeliverNotification("uid-1", "uid-1", allowSelfNotification = false))
        assertTrue(canDeliverNotification("uid-2", "uid-1", allowSelfNotification = false))
        // A blank recipient is never valid, even with the waiver.
        assertFalse(canDeliverNotification("", "uid-1", allowSelfNotification = true))
    }

    @Test
    fun savedSearchSelfNotificationAllowedWhileChatAndOrderSelfBlocked() {
        // Saved-search alerts notify the user about their own search, so the
        // self case is explicitly allowed.
        assertTrue(canDeliverNotification("uid-1", "uid-1", allowSelfNotification = true))
        // Chat and order notifications use the default guard, so a same-to-same
        // (self) notification is blocked.
        assertFalse(canDeliverNotification("uid-1", "uid-1", allowSelfNotification = false))
        // Unrelated recipients are still allowed in both modes.
        assertTrue(canDeliverNotification("uid-2", "uid-1", allowSelfNotification = false))
        assertTrue(canDeliverNotification("uid-2", "uid-1", allowSelfNotification = true))
    }
}
