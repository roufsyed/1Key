package com.roufsyed.onekey.core.domain.model

/**
 * Colour theme of the flip card rendered for [CredentialType.CREDIT_CARD] and
 * [CredentialType.BANK_ACCOUNT] credentials. Chosen in the editor and stored per
 * credential as the enum NAME in the plaintext `card_theme` column (DB v16),
 * the same convention [CredentialType] uses for `type`.
 *
 * Why plaintext: the theme is a UI preference, not a secret. The row already
 * exposes `type`, so an attacker holding the database file learns nothing new
 * about which rows are cards; the colour choice itself is the whole residual
 * leak (docs/internal/CARD_FLIP_PLAN.md, section 4.4).
 *
 * Contract:
 *  - Entries are append-only and are never renamed or removed. The name is the
 *    persisted identity and the JSON export value; renaming one would silently
 *    collapse every stored row to [GRAPHITE]. If a rename is ever unavoidable,
 *    add a legacy-name alias inside [fromNameOrDefault] and extend the golden
 *    name test (CardThemeTest).
 *  - Colours are NOT part of this enum. The ARGB values live in the
 *    presentation layer so the palette can be retuned without a migration.
 *  - [GRAPHITE] is the neutral default: pre-v16 rows, imports without a
 *    `card_theme` key, and unknown names from newer app versions all resolve
 *    to it. Exports write the key only for non-default themes.
 *  - Names are neutral colour words, never bank or card-network names.
 */
enum class CardTheme(val displayName: String) {
    CHARCOAL("Charcoal"),
    MIDNIGHT("Midnight"),
    MAROON("Maroon"),
    GRAPHITE("Graphite"),
    PINE("Pine"),
    GRAPE("Grape"),
    SLATE("Slate"),
    TEAL("Teal"),
    RUST("Rust"),
    SOFT_BLUE("Soft Blue"),
    CORAL("Coral"),
    MUSTARD("Mustard"),
    POWDER("Powder"),
    PEACH("Peach"),
    LEMON("Lemon"),
    IVORY("Ivory"),
    ;

    companion object {
        /** The theme used when none was chosen or a stored name is unknown. */
        val DEFAULT: CardTheme = GRAPHITE

        /**
         * Resolves a persisted or imported name. Case-sensitive like
         * [CredentialType.fromNameOrDefault]; anything unknown (including a
         * value written by a newer app version) falls back to [DEFAULT] instead
         * of throwing, so a crafted or future backup can never crash the UI.
         */
        fun fromNameOrDefault(name: String?): CardTheme =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
