#!/usr/bin/env python3
"""One-shot: the launch update prompt is shown once per installed version.

Replaces `AppUpdatePrompt()` in Extras.kt so the GitHub check is skipped, and
the dialog not repeated, once it has already been shown for VERSION_NAME.
Profile -> Check for updates keeps checking on demand.
"""
import pathlib
import sys

TARGET = pathlib.Path("app/src/main/java/com/camiguinmarket/app/Extras.kt")

OLD = '''/** Automatic check, once per launch. */
@Composable
fun AppUpdatePrompt() {
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchAppUpdate() }
        if (found != null) update = found
    }
    update?.let { u -> AppUpdateDialog(u) { update = null } }
}'''

NEW = '''/** Remembers which installed version already showed the launch prompt. */
private fun updatePrefs(context: Context) =
    context.getSharedPreferences("update_prompt", Context.MODE_PRIVATE)

/**
 * Automatic check on launch, but the prompt appears at most once per installed
 * version: dismissing it with "Later" is remembered instead of nagging on every
 * open. Profile -> Check for updates still checks whenever the user asks.
 */
@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        if (updatePrefs(context).getString("version", null) == BuildConfig.VERSION_NAME) {
            return@LaunchedEffect
        }
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchAppUpdate() }
        if (found != null) {
            // Stored before the dialog opens, so a dismissal is respected on the
            // next launch. Installing the next build changes VERSION_NAME, and
            // the newer release is announced again.
            updatePrefs(context).edit().putString("version", BuildConfig.VERSION_NAME).apply()
            update = found
        }
    }
    update?.let { u -> AppUpdateDialog(u) { update = null } }
}'''


def main() -> int:
    src = TARGET.read_text()
    if NEW in src:
        print("already applied")
        return 0
    count = src.count(OLD)
    if count != 1:
        print(f"expected exactly one AppUpdatePrompt block, found {count}", file=sys.stderr)
        return 1
    TARGET.write_text(src.replace(OLD, NEW, 1))
    print(f"patched {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
