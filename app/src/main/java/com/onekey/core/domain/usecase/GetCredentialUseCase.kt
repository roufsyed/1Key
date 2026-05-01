package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCredentialUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    /** Active credentials only — returns null for soft-deleted items. */
    operator fun invoke(id: String): Flow<Credential?> = repository.observeCredential(id)

    /** Returns the credential whether it's active or in the recycle bin. */
    fun includingDeleted(id: String): Flow<Credential?> =
        repository.observeCredentialIncludingDeleted(id)
}
