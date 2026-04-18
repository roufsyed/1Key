package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCredentialUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    operator fun invoke(id: String): Flow<Credential?> = repository.observeCredential(id)
}
