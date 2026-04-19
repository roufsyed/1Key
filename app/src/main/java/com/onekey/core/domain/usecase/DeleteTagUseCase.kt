package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import javax.inject.Inject

class DeleteTagUseCase @Inject constructor(
    private val tagRepository: TagRepository,
    private val credentialRepository: CredentialRepository,
) {
    suspend operator fun invoke(name: String): AppResult<Unit> = runCatchingResult {
        val credentials = when (val r = credentialRepository.getAllCredentials()) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw r.exception
        }

        // Strip the deleted tag from every credential that carries it.
        credentials
            .filter { name in it.tags }
            .forEach { credential ->
                val result = credentialRepository.saveCredential(
                    credential.copy(tags = credential.tags - name)
                )
                if (result is AppResult.Error) throw result.exception
            }

        // Only delete the tag entry once all credentials are clean.
        when (val r = tagRepository.deleteTag(name)) {
            is AppResult.Error -> throw r.exception
            else -> Unit
        }
    }
}
