package com.camiguinmarket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParsingTest {

    @Test
    fun pushTarget_order() {
        assertEquals("order:abc", MainActivity.parsePushTarget("order", "abc", null, null))
    }

    @Test
    fun pushTarget_chat() {
        assertEquals("chat:xyz", MainActivity.parsePushTarget("chat", null, "xyz", null))
    }

    @Test
    fun pushTarget_saved() {
        assertEquals("saved:s1", MainActivity.parsePushTarget("saved", null, null, "s1"))
        assertEquals("saved:s1", MainActivity.parsePushTarget("saved_search", null, null, "s1"))
    }

    @Test
    fun pushTarget_missingId() {
        assertNull(MainActivity.parsePushTarget("order", null, null, null))
        assertNull(MainActivity.parsePushTarget("order", "   ", null, null))
    }

    @Test
    fun pushTarget_unknownType() {
        assertNull(MainActivity.parsePushTarget("foo", "abc", null, null))
        assertNull(MainActivity.parsePushTarget(null, "abc", null, null))
    }

    @Test
    fun customScheme_listing() {
        assertEquals("listing:l1", MainActivity.parseCustomSchemeHost("listing", "l1"))
    }

    @Test
    fun customScheme_order_chat() {
        assertEquals("order:o1", MainActivity.parseCustomSchemeHost("order", "o1"))
        assertEquals("chat:c1", MainActivity.parseCustomSchemeHost("chat", "c1"))
    }

    @Test
    fun customScheme_unknownHost() {
        assertNull(MainActivity.parseCustomSchemeHost("profile", "p1"))
        assertNull(MainActivity.parseCustomSchemeHost("listing", null))
        assertNull(MainActivity.parseCustomSchemeHost("listing", "   "))
    }

    @Test
    fun httpsAppLink_listing_order_chat() {
        assertEquals("listing:l1", MainActivity.parseHttpsAppLinkKind("listing", "l1"))
        assertEquals("order:o1", MainActivity.parseHttpsAppLinkKind("order", "o1"))
        assertEquals("chat:c1", MainActivity.parseHttpsAppLinkKind("chat", "c1"))
    }

    @Test
    fun httpsAppLink_unknownKind() {
        assertNull(MainActivity.parseHttpsAppLinkKind("saved", "s1"))
        assertNull(MainActivity.parseHttpsAppLinkKind("foo", "x"))
        assertNull(MainActivity.parseHttpsAppLinkKind("listing", ""))
    }
}
