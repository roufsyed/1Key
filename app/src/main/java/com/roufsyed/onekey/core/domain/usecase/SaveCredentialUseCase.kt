package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CustomField
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class SaveCredentialUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    suspend operator fun invoke(credential: Credential): AppResult<Unit> {
        if (credential.title.isBlank()) {
            return AppResult.Error(IllegalArgumentException("Title is required"))
        }
        if (credential.customFields.size > CustomField.MAX_FIELDS) {
            return AppResult.Error(IllegalArgumentException("Max ${CustomField.MAX_FIELDS} custom fields allowed"))
        }
        return repository.saveCredential(credential)
    }
}
