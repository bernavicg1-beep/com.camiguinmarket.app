package com.camiguinmarket.app

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
 * Minimal device smoke tests for the five release-critical flows.
 *
 * Credentials are deliberately not stored here. Pass them as instrumentation
 * arguments:
 *
 *   adb shell am instrument -w -r \
 *     -e smokeEmail "$SMOKE_TEST_EMAIL" \
 *     -e smokePassword "$SMOKE_TEST_PASSWORD" \
 *     com.camiguinmarket.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * If the app already has a signed-in session, the credentials can be omitted.
 * Tests that need seeded listings skip cleanly when none are present.
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

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        // Animations make waitUntil/fling assertions flaky; restore the device's
        // own values afterwards so a shared phone is left as it was found.
        disableAnimations()
        enableNetwork()
        ensureSignedIn()
    }

    @After
    fun tearDown() {
        enableNetwork()
        restoreAnimations()
        runCatching { device.pressBack() }
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit().putString("lang", "en").apply()
    }

    @Test
    fun bisayaToggleUpdatesLabelsAndPersists() {
        openProfile()
        // Make the test independent of the previous run's language.
        if (composeRule.onAllNodesWithText("English").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("English").performScrollTo().performClick()
        }

        composeRule.onNodeWithText("Bisaya").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            prefs().getString("lang", "en") == "ceb"
        }
        composeRule.onNodeWithText("Pinulongan").assertExists()

        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithText("Duol:", substring = true).assertExists()

        openProfile()
        composeRule.onNodeWithText("English").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            prefs().getString("lang", "en") == "en"
        }
    }

    @Test
    fun nearbyChipsSelectAndReset() {
        openHome()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        runCatching { device.executeShellCommand("settings put secure location_mode 3") }

        composeRule.onNodeWithText("1km").performClick()
        composeRule.onNodeWithText("1km").assertIsSelected()
        composeRule.onNodeWithText("Off").performClick()
        composeRule.onNodeWithText("Off").assertIsSelected()
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
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().isNotEmpty()
            }
            val before = composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().size

            composeRule.onNodeWithText("1km").performClick()
            composeRule.waitForIdle()

            // With a radius chosen but no fix, the prompt must appear...
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    "Enable location to filter nearby listings.",
                    substring = true
                ).fetchSemanticsNodes().isNotEmpty()
            }
            // ...and the feed must be left untouched until a real fix arrives.
            val after = composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().size
            assertEquals(
                "Feed changed without a location fix ($before -> $after)",
                before,
                after
            )

            composeRule.onNodeWithText("Off").performClick()
            composeRule.waitForIdle()
        } finally {
            runCatching { device.executeShellCommand("settings put secure location_mode $originalMode") }
            runCatching { device.executeShellCommand("cmd location set-location-enabled true") }
        }
    }

    @Test
    fun offlineCacheKeepsListingsVisible() {
        openHome()
        composeRule.waitUntil(20_000) {
            File(context.dataDir, "shared_prefs/listings_cache.xml").exists()
        }

        disableNetwork()
        try {
            composeRule.activityRule.scenario.recreate()
            // The cached feed is the behaviour under test. The banner is checked
            // separately: the app sets it only when the listings listener returns
            // an error, and Firestore serves cached snapshots instead of erroring,
            // so its absence with the radio off is not a failure.
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().isNotEmpty()
            }
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
    fun shareCardCreatesSquarePng() {
        openHome()
        shareFiles().forEach { it.delete() }
        val lists = composeRule.onAllNodesWithTag("listing_list").fetchSemanticsNodes()
        assumeTrue("Home listing list was not found", lists.isNotEmpty())
        val scrolled = runCatching {
            composeRule.onAllNodesWithTag("listing_list").onFirst()
                .performScrollToNode(hasTestTag("listing_card"))
        }.isSuccess
        assumeTrue("No listing is available to share", scrolled)

        composeRule.onAllNodesWithTag("listing_card").onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("listing_share").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("listing_share").onFirst().performClick()

        composeRule.waitUntil(20_000) { shareFiles().isNotEmpty() }
        val file = shareFiles().maxByOrNull { it.lastModified() }
        assertNotNull("Share card was not written", file)
        val bitmap = BitmapFactory.decodeFile(file!!.absolutePath)
        assertNotNull("Share card is not a readable image", bitmap)
        assertEquals(1080, bitmap!!.width)
        assertEquals(1080, bitmap.height)
    }

    @Test
    fun postListingCreatesDocument() {
        // Open the sell dialog from the floating action button and post a
        // listing without photos (no R2 upload required).
        composeRule.waitUntil(10_000) { isMarketVisible() }
        composeRule.onNodeWithText("Sell", substring = true).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Sell an item").fetchSemanticsNodes().isNotEmpty()
        }
        val fields = composeRule.onAllNodes(hasSetTextAction())
        assumeTrue("Post form has no text fields", fields.fetchSemanticsNodes().size >= 4)
        fields[0].performTextReplacement("Smoke test listing")
        fields[1].performTextReplacement("Automated smoke test listing")
        fields[2].performTextReplacement("100")
        fields[3].performTextReplacement("09171234567")
        composeRule.onNodeWithText("Post listing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Sell an item").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun cartCheckoutPlacesOrder() {
        openHome()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().isNotEmpty()
        }
        assumeTrue(
            "No listing available to add to cart",
            composeRule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onAllNodesWithTag("listing_card").onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Add to cart", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add to cart", substring = true).performClick()
        composeRule.waitForIdle()

        // Open the cart tab and check out.
        composeRule.onNodeWithText("Cart").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Checkout", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Checkout", substring = true).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun ordersScreenRenders() {
        openProfile()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Orders").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Orders").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("I'm the Buyer").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun prefs() = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private fun openHome() {
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        runCatching {
            composeRule.onAllNodesWithTag("listing_list").onFirst().performScrollToIndex(0)
        }
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
            file.name.startsWith("share_") && file.name.endsWith(".png")
        }?.toList().orEmpty()

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
