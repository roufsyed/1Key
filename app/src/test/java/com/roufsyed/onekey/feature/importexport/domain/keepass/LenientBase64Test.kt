package com.roufsyed.onekey.feature.importexport.domain.keepass

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class LenientBase64Test {

    private val bytes = ByteArray(32) { (it * 7 + 250).toByte() }   // yields both '+'/'/' and '-'/'_' forms

    @Test fun accepts_base64url_with_or_without_padding() {
        assertArrayEquals(bytes, LenientBase64.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
        assertArrayEquals(bytes, LenientBase64.decode(Base64.getUrlEncoder().encodeToString(bytes)))
    }

    @Test fun accepts_standard_base64() {
        assertArrayEquals(bytes, LenientBase64.decode(Base64.getEncoder().encodeToString(bytes)))
        assertArrayEquals(bytes, LenientBase64.decode(Base64.getEncoder().withoutPadding().encodeToString(bytes)))
    }

    @Test fun refuses_what_is_neither() {
        assertNull(LenientBase64.decode(null))
        assertNull(LenientBase64.decode(""))
        assertNull(LenientBase64.decode("   "))
        assertNull(LenientBase64.decode("not base64 at all!"))
    }
}
