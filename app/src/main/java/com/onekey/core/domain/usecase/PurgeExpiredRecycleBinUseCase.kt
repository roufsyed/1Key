package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Permanently removes recycle-bin items deleted more than [RETENTION_DAYS] days ago,
 * along with their history. Run on every vault unlock — soft-deleted items have a
 * 30-day undo window, then they're gone.
 */
class PurgeExpiredRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): AppResult<Int> {
        val cutoff = now - RETENTION_MS
        val binResult = credentialRepository.getAllInRecycleBin()
        if (binResult is AppResult.Error) return binResult
        val expired = (binResult as AppResult.Success).data.filter { (it.deletedAt ?: 0L) < cutoff }
        expired.forEach { historyRepository.deleteForCredential(it.id) }
        return credentialRepository.purgeRecycleBinOlderThan(cutoff)
    }

    companion object {
        const val RETENTION_DAYS = 30
        const val RETENTION_MS: Long = RETENTION_DAYS * 24L * 60L * 60L * 1000L
    }
}
