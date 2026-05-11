package com.onekey.feature.autofill.domain

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks an [AssistStructure] and flattens every node we care about into a list
 * of [RawNode]s. Iterative (uses an [ArrayDeque]) rather than recursive — deep
 * signup forms can have stacks 30+ levels deep and we'd rather not gamble on a
 * 4 KB worker-thread stack on low-end OEM JVMs.
 *
 * Cancellation is supported via the [cancelled] predicate, which is checked
 * once per node so an aborted fill request stops walking promptly.
 *
 * This class is intentionally the only Android-touching seam in the parser
 * pipeline. The pure [FieldParser] consumes the flattened list, which keeps
 * it fully unit-testable on the JVM.
 */
@Singleton
class StructureWalker @Inject constructor() {

    /**
     * Returns every focusable input-shaped node under [structure]. Returns
     * `emptyList()` when [cancelled] returns `true` during the walk.
     *
     * Nodes with `IMPORTANT_FOR_AUTOFILL_NO` or `..._NO_EXCLUDE_DESCENDANTS`
     * are dropped, including their subtree in the latter case. This is a
     * defence-in-depth check — the OS pre-filters these as well, but custom
     * AssistStructure backends in some browsers historically have not.
     */
    fun walk(structure: AssistStructure, cancelled: () -> Boolean = { false }): List<RawNode> {
        val out = ArrayList<RawNode>(32)
        val queue = ArrayDeque<Pair<AssistStructure.ViewNode, String?>>()
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode ?: continue
            queue.addLast(root to null)
        }
        while (queue.isNotEmpty()) {
            if (cancelled()) return emptyList()
            val (node, inheritedWebDomain) = queue.removeFirst()
            val effectiveWebDomain = node.webDomain ?: inheritedWebDomain
            // `ViewNode.getImportantForAutofill` arrived in API 28; on 26-27 we
            // treat every node as "yes" (the framework already filters by its
            // own pre-API-28 heuristics before we see the structure).
            val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.importantForAutofill
            } else {
                View.IMPORTANT_FOR_AUTOFILL_AUTO
            }
            // NO_EXCLUDE_DESCENDANTS means skip self AND children entirely.
            if (importance == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) continue
            // NO means skip self but visit children.
            if (importance != View.IMPORTANT_FOR_AUTOFILL_NO) {
                val autofillId = node.autofillId
                if (autofillId != null) {
                    out += RawNode(
                        autofillId = autofillId,
                        autofillHints = node.autofillHints?.toList().orEmpty(),
                        idEntry = node.idEntry,
                        hint = node.hint,
                        inputType = node.inputType,
                        // `htmlInfo.attributes` is `List<android.util.Pair>`,
                        // not Kotlin's Pair — explicit conversion required.
                        htmlAttributes = node.htmlInfo?.attributes?.mapNotNull { p ->
                            val first = p.first ?: return@mapNotNull null
                            val second = p.second ?: return@mapNotNull null
                            first to second
                        }.orEmpty(),
                        webDomain = effectiveWebDomain,
                        importantForAutofill = importance,
                        className = node.className,
                    )
                }
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChildAt(i) ?: continue
                queue.addLast(child to effectiveWebDomain)
            }
        }
        return out
    }
}
