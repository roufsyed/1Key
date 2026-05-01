package com.onekey.feature.twofa.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each Invalid branch of the validator gets one explicit test so a future change
 * that collapses two errors into one can't slide through silently — error
 * messaging in the manual-entry sheet maps 1:1 onto these subclasses.
 */
class OtpSecretValidatorTest {

    private val gen = OtpGenerator()

    @Test fun empty_is_Empty() {
        val r = OtpSecretValidator.validate("", gen)
        assertEquals(OtpSecretValidator.Result.Invalid.Empty, r)
    }

    @Test fun whitespace_only_is_Empty() {
        val r = OtpSecretValidator.validate("   \t  ", gen)
        assertEquals(OtpSecretValidator.Result.Invalid.Empty, r)
    }

    @Test fun padding_only_is_Empty() {
        val r = OtpSecretValidator.validate("====", gen)
        assertEquals(OtpSecretValidator.Result.Invalid.Empty, r)
    }

    @Test fun lowercase_letters_normalise_and_validate() {
        val r = OtpSecretValidator.validate("jbswy3dpehpk3pxp", gen)
        assertTrue("Expected Valid, got $r", r is OtpSecretValidator.Result.Valid)
        assertEquals("JBSWY3DPEHPK3PXP", (r as OtpSecretValidator.Result.Valid).cleaned)
    }

    @Test fun spaces_are_stripped() {
        val r = OtpSecretValidator.validate("JBSWY 3DPEH PK3PX P", gen)
        assertTrue(r is OtpSecretValidator.Result.Valid)
        assertEquals("JBSWY3DPEHPK3PXP", (r as OtpSecretValidator.Result.Valid).cleaned)
    }

    @Test fun bad_character_is_BadCharacters() {
        // '0' isn't part of the RFC 4648 base32 alphabet — common typo for 'O'.
        val r = OtpSecretValidator.validate("JBSWY30PEHPK3PXP", gen)
        assertEquals(OtpSecretValidator.Result.Invalid.BadCharacters, r)
    }

    @Test fun too_short_is_TooShort() {
        // Only 8 chars — under the 16-char (80-bit) RFC minimum.
        val r = OtpSecretValidator.validate("JBSWY3DP", gen)
        assertEquals(OtpSecretValidator.Result.Invalid.TooShort, r)
    }

    @Test fun trailing_padding_doesnt_count_against_length() {
        // 16 chars cleaned (after `=` strip), still valid.
        val r = OtpSecretValidator.validate("JBSWY3DPEHPK3PXP====", gen)
        assertTrue(r is OtpSecretValidator.Result.Valid)
    }
}
