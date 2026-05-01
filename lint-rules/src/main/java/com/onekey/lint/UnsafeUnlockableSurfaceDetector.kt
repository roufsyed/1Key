package com.onekey.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile

/**
 * Fails the build when a Compose surface that renders in its own native Window
 * is used outside the lock-aware wrapper package.
 *
 * The surfaces it bans:
 *  - `androidx.compose.material3.AlertDialog`
 *  - `androidx.compose.material3.ModalBottomSheet`
 *  - `androidx.compose.material3.DropdownMenu`
 *  - `androidx.compose.material3.ExposedDropdownMenu` (scoped to ExposedDropdownMenuBox)
 *  - `androidx.compose.material3.OutlinedTextField`
 *  - `androidx.compose.material3.TextField`
 *  - `androidx.compose.ui.window.Dialog`
 *  - `androidx.compose.ui.window.Popup`
 *
 * Each of these (or their nested IME path, in the case of TextField) generates
 * pointer / text input that does not reach `Activity.onUserInteraction()`, so the
 * inactivity auto-lock would drain while the user is actively interacting. The
 * `LockAware*` wrappers in [com.onekey.core.presentation.lockaware] inject a
 * pointer/key/onValueChange ping; this detector enforces their use everywhere
 * except inside the wrapper package itself.
 *
 * Implementation:
 *  - Filter calls by simple name via [getApplicableMethodNames] — Lint then only
 *    invokes [visitMethodCall] for those names.
 *  - In the visitor, narrow further by the resolved method's package: only flag
 *    calls whose containing class is in `androidx.compose.material3` or
 *    `androidx.compose.ui.window`. This avoids false positives on incidental
 *    function names (e.g. an internal `Dialog` helper in some unrelated lib).
 *  - Skip calls originating from the `lockaware` package — that's where the
 *    sanctioned uses live.
 */
class UnsafeUnlockableSurfaceDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = BANNED_NAMES.toList()

    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        // Resolve the function's containing class. Top-level Kotlin functions
        // appear as members of a synthesised "<File>Kt" class — so the qualified
        // name will look like `androidx.compose.material3.AndroidAlertDialog_androidKt`.
        // We don't need the exact class; just that it's in one of the known packages.
        val containingPackage = method.containingClass?.qualifiedName
            ?.substringBeforeLast('.')
            ?: return
        val isMaterial3 = containingPackage == "androidx.compose.material3"
        val isUiWindow = containingPackage == "androidx.compose.ui.window"
        if (!isMaterial3 && !isUiWindow) return

        // Allow the wrapper file itself to call into M3 / UI window — that's the
        // *one* sanctioned site for these surfaces.
        val callerPackage = (context.uastFile as? UFile)?.packageName ?: ""
        if (callerPackage == LOCKAWARE_PACKAGE) return

        val simpleName = method.name
        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            buildMessage(simpleName),
        )
    }

    private fun buildMessage(simpleName: String): String {
        val wrapper = WRAPPER_BY_NAME[simpleName]
            ?: "the appropriate `LockAware*` wrapper"
        return "Use `$wrapper` from `$LOCKAWARE_PACKAGE` instead. " +
            "Raw `$simpleName` renders in a separate native Window whose touches " +
            "and IME input do not trigger `Activity.onUserInteraction()`, so the " +
            "inactivity auto-lock will drain while the user is actively interacting. " +
            "The wrapper threads a `LocalUserActivityPing` through the surface's " +
            "input path."
    }

    companion object {
        const val LOCKAWARE_PACKAGE = "com.onekey.core.presentation.lockaware"

        // Names we care about. The Lint dispatcher calls visitMethodCall only
        // when one of these matches, then we further narrow by package.
        val BANNED_NAMES = setOf(
            "AlertDialog",
            "ModalBottomSheet",
            "DropdownMenu",
            "ExposedDropdownMenu",
            "OutlinedTextField",
            "TextField",
            "Dialog",
            "Popup",
        )

        // Mapping from the banned function's simple name to the lock-aware
        // wrapper a developer should use instead. Centralised here so the
        // diagnostic message stays accurate as wrappers are renamed.
        // `AlertDialog` and `Dialog` both map to `LockAwareDialog` because the
        // Material3 alert dialog is the only dialog surface this codebase uses.
        // `Popup` has no current wrapper — we direct the developer to apply
        // `Modifier.lockAware()` manually if they have a legitimate need.
        private val WRAPPER_BY_NAME = mapOf(
            "AlertDialog" to "LockAwareDialog",
            "Dialog" to "LockAwareDialog",
            "ModalBottomSheet" to "LockAwareModalBottomSheet",
            "DropdownMenu" to "LockAwareDropdownMenu",
            "ExposedDropdownMenu" to "LockAwareExposedDropdownMenu",
            "OutlinedTextField" to "LockAwareOutlinedTextField",
            "TextField" to "LockAwareTextField",
            "Popup" to "Popup with `Modifier.lockAware()` applied to its content root",
        )

        val ISSUE: Issue = Issue.create(
            id = "UnsafeUnlockableSurface",
            briefDescription = "Compose surface bypasses the inactivity auto-lock ping",
            explanation = """
                Compose surfaces that render in a separate Window — `AlertDialog`, \
                `ModalBottomSheet`, `DropdownMenu`, `ExposedDropdownMenu`, `Dialog`, \
                `Popup`, plus the `OutlinedTextField` / `TextField` IME path — \
                receive pointer and text-input events that never reach \
                `Activity.onUserInteraction()`. The inactivity auto-lock relies on \
                that callback, so without compensation the vault locks under an \
                actively-interacting user.

                Use the wrappers in `$LOCKAWARE_PACKAGE`:

                * `LockAwareDialog`
                * `LockAwareModalBottomSheet`
                * `LockAwareDropdownMenu` / `LockAwareExposedDropdownMenu`
                * `LockAwareOutlinedTextField` / `LockAwareTextField`

                The wrappers thread a `LocalUserActivityPing` into the surface so \
                touches, key events, and IME-driven value changes reset the \
                inactivity timer.
            """,
            category = Category.SECURITY,
            severity = Severity.ERROR,
            implementation = Implementation(
                UnsafeUnlockableSurfaceDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
