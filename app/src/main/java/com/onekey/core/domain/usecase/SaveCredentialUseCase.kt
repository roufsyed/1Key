package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class SaveCredentialUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    suspend operator fun invoke(credential: Credential): AppResult<Unit> {
        if (credential.title.isBlank()) {
            return AppResult.Error(IllegalArgumentException("Title is required"))
        }
        // Password-required types accept either a password or a TOTP secret as the auth
        // material — the 2FA QR scan flow saves credentials with totpSecret and an empty
        // password, and that's a legitimate "TOTP-only" entry.
        val hasAuthMaterial = credential.password.isNotBlank() ||
            !credential.totpSecret.isNullOrBlank()
        if (credential.type.requiresPassword && !hasAuthMaterial) {
            return AppResult.Error(IllegalArgumentException("Password or TOTP secret is required for ${credential.type.displayName}"))
        }
        if (credential.customFields.size > CustomField.MAX_FIELDS) {
            return AppResult.Error(IllegalArgumentException("Max ${CustomField.MAX_FIELDS} custom fields allowed"))
        }
        return repository.saveCredential(credential)
    }
}
