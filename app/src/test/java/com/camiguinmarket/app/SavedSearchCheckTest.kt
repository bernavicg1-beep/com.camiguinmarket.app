package com.camiguinmarket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchCheckTest {
    private val uid = "buyer"

    private fun listing(
        id: String,
        title: String,
        createdAt: Long,
        sellerId: String = "seller",
        category: String = "Food & Seafood",
        town: String = "Mambajao",
        description: String = ""
    ) = Listing(
        id = id,
        title = title,
        description = description,
        createdAt = createdAt,
        sellerId = sellerId,
        category = category,
        town = town
    )

    private fun search(
        query: String = "",
        category: String = "All",
        town: String = "All",
        lastCheckedAt: Long = 0L
    ) = SavedSearch(
        id = "search-1",
        query = query,
        category = category,
        town = town,
        lastCheckedAt = lastCheckedAt
    )

    @Test
    fun newMatchesAreAggregatedIntoOneNotification() {
        val check = evaluateSavedSearch(
            uid = uid,
            search = search(query = "tuna", lastCheckedAt = 100),
            listings = listOf(
                listing("a", "Fresh Tuna", 200),
                listing("b", "Tuna Steak", 300),
                listing("a", "Fresh Tuna", 200), // duplicate document in the feed
                listing("old", "Tuna", 100),
                listing("other", "Bread", 400),
                listing("mine", "My Tuna", 500, sellerId = uid)
            ),
            now = 1_000
        )

        assertEquals(listOf("a", "b"), check?.matches?.map { it.id })
        assertEquals(500L, check?.latestSeenAt)
    }

    @Test
    fun matchingUsesCategoryAndTownAndIgnoresOwnListings() {
        val check = evaluateSavedSearch(
            uid = uid,
            search = search(category = "Food & Seafood", town = "Mambajao"),
            listings = listOf(
                listing("match", "Catch of the day", 200, category = "Pagkaon ug Seafood"),
                listing("wrong-town", "Catch", 300, town = "Catarman"),
                listing("mine", "Catch", 400, sellerId = uid)
            ),
            now = 1_000
        )

        assertEquals(listOf("match"), check?.matches?.map { it.id })
    }

    @Test
    fun noNewMatchesStillAdvancesCheckpointWithoutNotifying() {
        val check = evaluateSavedSearch(
            uid = uid,
            search = search(query = "tuna", lastCheckedAt = 100),
            listings = listOf(listing("bread", "Bread", 500)),
            now = 1_000
        )

        assertTrue(check?.matches?.isEmpty() == true)
        assertEquals(500L, check?.latestSeenAt)
    }

    @Test
    fun aListAtTheCheckpointIsNotNotifiedAgain() {
        val check = evaluateSavedSearch(
            uid = uid,
            search = search(query = "tuna", lastCheckedAt = 500),
            listings = listOf(listing("seen", "Tuna", 500)),
            now = 1_000
        )

        assertTrue(check?.matches?.isEmpty() == true)
        assertEquals(500L, check?.latestSeenAt)
    }

    @Test
    fun futureTimestampsDoNotMoveCheckpointPastNow() {
        val check = evaluateSavedSearch(
            uid = uid,
            search = search(query = "tuna"),
            listings = listOf(listing("future", "Tuna", 5_000)),
            now = 1_000
        )

        assertTrue(check?.matches?.isEmpty() == true)
        assertEquals(1_000L, check?.latestSeenAt)
    }

}
