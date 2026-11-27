package com.camiguinmarket.app

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke tests for the release-critical flows.
 *
 * Credentials are deliberately not stored here. Pass them as instrumentation
 * arguments:
 *
 *   adb shell am instrument -w -r \
 *     -e smokeEmail "$SMOKE_TEST_EMAIL" \
 *     -e smokePassword "$SMOKE_TEST_PASSWORD" \
 *     com.camiguinmarket.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The data-dependent flows expect the listings written by
 * `scripts/seed-smoke-listings.sh`. They belong to a synthetic seller, so the
 * test account really gets an "Add to cart" action instead of its own listing.
 * Without the seeds those tests fail, not skip: a silent skip hid regressions.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var device: UiDevice

    companion object {
        /** Every seeded listing title starts with this. */
        private const val SEED_PREFIX = "Smoke seed"
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        // Animations make waitUntil/fling assertions flaky; restore the device's
        // own values afterwards so a shared phone is left as it was found.
        disableAnimations()
        enableNetwork()
        // Checkout asks for this lazily; the system dialog would block the taps.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    "android.permission.POST_NOTIFICATIONS"
                )
            }
        }
        ensureSignedIn()
    }

    @After
    fun tearDown() {
        enableNetwork()
        restoreAnimations()
        runCatching { device.pressBack() }
        prefs().edit().putString("lang", "en").apply()
    }

    @Test
    fun bisayaToggleUpdatesLabelsAndPersists() {
        openProfile()
        // The profile row shows the language that is currently selected and
        // flips between the two when tapped, so normalise to English first.
        if (hasText("Cebuano")) {
            composeRule.onNodeWithText("Cebuano").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { prefs().getString("lang", "en") == "en" }
        }

        composeRule.onNodeWithText("English").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { prefs().getString("lang", "en") == "ceb" }
        composeRule.onNodeWithText("Pinulongan").assertExists()
        composeRule.onNodeWithText("Cebuano").performScrollTo().assertExists()

        composeRule.onNodeWithText("Cebuano").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { prefs().getString("lang", "en") == "en" }
        composeRule.onNodeWithText("Language").assertExists()
    }

    @Test
    fun nearbyChipsSelectAndReset() {
        openHome()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        runCatching { device.executeShellCommand("settings put secure location_mode 3") }

        // The home filters are the 5/10/25 km chips, and tapping the selected
        // chip clears the radius, so there is no separate "Off" chip.
        composeRule.onNodeWithText("5 km").performClick()
        composeRule.onNodeWithText("5 km").assertIsSelected()
        composeRule.onNodeWithText("5 km").performClick()
        composeRule.onNodeWithText("5 km").assertIsNotSelected()
    }

    @Test
    fun nearbyRadiusWithoutFixShowsPromptAndLeavesFeedUnchanged() {
        openHome()
        // Grant the permission so selecting a radius never pops a system dialog,
        // but keep location services off so no real fix can ever arrive.
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val originalMode = runCatching {
            device.executeShellCommand("settings get secure location_mode").trim()
        }.getOrDefault("3")
        runCatching { device.executeShellCommand("settings put secure location_mode 0") }
        runCatching { device.executeShellCommand("cmd location set-location-enabled false") }

        try {
            assertTrue(
                "Seeded listings never appeared on Home: ${screenSummary()} | " +
                    listingDiagnostics(),
                awaitSeed()
            )

            composeRule.onNodeWithText("5 km").performClick()
            composeRule.waitForIdle()

            // With a radius chosen but no fix, the prompt must appear...
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    "Enable location to filter nearby listings.",
                    substring = true
                ).fetchSemanticsNodes().isNotEmpty()
            }
            // ...and the feed must be left untouched until a real fix arrives.
            assertTrue("Seeded listings disappeared without a location fix", seedVisible())

            composeRule.onNodeWithText("5 km").performClick()
            composeRule.waitForIdle()
        } finally {
            runCatching {
                device.executeShellCommand("settings put secure location_mode $originalMode")
            }
            runCatching { device.executeShellCommand("cmd location set-location-enabled true") }
        }
    }

    @Test
    fun offlineCacheKeepsListingsVisible() {
        openHome()
        // The cache is written from the feed, and the file exists even when the
        // feed was empty, so a listing must be on screen before going offline.
        assertTrue(
            "No seeded listing to cache: ${screenSummary()} | " + listingDiagnostics(),
            awaitSeed()
        )

        disableNetwork()
        try {
            composeRule.activityRule.scenario.recreate()
            // The cached feed is the behaviour under test. The banner is checked
            // separately: the app sets it only when the listings listener returns
            // an error, and Firestore serves cached snapshots instead of erroring,
            // so its absence with the radio off is not a failure.
            assertTrue(
                "Cached seeded listing did not render with the network off",
                awaitSeed(40_000)
            )
            val bannerShown = runCatching {
                composeRule.waitUntil(3_000) {
                    composeRule.onAllNodesWithText("Offline", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }.isSuccess
            println(
                if (bannerShown) "Offline banner was shown while the radio was off"
                else "Offline banner not shown; the app sets it only on a listings listener error"
            )
        } finally {
            enableNetwork()
        }
    }

    @Test
    fun shareCardCreatesPng() {
        openHome()
        shareFiles().forEach { it.delete() }
        assertTrue(
            "No seeded listing to share: ${screenSummary()} | " + listingDiagnostics(),
            awaitSeed()
        )

        assertTrue(
            "Seeded listing detail did not open: ${screenSummary()}",
            openFirstSeedDetail()
        )
        composeRule.onNodeWithText("↗").performClick()

        composeRule.waitUntil(30_000) { shareFiles().isNotEmpty() }
        val file = shareFiles().maxByOrNull { it.lastModified() }
        assertNotNull("Share card was not written to the app cache", file)
        val bitmap = BitmapFactory.decodeFile(file!!.absolutePath)
        assertNotNull("Share card is not a readable image", bitmap)
        // renderShareCard draws a 1080x1350 story-sized card.
        assertEquals("Share card width", 1080, bitmap!!.width)
        assertEquals("Share card height", 1350, bitmap.height)
    }

    @Test
    fun postListingCreatesDocument() {
        // Post a listing without photos (no R2 upload required), prove the
        // document reached Firestore, then remove it so re-runs stay clean.
        val db = FirebaseFirestore.getInstance()
        val seller = uid()
        val startedAt = System.currentTimeMillis()

        composeRule.waitUntil(10_000) { isMarketVisible() }
        composeRule.onNodeWithText("+ Sell").performClick()
        assertTrue(
            "Post form did not open: ${screenSummary()}",
            awaitCondition(10_000) { hasText("Sell an item") }
        )

        // Only the dialog's own fields: a plain by-index scan also picks up the
        // Home search box behind it, which left the form incomplete.
        val fields = composeRule.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        assertEquals(
            "Expected four text fields in the post form, saw ${fields.fetchSemanticsNodes().size}",
            4,
            fields.fetchSemanticsNodes().size
        )
        fields[0].performTextReplacement("Smoke test listing")
        fields[1].performTextReplacement("Automated smoke test listing")
        fields[2].performTextReplacement("100")
        fields[3].performTextReplacement("09171234567")
        composeRule.onNodeWithText("Post listing").performClick()

        assertTrue(
            "Post form did not close after submitting: ${screenSummary()}",
            awaitCondition(10_000) { !hasText("Sell an item") }
        )

        var createdIds: List<String> = emptyList()
        val created = awaitCondition(30_000) {
            createdIds = runCatching {
                Tasks.await(
                    db.collection("listings").whereEqualTo("sellerId", seller).get(),
                    20,
                    TimeUnit.SECONDS
                ).documents.filter { doc ->
                    doc.getString("title") == "Smoke test listing" &&
                        (doc.getLong("createdAt") ?: 0L) >= startedAt - 5_000
                }.map { it.id }
            }.getOrDefault(emptyList())
            createdIds.isNotEmpty()
        }
        createdIds.forEach { id ->
            runCatching { db.collection("listings").document(id).delete() }
        }
        assertTrue("Posting created no listing document for $seller", created)
    }

    @Test
    fun cartCheckoutPlacesOrder() {
        // The seeded listings belong to a different seller, so the test account
        // gets a real "Add to cart" action instead of skipping.
        val db = FirebaseFirestore.getInstance()
        val buyer = uid()
        val ordersBefore = buyerOrderIds(db, buyer)

        openHome()
        assertTrue(
            "Seeded listing did not appear on Home: ${screenSummary()} | " +
                listingDiagnostics(),
            awaitSeed()
        )
        assertTrue(
            "Seeded listing has no add-to-cart action: ${screenSummary()}",
            openFirstSeedDetail()
        )
        composeRule.onNodeWithText("Add to cart", substring = true).performClick()

        composeRule.onNodeWithText("Cart").performClick()
        assertTrue(
            "Cart never showed a Checkout button: ${screenSummary()}",
            awaitCondition(15_000) { hasText("Checkout", substring = true) }
        )
        composeRule.onNodeWithText("Checkout", substring = true).performClick()

        // Known blocker, stated plainly: checkoutCart() reads checkouts/{id} and
        // then writes the orders. The rules deployed on the project still combine
        // that collection's read and write behind `request.resource`, which is
        // undefined for a read, so the pre-check read is denied and no order is
        // ever written. This branch's firestore.rules split read from write; the
        // order lands once those rules are deployed.
        assertTrue(
            "Checkout created no order document for $buyer — the deployed Firestore " +
                "rules block checkout: checkoutCart() reads checkouts/{id} before " +
                "writing the order, and the rules currently on the project deny that " +
                "read. This branch's firestore.rules allow the read and are the fix; " +
                "the order appears once they are deployed. " +
                "checkouts read probe: ${checkoutReadProbe()}. ${screenSummary()}",
            awaitCondition(30_000) {
                (buyerOrderIds(db, buyer) - ordersBefore).isNotEmpty()
            }
        )
        assertTrue(
            "Cart was not emptied after checkout: ${screenSummary()}",
            awaitCondition(20_000) { hasText("Your cart is empty.", substring = true) }
        )

        // The order is really visible in the buyer's list, not just written.
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithText("Orders").performScrollTo().performClick()
        assertTrue(
            "Seeded order did not appear under Orders: ${screenSummary()}",
            awaitCondition(20_000) {
                hasText("I'm the Buyer") && hasText(SEED_PREFIX, substring = true)
            }
        )
    }

    @Test
    fun ordersScreenRenders() {
        openProfile()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Orders").fetchSemanticsNodes().isNotEmpty()
        }
        // The profile is a scrolling column; an off-screen row swallows the tap.
        composeRule.onNodeWithText("Orders").performScrollTo().performClick()
        // The Buyer/Seller pills are static chrome, but the screen only exists
        // once the orders listener has attached.
        assertTrue(
            "Orders screen did not render its Buyer/Seller toggle: ${screenSummary()}",
            awaitCondition(15_000) {
                hasText("I'm the Buyer") || hasText("I'm the Seller")
            }
        )
        composeRule.onNodeWithText("I'm the Buyer").assertExists()
    }

    private fun prefs() = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private fun hasText(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes().isNotEmpty()

    private fun seedVisible(): Boolean = hasText(SEED_PREFIX, substring = true)

    /**
     * Polls the semantics tree instead of blocking in `waitUntil`, so a data
     * dependent screen can be failed with a bounded wait and a clear message.
     */
    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return true
            Thread.sleep(250)
        }
        return runCatching(predicate).getOrDefault(false)
    }

    /** Waits for a seeded listing, scrolling the feed down while it loads. */
    private fun awaitSeed(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var swipes = 0
        while (System.currentTimeMillis() < deadline) {
            if (seedVisible()) return true
            if (swipes < 6) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    20
                )
                composeRule.waitForIdle()
                swipes += 1
            } else {
                Thread.sleep(250)
            }
        }
        return runCatching { seedVisible() }.getOrDefault(false)
    }

    /**
     * Opens the first seeded listing's detail by tapping the feed card node
     * itself, then waits for its add-to-cart action.
     *
     * The card is located by semantics ([seedCardNode]), so the tap lands on the
     * card rather than on a screen coordinate; the card is scrolled into view
     * first because the seeds sit below the hero, categories and trending row.
     *
     * The tap is placed a quarter of the way down the card, over its title, not
     * at the card's centre: the centre of a short card can fall on the card's own
     * "Add to Cart" button, which adds the item instead of opening the detail.
     */
    private fun openFirstSeedDetail(timeoutMs: Long = 25_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!seedVisible() && !awaitSeed(5_000)) return false
            val card = seedCardNode()
            if (card != null) {
                val tapped = runCatching {
                    card.performScrollTo()
                    composeRule.waitForIdle()
                    card.performTouchInput { click(percentOffset(0.5f, 0.25f)) }
                }.isSuccess
                if (tapped && awaitCondition(6_000) {
                        hasText("Add to cart", substring = true)
                    }
                ) return true
            }
            Thread.sleep(250)
        }
        return false
    }

    /**
     * The feed card for a seeded listing: the clickable node whose merged text
     * carries the seed title. Tapping it hits the card, whereas the bare title
     * text node can sit under the sticky search bar and swallow the tap.
     */
    private fun seedCardNode() = runCatching {
        composeRule.onAllNodes(
            hasClickAction() and androidx.compose.ui.test.hasText(SEED_PREFIX, substring = true)
        ).onFirst()
    }.getOrNull()

    /**
     * Read probe for `checkouts/{id}`, the step that fails first when the
     * deployed rules deny it. Quoted in the cart test's failure message.
     */
    private fun checkoutReadProbe(): String = runCatching {
        val snap = Tasks.await(
            FirebaseFirestore.getInstance().collection("checkouts")
                .document("smoke-rules-probe").get(),
            15,
            TimeUnit.SECONDS
        )
        "read allowed (probe exists=${snap.exists()})"
    }.getOrElse { error ->
        "read denied or failed: ${error.message ?: error::class.java.simpleName}"
    }

    /**
     * What the signed-in SDK itself sees, so a "seeded but not on screen" failure
     * says whether the data is missing, denied, or a UI problem.
     */
    private fun listingDiagnostics(): String = runCatching {
        val db = FirebaseFirestore.getInstance()
        val query = db.collection("listings")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
        val snap = Tasks.await(query.get(), 25, TimeUnit.SECONDS)
        val titles = snap.documents.take(3).map { it.getString("title") }
        "project=${db.app.options.projectId} serverListings=${snap.size()} titles=$titles"
    }.getOrElse {
        val project = runCatching { FirebaseFirestore.getInstance().app.options.projectId }
            .getOrDefault("?")
        "project=$project listingsError=${it::class.simpleName}: ${it.message}"
    }

    /** Node counts for the labels under test, quoted in failure messages. */
    private fun screenSummary(): String =
        listOf("Home", "Cart", "Profile", "Orders", "Add to cart", "Checkout", "Sell")
            .joinToString { label ->
                val count = runCatching {
                    composeRule.onAllNodesWithText(label, substring = true)
                        .fetchSemanticsNodes().size
                }.getOrDefault(-1)
                "$label=$count"
            } + " offline=${hasText("Offline", substring = true)}"

    private fun openHome() {
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
    }

    private fun openProfile() {
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Language").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Pinulongan").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun isMarketVisible(): Boolean =
        composeRule.onAllNodesWithText("Profile").fetchSemanticsNodes().isNotEmpty() &&
            composeRule.onAllNodesWithText("Cart").fetchSemanticsNodes().isNotEmpty()

    private fun isLoginFormVisible(): Boolean =
        composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2

    private fun ensureSignedIn() {
        composeRule.waitUntil(10_000) {
            isMarketVisible() ||
                isLoginFormVisible() ||
                composeRule.onAllNodesWithText("I already have an account")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (isMarketVisible()) return

        val email = args.getString("smokeEmail")
        val password = args.getString("smokePassword")
        assumeTrue(
            "Set smokeEmail and smokePassword instrumentation arguments, or sign in first",
            !email.isNullOrBlank() && !password.isNullOrBlank()
        )

        // The welcome screen has used either wording; click whichever is shown.
        listOf("I already have an account", "Already have an account? Log in")
            .firstOrNull { composeRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
            ?.let { composeRule.onNodeWithText(it).performClick() }

        composeRule.waitUntil(10_000) { isLoginFormVisible() }
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields.onFirst().performTextReplacement(email!!)
        fields.onLast().performTextReplacement(password!!)
        composeRule.onNodeWithText("Log in").performClick()
        composeRule.waitUntil(30_000) { isMarketVisible() }
    }

    private fun shareFiles(): List<File> =
        context.cacheDir.listFiles { file ->
            file.name.startsWith("share_card_") && file.name.endsWith(".png")
        }?.toList().orEmpty()

    private fun uid(): String =
        requireNotNull(FirebaseAuth.getInstance().currentUser?.uid) {
            "No signed-in Firebase user; the smoke account must be signed in"
        }

    /** Order document ids for the signed-in buyer, used to detect a new order. */
    private fun buyerOrderIds(db: FirebaseFirestore, buyer: String): Set<String> =
        runCatching {
            Tasks.await(
                db.collection("orders").whereEqualTo("buyerId", buyer).get(),
                20,
                TimeUnit.SECONDS
            ).documents.map { it.id }.toSet()
        }.getOrDefault(emptySet())

    private fun disableNetwork() {
        runCatching { device.executeShellCommand("cmd connectivity airplane-mode enable") }
        runCatching { device.executeShellCommand("svc wifi disable") }
        runCatching { device.executeShellCommand("svc data disable") }
        awaitDefaultNetwork(up = false)
    }

    private fun enableNetwork() {
        runCatching { device.executeShellCommand("svc data enable") }
        runCatching { device.executeShellCommand("svc wifi enable") }
        runCatching { device.executeShellCommand("cmd connectivity airplane-mode disable") }
        awaitDefaultNetwork(up = true)
    }

    /** `dumpsys connectivity` names the default network; "none" means no route. */
    private fun defaultNetworkIsUp(): Boolean {
        val dump = runCatching { device.executeShellCommand("dumpsys connectivity") }
            .getOrDefault("")
        if (dump.contains("Active default network: none")) return false
        if (dump.contains("Active default network:")) return true
        // Some builds omit the line; fall back to the airplane-mode setting.
        return runCatching {
            device.executeShellCommand("settings get global airplane_mode_on").trim() != "1"
        }.getOrDefault(true)
    }

    /**
     * Radio toggles are asynchronous, so poll (bounded) instead of assuming the
     * device is online the moment the shell command returns. A miss only logs:
     * the assertions that follow fail with their own message.
     */
    private fun awaitDefaultNetwork(up: Boolean, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (defaultNetworkIsUp() == up) return true
            Thread.sleep(500)
        }
        val settled = defaultNetworkIsUp() == up
        if (!settled) {
            println(
                "WARNING: default network was still ${if (up) "down" else "up"} " +
                    "after ${timeoutMs}ms; check Wi-Fi/data manually"
            )
        }
        return settled
    }

    private val animationSettings = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )
    private val savedAnimationSettings = mutableMapOf<String, String>()

    private fun disableAnimations() {
        animationSettings.forEach { name ->
            // An unset scale reads as the literal string "null"; remember that so
            // the teardown can delete the row instead of storing "null".
            val current = runCatching {
                device.executeShellCommand("settings get global $name").trim()
            }.getOrDefault("null")
            savedAnimationSettings[name] = current
            runCatching { device.executeShellCommand("settings put global $name 0") }
        }
    }

    private fun restoreAnimations() {
        savedAnimationSettings.forEach { (name, value) ->
            runCatching {
                if (value.isEmpty() || value == "null") {
                    device.executeShellCommand("settings delete global $name")
                } else {
                    device.executeShellCommand("settings put global $name $value")
                }
            }
        }
        savedAnimationSettings.clear()
    }
}
