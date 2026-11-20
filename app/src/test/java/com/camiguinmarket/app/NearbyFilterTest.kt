package com.camiguinmarket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyFilterTest {
    private val centerLat = 9.25
    private val centerLng = 124.75
    private val near = Listing(id = "near", latitude = centerLat, longitude = centerLng)
    private val far = Listing(id = "far", latitude = 9.90, longitude = 125.20)
    private val noCoordinates = Listing(id = "no-coordinates")
    private val listings = listOf(near, far, noCoordinates)

    @Test
    fun noRealFixLeavesTheFeedUnchanged() {
        assertSame(listings, applyNearbyFilter(listings, null, null, 1))
        assertSame(listings, applyNearbyFilter(listings, centerLat, null, 1))
        assertSame(listings, applyNearbyFilter(listings, null, centerLng, 1))
    }

    @Test
    fun noRadiusLeavesTheFeedUnchanged() {
        assertSame(listings, applyNearbyFilter(listings, centerLat, centerLng, null))
    }

    @Test
    fun thePromptShowsUntilARealFixExists() {
        assertTrue(shouldPromptForNearbyLocation(1, null, null))
        assertTrue(shouldPromptForNearbyLocation(1, centerLat, null))
        assertTrue(shouldPromptForNearbyLocation(1, null, centerLng))
        assertFalse(shouldPromptForNearbyLocation(1, centerLat, centerLng))
        assertFalse(shouldPromptForNearbyLocation(null, centerLat, centerLng))
    }

    @Test
    fun aRealFixFiltersByRadiusAndDropsCoordinateLessListings() {
        val filtered = applyNearbyFilter(listings, centerLat, centerLng, 5)

        assertEquals(listOf("near"), filtered.map { it.id })
    }
}
