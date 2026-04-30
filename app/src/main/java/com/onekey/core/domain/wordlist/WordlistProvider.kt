package com.onekey.core.domain.wordlist

/**
 * Source of word lists used by the password generator. Domain-side abstraction so that
 * `GeneratePasswordUseCase` can stay free of `android.content.Context` — the loading is
 * an infrastructure concern (assets, in this app) handled in the data layer.
 */
interface WordlistProvider {
    /**
     * The English wordlist used for memorable passphrase generation. May load lazily on
     * first call but must always return the same list for the lifetime of the process.
     */
    fun memorableWordlist(): List<String>
}
