package com.roufsyed.onekey.core.data.wordlist

import android.content.Context
import com.roufsyed.onekey.core.domain.wordlist.WordlistProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetWordlistProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : WordlistProvider {

    // lazy(SYNCHRONIZED) ensures the wordlist is loaded at most once across concurrent
    // generator invocations. Bundled wordlist is small enough that holding it in memory
    // for the process lifetime is fine.
    private val wordlist: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.assets.open(WORDLIST_PATH).bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    override fun memorableWordlist(): List<String> = wordlist

    private companion object {
        private const val WORDLIST_PATH = "wordlists/words_en.txt"
    }
}
