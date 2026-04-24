package com.onekey.core.domain.usecase

import androidx.paging.PagingData
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetPagedCredentialsUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    operator fun invoke(
        query: String = "",
        tag: String = "",
        sortOrder: CredentialSortOrder = CredentialSortOrder.NEWEST_FIRST,
    ): Flow<PagingData<Credential>> = repository.getPagedCredentials(query, tag, sortOrder)
}
