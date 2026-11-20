package com.camiguinmarket.app

import java.net.URLEncoder
import java.util.Locale

/** Deep link the app handles in MainActivity for a single listing. */
fun listingDeepLink(id: String): String =
    if (id.isBlank()) "" else
        "camiguinmarket://listing/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20")

/** Text shared beside the listing card image. */
fun shareListingText(listing: Listing): String {
    val price = "P%.2f".format(Locale.US, listing.price)
    val link = listingDeepLink(listing.id)
    return buildString {
        append("${listing.title} — $price in ${listing.town}")
        append("\nContact: ${listing.contact}")
        if (link.isNotBlank()) {
            append("\n\nOpen listing: $link")
        } else {
            append("\n\nView it in the Camiguin Market app.")
        }
    }
}
