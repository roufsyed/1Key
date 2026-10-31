package com.roufsyed.onekey.feature.passkey.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.credentials.provider.Action
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BiometricPromptData
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.roufsyed.onekey.R
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCreateActivity
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyGetActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the entries Android's account selector shows for 1Key, and the
 * PendingIntents behind them. Request codes come from a process-wide counter
 * and every intent is `FLAG_MUTABLE | FLAG_UPDATE_CURRENT`, as the provider
 * contract requires (the system appends the request to the intent) and as the
 * autofill service already does. Intents carry only row ids and modes, never
 * key material.
 */
@Singleton
class PasskeyEntries @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val requestCodes = AtomicInteger(0x5000)

    /** Locked vault: one action that opens the unlock flow and then re-lists entries. */
    fun unlockAction(): AuthenticationAction = AuthenticationAction(
        "Unlock 1Key",
        pendingIntent(Intent(context, PasskeyGetActivity::class.java).putExtra(PasskeyGetActivity.EXTRA_MODE, PasskeyGetActivity.MODE_UNLOCK_AND_LIST)),
    )

    /** Caller refused by policy: an informational action instead of silence. */
    fun explainAction(reason: CallerKind.Reason): Action = Action(
        title = "Why isn't 1Key offering a passkey?",
        pendingIntent = pendingIntent(
            Intent(context, PasskeyGetActivity::class.java)
                .putExtra(PasskeyGetActivity.EXTRA_MODE, PasskeyGetActivity.MODE_EXPLAIN)
                .putExtra(PasskeyGetActivity.EXTRA_REFUSAL, reason.name),
        ),
        subtitle = "1Key can't verify this app",
    )

    /**
     * [unlockGrant] is set when the unlock-and-list activity builds the entries:
     * the user has just unlocked inside this ceremony, so the entry carries that
     * proof for the sign activity and skips the system's own biometric prompt.
     * Entries the service builds while the vault is already unlocked carry no
     * grant and keep Android 15's single-tap prompt, which then counts as the
     * user verification.
     */
    fun credentialEntry(
        option: BeginGetPublicKeyCredentialOption,
        passkey: PasskeySummary,
        unlockGrant: String? = null,
    ): PublicKeyCredentialEntry {
        val intent = Intent(context, PasskeyGetActivity::class.java)
            .putExtra(PasskeyGetActivity.EXTRA_MODE, PasskeyGetActivity.MODE_SIGN)
            .putExtra(PasskeyGetActivity.EXTRA_PASSKEY_ID, passkey.id)
        unlockGrant?.let { intent.putExtra(PasskeyGetActivity.EXTRA_UNLOCK_GRANT, it) }
        val builder = PublicKeyCredentialEntry.Builder(context, passkey.userName.ifBlank { passkey.userDisplayName }, pendingIntent(intent), option)
            .setDisplayName(passkey.userDisplayName.ifBlank { passkey.rpName })
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        passkey.lastUsedAt?.let { builder.setLastUsedTime(Instant.ofEpochMilli(it)) }
        if (unlockGrant == null && Build.VERSION.SDK_INT >= 35) {
            // Android 15 single tap: the system shows the biometric prompt itself and
            // reports the result through ProviderGetCredentialRequest.biometricPromptResult.
            builder.setBiometricPromptData(
                BiometricPromptData.Builder()
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
            )
        }
        return builder.build()
    }

    fun createEntry(rpId: String, existingForRp: Int): CreateEntry {
        val intent = Intent(context, PasskeyCreateActivity::class.java)
        val builder = CreateEntry.Builder("1Key vault", pendingIntent(intent))
            .setDescription(
                if (existingForRp > 0) "Save a passkey for $rpId. You already have $existingForRp here."
                else "Save a passkey for $rpId in your encrypted vault.",
            )
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setPublicKeyCredentialCount(existingForRp)
        return builder.build()
    }

    private fun pendingIntent(intent: Intent): PendingIntent = PendingIntent.getActivity(
        context,
        requestCodes.incrementAndGet(),
        intent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
