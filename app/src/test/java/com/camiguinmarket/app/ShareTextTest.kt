package com.camiguinmarket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    @Test
    fun shareTextKeepsListingDetailsAndAddsDeepLink() {
        val text = shareListingText(
            Listing(
                id = "listing123",
                title = "Fresh Tuna",
                price = 250.0,
                town = "Mambajao",
                contact = "0917 000 0000"
            )
        )

        assertTrue(text.contains("Fresh Tuna"))
        assertTrue(text.contains("P250.00"))
        assertTrue(text.contains("Mambajao"))
        assertTrue(text.contains("0917 000 0000"))
        assertTrue(text.contains("camiguinmarket://listing/listing123"))
    }

    @Test
    fun listingDeepLinkEncodesTheId() {
        assertEquals("camiguinmarket://listing/a%20b", listingDeepLink("a b"))
    }

    @Test
    fun blankListingIdFallsBackToTheAppMessage() {
        val text = shareListingText(Listing(title = "No id"))

        assertTrue(text.contains("View it in the Camiguin Market app."))
    }
}
