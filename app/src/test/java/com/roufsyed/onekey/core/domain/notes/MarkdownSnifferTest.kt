package com.roufsyed.onekey.core.domain.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sniffer gates the imported-notes hint. False negatives mean a markdown
 * note gets rendered unasked; false positives mean a pointless toast on an
 * email address. Both sides are pinned.
 */
class MarkdownSnifferTest {

    private fun yes(text: String) = assertTrue("expected markdown: $text", MarkdownSniffer.looksLikeMarkdown(text))
    private fun no(text: String) = assertFalse("expected plain: $text", MarkdownSniffer.looksLikeMarkdown(text))

    @Test fun plain_notes_are_not_markdown() {
        no("")
        no("   \n ")
        no("afrid704@gmail.com")
        no("my_user_name@example.com and first_last@mail.co.uk")
        no("Customer no 1234-5678, call +91 98765 43210")
        no("https://www.example.com/path_with_underscores/and*stars?q=a_b")
        no("Security question: mother's maiden name\nAnswer: Smith")
        no("Price is 5*3 = 15 and 2 * 4")
        no("Snake_case_ids like user_id_42 are fine")
        no("Account #12345 opened 2024")
        no("Use the -f flag, then -v")
    }

    @Test fun block_constructs_are_markdown() {
        yes("# Recovery codes\n1234 5678")
        yes("Steps:\n- open settings\n- tap security")
        yes("1. first\n2. second")
        yes("> quoted advice")
        yes("```\nssh-rsa AAAA\n```")
        yes("above\n---\nbelow")
        yes("| a | b |\n|---|---|\n| 1 | 2 |")
    }

    @Test fun inline_constructs_are_markdown() {
        yes("this is **important** for later")
        yes("this is __important__ for later")
        yes("call *before* noon")
        yes("call _before_ noon")
        yes("run `passwd` then log out")
        yes("see [the docs](https://example.com/docs) first")
        yes("old pin ~~1234~~ now 5678")
    }

    @Test fun emphasis_needs_clean_boundaries() {
        no("file_name_here")
        no("a*b*c")
        no("**not closed")
        yes("word **bold** word")
    }
}
