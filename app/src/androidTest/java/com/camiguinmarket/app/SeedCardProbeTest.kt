package com.camiguinmarket.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY diagnostic, not a behaviour test.
 *
 * It signs in, opens Home, waits for a seeded listing and then reports what the
 * semantics tree really contains around the seed cards: every test tag present
 * (merged and unmerged), the tags that start with `listing_card_`, the counts
 * for each seed's exact tag/title in both trees, and the tree lines around any
 * seed text or card tag. It then fails with that evidence so the run's failure
 * artifact carries it, because the plain lookup only ever reported `tagged=0`.
 *
 * Delete this file once the card lookup in SmokeTest is fixed.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class SeedCardProbeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var device: UiDevice

    private data class Seed(val id: String, val title: String)

    companion object {
        /** Every seeded listing title starts with this. */
        private const val SEED_PREFIX = "Smoke seed"

        /** `ListingCard` tags itself `listing_card_<id>`; see Extras.kt. */
        private const val CARD_TAG_PREFIX = "listing_card_"
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        ensureSignedIn()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun dumpSeedCardSemantics() {
        val seedOnScreen = awaitSeed()
        val seeds = seedRefs()
        val merged = tags(useUnmergedTree = false)
        val unmerged = tags(useUnmergedTree = true)

        val evidence = buildString {
            append("seedTextOnScreen=").append(seedOnScreen)
            append(" serverSeeds=").append(seeds.size)
            append(" seedIds=").append(seeds.take(5).map { it.id })
            append("\ntagsMerged(").append(merged.size).append(")=").append(merged.take(80))
            append("\ntagsUnmerged(").append(unmerged.size).append(")=").append(unmerged.take(80))
            append("\ncardTagsMerged=").append(merged.filter { it.startsWith(CARD_TAG_PREFIX) })
            append("\ncardTagsUnmerged=").append(unmerged.filter { it.startsWith(CARD_TAG_PREFIX) })
            seeds.take(4).forEach { seed ->
                append("\n").append(seed.id)
                    .append(": tagMerged=").append(countTag(CARD_TAG_PREFIX + seed.id, false))
                    .append(" tagUnmerged=").append(countTag(CARD_TAG_PREFIX + seed.id, true))
                    .append(" titleMerged=").append(countText(seed.title, false))
                    .append(" titleUnmerged=").append(countText(seed.title, true))
            }
            append("\n--- merged tree around seed text / tags ---\n").append(treeLines(false))
            append("\n--- unmerged tree around seed text / tags ---\n").append(treeLines(true))
        }

        treeLines(false).lines().forEach { println("PROBE_MERGED $it") }
        treeLines(true).lines().forEach { println("PROBE_UNMERGED $it") }
        throw AssertionError("SEED CARD PROBE: $evidence")
    }

    /** Distinct test tags present in one of the two semantics trees. */
    private fun tags(useUnmergedTree: Boolean): List<String> = runCatching {
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag),
            useUnmergedTree = useUnmergedTree
        ).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) }
            .distinct()
    }.getOrDefault(emptyList())

    private fun countTag(tag: String, unmerged: Boolean): Int = runCatching {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = unmerged)
            .fetchSemanticsNodes().size
    }.getOrDefault(-1)

    private fun countText(text: String, unmerged: Boolean): Int = runCatching {
        composeRule.onAllNodesWithText(text, useUnmergedTree = unmerged)
            .fetchSemanticsNodes().size
    }.getOrDefault(-1)

    /**
     * The tree lines that mention a seed title, a card tag or any test tag, plus
     * two lines of context so each match keeps its node header and actions.
     */
    private fun treeLines(unmerged: Boolean): String = runCatching {
        val tree = composeRule.onRoot(useUnmergedTree = unmerged)
            .printToString(maxDepth = Int.MAX_VALUE)
        val lines = tree.lines()
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { index, line ->
            val interesting = line.contains(SEED_PREFIX) ||
                line.contains(CARD_TAG_PREFIX) ||
                line.contains("TestTag")
            if (interesting) {
                for (i in (index - 2)..(index + 2)) if (i in lines.indices) keep.add(i)
            }
        }
        keep.take(160).joinToString("\n") { lines[it] }
    }.getOrElse { "<tree unavailable: ${it::class.java.simpleName}: ${it.message}>" }

    private fun awaitSeed(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var swipes = 0
        while (System.currentTimeMillis() < deadline) {
            if (hasSeedText()) return true
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
        return runCatching { hasSeedText() }.getOrDefault(false)
    }

    private fun hasSeedText(): Boolean =
        composeRule.onAllNodesWithText(SEED_PREFIX, substring = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun seedRefs(timeoutMs: Long = 20_000): List<Seed> = runCatching {
        val snap = Tasks.await(
            FirebaseFirestore.getInstance().collection("listings")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get(),
            timeoutMs,
            TimeUnit.MILLISECONDS
        )
        snap.documents.mapNotNull { doc ->
            val title = doc.getString("title")
            if (title?.startsWith(SEED_PREFIX) == true) Seed(doc.id, title) else null
        }
    }.getOrDefault(emptyList())

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
}
