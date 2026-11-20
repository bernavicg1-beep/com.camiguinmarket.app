package com.camiguinmarket.app

/**
 * Apply the nearby radius only when there is a real user location fix.
 * Without a fix the feed stays unchanged while the UI shows its prompt.
 */
fun applyNearbyFilter(
    listings: List<Listing>,
    userLat: Double?,
    userLng: Double?,
    radiusKm: Int?
): List<Listing> {
    if (radiusKm == null || userLat == null || userLng == null) return listings
    return listings.filter { listing ->
        val lat = listing.latitude ?: return@filter false
        val lng = listing.longitude ?: return@filter false
        haversineKm(userLat, userLng, lat, lng) <= radiusKm
    }
}

/** A radius is chosen but no real fix has arrived yet. */
fun shouldPromptForNearbyLocation(
    radiusKm: Int?,
    userLat: Double?,
    userLng: Double?
): Boolean = radiusKm != null && (userLat == null || userLng == null)

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
