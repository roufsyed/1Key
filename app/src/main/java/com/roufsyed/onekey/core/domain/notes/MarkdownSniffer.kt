package com.roufsyed.onekey.core.domain.notes

/**
 * Decides whether imported notes contain markdown worth protecting from
 * rendering. The detail screen flips imported notes to plain-source mode on
 * first view so text written in another app is not reinterpreted; that only
 * matters when the text has markdown in it. Plain notes, email addresses,
 * phone numbers and URLs render identically either way, so they are left
 * alone and the user is not told anything.
 *
 * Deliberately conservative: every rule needs a construct that the renderer
 * would actually change, and emphasis needs delimiters on both sides with
 * nothing alphanumeric touching them from outside, so `my_name_here@mail.com`
 * or `a*b` never count.
 */
object MarkdownSniffer {

    private val lineStart = listOf(
        Regex("""^\s{0,3}#{1,6}\s+\S"""),           // # heading
        Regex("""^\s{0,3}[-*+]\s+\S"""),            // - bullet
        Regex("""^\s{0,3}\d{1,9}[.)]\s+\S"""),      // 1. numbered
        Regex("""^\s{0,3}>\s?\S"""),                // > quote
        Regex("""^\s{0,3}(```|~~~)"""),             // fenced code
        Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$"""), // --- rule
        Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$"""), // |---|---| table rule
    )

    private val inline = listOf(
        Regex("""(?<![\w*])\*\*(?=\S)(.+?)(?<=\S)\*\*(?![\w*])"""),   // **bold**
        Regex("""(?<![\w_])__(?=\S)(.+?)(?<=\S)__(?![\w_])"""),       // __bold__
        Regex("""(?<![\w*])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![\w*])"""), // *italic*
        Regex("""(?<![\w_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\w_])"""),   // _italic_
        Regex("""(?<!`)`[^`\n]+`(?!`)"""),                           // `code`
        Regex("""\[[^\]\n]+]\((?:https?://|mailto:)[^)\s]+\)"""),     // [text](url)
        Regex("""~~(?=\S)(.+?)(?<=\S)~~"""),                         // ~~strike~~
    )

    fun looksLikeMarkdown(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lineSequence()
        if (lines.any { line -> lineStart.any { it.containsMatchIn(line) } }) return true
        return inline.any { it.containsMatchIn(text) }
    }
}
