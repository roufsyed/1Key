package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.RecycleBinRetention
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Permanently removes recycle-bin items deleted more than the user's configured
 * retention window ago, along with their history. Run on every vault unlock.
 *
 * If the user has chosen [RecycleBinRetention.NEVER] this is a no-op - items in
 * the bin then stay until the user empties them manually.
 */
class PurgeExpiredRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val appPrefs: AppPreferencesRepository,
) {
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): AppResult<Int> {
        val retentionMs = appPrefs.getRecycleBinRetention().first().millis
            ?: return AppResult.Success(0)
        val cutoff = now - retentionMs
        val binResult = credentialRepository.getAllInRecycleBin()
        if (binResult is AppResult.Error) return binResult
        val expired = (binResult as AppResult.Success).data.filter { (it.deletedAt ?: 0L) < cutoff }
        expired.forEach { historyRepository.deleteForCredential(it.id) }
        return credentialRepository.purgeRecycleBinOlderThan(cutoff)
    }
}
