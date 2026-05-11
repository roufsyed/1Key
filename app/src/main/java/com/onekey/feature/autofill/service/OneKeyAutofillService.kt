package com.onekey.feature.autofill.service

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import com.onekey.feature.autofill.domain.AutofillCaptureBuffer
import com.onekey.feature.autofill.domain.ParsedFields
import com.onekey.feature.autofill.presentation.AutofillSaveActivity
import com.onekey.feature.autofill.presentation.AutofillUnlockActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Path-A AutofillService entry point.
 *
 * Responsibility split:
 *  - This class handles the OS lifecycle, coroutine plumbing, and routing.
 *  - All parsing, matching, and Dataset construction is delegated to the
 *    `feature/autofill/domain` package (pure logic, unit-testable).
 *
 * Concurrency model:
 *  - One shared [serviceScope] backed by a [SupervisorJob] so a thrown
 *    exception in one request does not cancel siblings.
 *  - Every `onFillRequest` launches its own [Job] under that scope. The
 *    framework's [CancellationSignal] wires through to this per-request
 *    job only — sibling jobs are unaffected when one request is cancelled.
 *
 * Security:
 *  - Captured save submissions never ride Intent extras. They live in the
 *    process-local [AutofillCaptureBuffer]; the launched [AutofillSaveActivity]
 *    receives only an opaque token.
 *  - PendingIntent request codes use a monotonic counter so simultaneous
 *    requests on different focused fields never collide on
 *    `FLAG_UPDATE_CURRENT`.
 */
