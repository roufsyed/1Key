package com.onekey.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * Locks the detector's behaviour against a small fixture surface that mimics
 * Material3 / Compose UI window declarations. We don't depend on the real M3
 * library here — Lint test files compile into a self-contained sandbox, and
 * pulling M3 in would balloon test runtime. The fixtures faithfully reproduce
 * the package + simple-name combinations the detector keys off.
 */
class UnsafeUnlockableSurfaceDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = UnsafeUnlockableSurfaceDetector()

    override fun getIssues(): List<Issue> =
        listOf(UnsafeUnlockableSurfaceDetector.ISSUE)

    fun testRawAlertDialogOutsideLockawareIsAnError() {
        lint()
            .files(
                m3Stub,
                kotlin(
                    """
                    package com.onekey.feature.example
                    import androidx.compose.material3.AlertDialog
                    fun show() {
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                        )
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
            // Pin the suggested wrapper name in the diagnostic — this is the
            // user-facing copy a developer reads when the build fails. Locking
            // it here means a future rename of `LockAwareDialog` would produce
            // an obvious test failure rather than silently broken guidance.
            .expectContains("Use LockAwareDialog")
            .expectContains("com.onekey.core.presentation.lockaware")
    }

    fun testRawModalBottomSheetOutsideLockawareIsAnError() {
        lint()
            .files(
                m3Stub,
                kotlin(
                    """
                    package com.onekey.feature.example
                    import androidx.compose.material3.ModalBottomSheet
                    fun show() {
                        ModalBottomSheet(onDismissRequest = {}) {}
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
    }

    fun testRawOutlinedTextFieldOutsideLockawareIsAnError() {
        lint()
            .files(
                m3Stub,
                kotlin(
                    """
                    package com.onekey.feature.example
                    import androidx.compose.material3.OutlinedTextField
                    fun show() {
                        OutlinedTextField(value = "", onValueChange = {})
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
    }

    fun testRawAlertDialogInsideLockawarePackageIsAllowed() {
        lint()
            .files(
                m3Stub,
                kotlin(
                    """
                    package com.onekey.core.presentation.lockaware
                    import androidx.compose.material3.AlertDialog
                    fun LockAwareDialog() {
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                        )
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    fun testNonComposeFunctionsWithSameNamesAreIgnored() {
        // A user-defined function called "AlertDialog" or "TextField" outside
        // the M3 / UI window package must not trigger the rule. The detector's
        // package narrowing handles this; this test guards it from regression.
        lint()
            .files(
                kotlin(
                    """
                    package com.onekey.feature.example
                    fun TextField() {}
                    fun show() { TextField() }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    /**
     * Minimal stub of the Material3 / Compose UI window functions the detector
     * keys off. Keeps tests fast and independent of the real library version.
     */
    private val m3Stub: TestFile = kotlin(
        """
        package androidx.compose.material3
        fun AlertDialog(onDismissRequest: () -> Unit, confirmButton: () -> Unit) {}
        fun ModalBottomSheet(onDismissRequest: () -> Unit, content: () -> Unit) {}
        fun DropdownMenu(expanded: Boolean = false, onDismissRequest: () -> Unit = {}, content: () -> Unit = {}) {}
        fun OutlinedTextField(value: String, onValueChange: (String) -> Unit) {}
        fun TextField(value: String, onValueChange: (String) -> Unit) {}
        """,
    ).indented()
}
