package com.roufsyed.onekey.feature.autofill.domain

import android.app.PendingIntent
import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.os.Build
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.roufsyed.onekey.R
import com.roufsyed.onekey.core.domain.model.Credential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructs [Dataset]s for the FillResponse. Three shapes:
 *
 *  - **Locked chip** ([buildLockedDataset]) - single placeholder dataset that
 *    routes to the unlock activity via the supplied [PendingIntent]. The
 *    framework calls `setAuthentication` semantics - when the user taps the
 *    chip, the OS launches the intent, the activity returns a replacement
 *    Dataset with real values via `AutofillManager.EXTRA_AUTHENTICATION_RESULT`,
 *    and the OS uses that to fill the fields. The placeholder values bound
 *    here are never written - they exist only so the chip is offered.
 *
 *  - **Credential dataset** ([buildCredentialDataset]) - populated with real
 *    [Credential] values, used directly by the framework when the vault is
 *    already unlocked.
 *
 *  - **Search chip** ([buildSearchDataset]) - trailing entry shown when the
 *    vault is unlocked but no matches were found. Behaves like the locked
 *    chip: tap routes through the unlock activity (which detects the
 *    already-unlocked state and goes straight to the picker).
 *
 * RemoteViews carry content descriptions so TalkBack reads the chip purpose,
 * not just the visible label.
 */
@Singleton
class DatasetBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun buildLockedDataset(parsed: ParsedFields, pendingIntent: PendingIntent): Dataset =
        buildAuthDataset(parsed, pendingIntent, label = LABEL_LOCKED_CHIP)

    fun buildSearchDataset(parsed: ParsedFields, pendingIntent: PendingIntent): Dataset =
        buildAuthDataset(parsed, pendingIntent, label = LABEL_SEARCH_CHIP)

    /**
     * Returns a Dataset with real [AutofillValue]s bound to the parsed fields.
     * Caller is responsible for ensuring [credential] decryption succeeded
     * before calling.
     */
    fun buildCredentialDataset(parsed: ParsedFields, credential: Credential): Dataset {
        val presentation = credentialRemoteViews(credential)
        val builder = Dataset.Builder()
        bindAll(builder, parsed, presentation) { type ->
            when (type) {
                AutofillField.Type.USERNAME -> credential.username
                AutofillField.Type.EMAIL -> credential.username
                AutofillField.Type.PASSWORD -> credential.password
            }
        }
        return builder.build()
    }

    private fun buildAuthDataset(
        parsed: ParsedFields,
        pendingIntent: PendingIntent,
        label: String,
    ): Dataset {
        val presentation = lockedChipRemoteViews(label)
        val builder = Dataset.Builder()
        // The placeholder values are never persisted - they exist only so each
        // field gets the chip offered to it. The framework replaces them with
        // values from the dataset returned via EXTRA_AUTHENTICATION_RESULT.
        bindAll(builder, parsed, presentation) { "" }
        builder.setAuthentication(pendingIntent.intentSender)
        return builder.build()
    }

    private inline fun bindAll(
        builder: Dataset.Builder,
        parsed: ParsedFields,
        presentation: RemoteViews,
        valueFor: (AutofillField.Type) -> String,
    ) {
        listOfNotNull(parsed.username, parsed.password, parsed.email).forEach { field ->
            bindOne(builder, field.autofillId, AutofillValue.forText(valueFor(field.type)), presentation)
        }
    }

    private fun bindOne(
        builder: Dataset.Builder,
        id: AutofillId,
        value: AutofillValue,
        presentation: RemoteViews,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: prefer `Presentations` to silence the deprecated overload.
            val presentations = Presentations.Builder().setMenuPresentation(presentation).build()
            builder.setField(id, Field.Builder().setValue(value).setPresentations(presentations).build())
        } else {
            @Suppress("DEPRECATION")
            builder.setValue(id, value, presentation)
        }
    }

    private fun lockedChipRemoteViews(label: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_chip_locked).apply {
            setTextViewText(R.id.autofill_chip_label, label)
            setContentDescription(R.id.autofill_chip_label, label)
        }

    private fun credentialRemoteViews(credential: Credential): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_chip_credential).apply {
            val title = credential.title.ifBlank { LABEL_FALLBACK_TITLE }
            val subtitle = credential.username.ifBlank { LABEL_FALLBACK_USERNAME }
            setTextViewText(R.id.autofill_chip_title, title)
            setTextViewText(R.id.autofill_chip_subtitle, subtitle)
            setContentDescription(R.id.autofill_chip_title, "$title, $subtitle")
        }

    private companion object {
        const val LABEL_LOCKED_CHIP = "Unlock 1Key to fill"
        const val LABEL_SEARCH_CHIP = "Search 1Key"
        const val LABEL_FALLBACK_TITLE = "1Key item"
        const val LABEL_FALLBACK_USERNAME = "(no username)"
    }
}