class OneKeyAutofillService : AutofillService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Default + CoroutineName("autofill-service")
    )
    private val pendingIntentCounter = AtomicInteger(0)
    private lateinit var entry: AutofillEntryPoint

    override fun onCreate() {
        super.onCreate()
        entry = EntryPointAccessors.fromApplication(
            applicationContext, AutofillEntryPoint::class.java
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val job = serviceScope.launch {
            try {
                val response = handleFill(request)
                callback.onSuccess(response)
            } catch (_: Throwable) {
                // Any unhandled error: tell the framework we have nothing to
                // contribute. Never throw across IPC. No logging per the
                // project's no-telemetry stance — error details would leak
                // package names and host names into logcat.
                runCatching { callback.onSuccess(null) }
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun handleFill(request: FillRequest): FillResponse? {
        // Soft kill switch: user toggle in Settings overrides the OS-level
        // enablement. If false, return nothing so the system tries the next
        // provider (or shows no chip).
        if (!entry.appPreferences().isAutofillEnabled().first()) return null

        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val packageName = structure.activityComponent?.packageName ?: return null

        if (entry.blocklist().isBlocked(packageName)) return null

        val nodes = entry.structureWalker().walk(structure)
        val parsed = entry.fieldParser().parse(nodes, packageName) ?: return null
        if (!parsed.isFillable()) return null

        val isUnlocked = entry.authRepository().isUnlocked().first()
        val saveInfo = entry.saveInfoBuilder().build(parsed, request.flags)

        val builder = FillResponse.Builder()

        if (!isUnlocked) {
            // Locked vault: single auth chip that bounces through the unlock
            // activity. The activity will rebuild a Dataset with real values
            // and return it via AutofillManager.EXTRA_AUTHENTICATION_RESULT.
            val pendingIntent = buildUnlockPendingIntent(parsed, startInSearch = false)
            builder.addDataset(entry.datasetBuilder().buildLockedDataset(parsed, pendingIntent))
        } else {
            val matches = entry.packageMatcher().findMatches(parsed)
            matches.forEach { credential ->
                runCatching {
                    builder.addDataset(entry.datasetBuilder().buildCredentialDataset(parsed, credential))
                }
            }
            // Trailing chip: route through the unlock activity and land
            // directly in search mode. The user already saw the exact-host
            // matches above; tapping this chip means they want to search the
            // rest of their vault (e.g. credentials saved under a different
            // host name).
            val pendingIntent = buildUnlockPendingIntent(parsed, startInSearch = true)
            builder.addDataset(entry.datasetBuilder().buildSearchDataset(parsed, pendingIntent))
        }

        saveInfo?.let { builder.setSaveInfo(it) }

        return try {
            builder.build()
        } catch (_: Throwable) {
            // Defensive fallback for TransactionTooLargeException at parcel
            // time. Return a single "Search 1Key" chip — always safe to
            // serialise — rather than nothing.
            buildSearchOnlyResponse(parsed, saveInfo)
        }
    }

    private fun buildSearchOnlyResponse(parsed: ParsedFields, saveInfo: android.service.autofill.SaveInfo?): FillResponse? {
        return runCatching {
            val pi = buildUnlockPendingIntent(parsed, startInSearch = true)
            val builder = FillResponse.Builder()
                .addDataset(entry.datasetBuilder().buildSearchDataset(parsed, pi))
            saveInfo?.let { builder.setSaveInfo(it) }
            builder.build()
        }.getOrNull()
    }

    private fun buildUnlockPendingIntent(parsed: ParsedFields, startInSearch: Boolean): PendingIntent {
        val intent = Intent(applicationContext, AutofillUnlockActivity::class.java).apply {
            putExtra(AutofillUnlockActivity.EXTRA_PARSED_FIELDS, parsed)
            // Distinguishes the trailing "Search 1Key" chip from the
            // locked-vault chip — the activity reads this from its initial
            // Intent and lands directly in search mode when true.
            putExtra(AutofillUnlockActivity.EXTRA_START_IN_SEARCH, startInSearch)
        }
        // FLAG_MUTABLE: required so the system can append
        // AutofillManager.EXTRA_AUTHENTICATION_RESULT before delivering.
        // FLAG_UPDATE_CURRENT (not CANCEL_CURRENT): updates extras if a
        // matching request-code is reused; never silently invalidates a
        // sibling chip that's still pending.
        return PendingIntent.getActivity(
            applicationContext,
            pendingIntentCounter.incrementAndGet(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Run synchronously: the save flow is tiny (parse + extract values).
        // We want callback.onSuccess() to fire fast so the framework's save
        // bottom sheet dismisses promptly.
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: run {
                callback.onSuccess(); return
            }
            val packageName = structure.activityComponent?.packageName ?: run {
                callback.onSuccess(); return
            }
            if (entry.blocklist().isBlocked(packageName)) {
                callback.onSuccess(); return
            }
            val nodes = entry.structureWalker().walk(structure)
            val parsed = entry.fieldParser().parse(nodes, packageName)
            if (parsed == null || !parsed.isFillable()) {
                callback.onSuccess(); return
            }

            val username = readField(structure, parsed.username?.autofillId)
                ?: readField(structure, parsed.email?.autofillId)
            val password = readField(structure, parsed.password?.autofillId)
            if (password.isNullOrEmpty() && username.isNullOrEmpty()) {
                callback.onSuccess(); return
            }

            val token = AutofillCaptureBuffer.store(
                username = username,
                password = password,
                packageName = parsed.packageName,
                webDomain = parsed.webDomain,
            )

            val intent = Intent(applicationContext, AutofillSaveActivity::class.java).apply {
                putExtra(AutofillSaveActivity.EXTRA_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
            callback.onSuccess()
        } catch (_: Throwable) {
            // Even on error we must call back — otherwise the framework
            // shows a stuck "Saving..." indicator. Swallow silently to
            // preserve the no-logging policy.
            runCatching { callback.onSuccess() }
        }
    }

    /**
     * Reads a string value out of the most-recent FillContext for the given
     * AutofillId. Used at save time — the OS scrubs password text from
     * `AssistStructure` at fill time but populates it at save time for
     * fields declared in `SaveInfo`. Recursive — keep it iterative on the
     * same pattern as the walker.
     */
    private fun readField(structure: AssistStructure, id: android.view.autofill.AutofillId?): String? {
        if (id == null) return null
        val queue = ArrayDeque<AssistStructure.ViewNode>()
        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { queue.addLast(it) }
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.autofillId == id) {
                return node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
            }
            val n = node.childCount
            for (i in 0 until n) node.getChildAt(i)?.let { queue.addLast(it) }
        }
        return null
    }

    @Suppress("unused")
    private fun runBlockingForCheck(block: suspend () -> Boolean): Boolean =
        // Only used as a defensive escape hatch in places where a suspend
        // function must be evaluated from a non-suspend OS callback. Not
        // currently invoked — kept here so future maintainers see the pattern.
        runBlocking { block() }

    private companion object {
        // Kept as a placeholder anchor for future per-request logging if the
        // no-logging policy is ever relaxed under a debug flavour.
        @Suppress("unused")
        const val UNUSED_LOG_TAG: String = "OneKeyAutofill"
    }
}

@Suppress("unused")
private fun Job.alive(): Boolean = isActive
