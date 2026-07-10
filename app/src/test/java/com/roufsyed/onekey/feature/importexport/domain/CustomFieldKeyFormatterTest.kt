package com.roufsyed.onekey.feature.importexport.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomFieldKeyFormatterTest {

    @Test fun camelCase_splits_on_uppercase_boundary() {
        assertEquals("Created At", CustomFieldKeyFormatter.prettify("createdAt"))
        assertEquals("Form Action Origin", CustomFieldKeyFormatter.prettify("formActionOrigin"))
        assertEquals("Time Last Used", CustomFieldKeyFormatter.prettify("timeLastUsed"))
    }

    @Test fun pascalCase_splits_on_uppercase_boundary() {
        assertEquals("Created At", CustomFieldKeyFormatter.prettify("CreatedAt"))
    }

    @Test fun snake_case_splits_on_underscore() {
        assertEquals("Time Password Changed", CustomFieldKeyFormatter.prettify("time_password_changed"))
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("phone_number"))
    }

    @Test fun kebab_case_splits_on_hyphen() {
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("phone-number"))
    }

    @Test fun mixed_separators_collapse_to_single_space() {
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("phone__number"))
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("_phone_number_"))
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("phone--number"))
    }

    @Test fun already_spaced_input_is_preserved_idempotently() {
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("Phone Number"))
        assertEquals("Phone Number", CustomFieldKeyFormatter.prettify("phone number"))
    }

    @Test fun double_pass_yields_same_output() {
        val once = CustomFieldKeyFormatter.prettify("createdAt")
        val twice = CustomFieldKeyFormatter.prettify(once)
        assertEquals(once, twice)
    }

    @Test fun acronym_at_start_followed_by_word() {
        assertEquals("HTTP Realm", CustomFieldKeyFormatter.prettify("HTTPRealm"))
        assertEquals("URL Pattern", CustomFieldKeyFormatter.prettify("URLPattern"))
    }

    @Test fun acronym_in_middle_of_camelCase() {
        assertEquals("My URL String", CustomFieldKeyFormatter.prettify("myURLString"))
    }

    @Test fun standalone_acronym_stays_uppercase() {
        assertEquals("ID", CustomFieldKeyFormatter.prettify("id"))
        assertEquals("ID", CustomFieldKeyFormatter.prettify("ID"))
        assertEquals("ID", CustomFieldKeyFormatter.prettify("Id"))
        assertEquals("URL", CustomFieldKeyFormatter.prettify("url"))
        assertEquals("OTP", CustomFieldKeyFormatter.prettify("otp"))
        assertEquals("2FA", CustomFieldKeyFormatter.prettify("2fa"))
    }

    @Test fun acronym_word_camelCase() {
        assertEquals("OTP Secret", CustomFieldKeyFormatter.prettify("otpSecret"))
        assertEquals("2FA Key", CustomFieldKeyFormatter.prettify("2faKey"))
    }

    @Test fun blank_input_returns_empty() {
        assertEquals("", CustomFieldKeyFormatter.prettify(""))
        assertEquals("", CustomFieldKeyFormatter.prettify("   "))
        assertEquals("", CustomFieldKeyFormatter.prettify("___"))
        assertEquals("", CustomFieldKeyFormatter.prettify("---"))
    }

    @Test fun trailing_digits_attach_to_word() {
        assertEquals("Address1", CustomFieldKeyFormatter.prettify("address1"))
    }

    @Test fun firefox_keys_match_expected_labels() {
        // Sanity-check the exact strings the Firefox export emits (matching the
        // user's reported screens) so we don't regress them quietly.
        assertEquals("Time Created", CustomFieldKeyFormatter.prettify("timeCreated"))
        assertEquals("Time Password Changed", CustomFieldKeyFormatter.prettify("timePasswordChanged"))
        assertEquals("Time Last Used", CustomFieldKeyFormatter.prettify("timeLastUsed"))
        assertEquals("Form Action Origin", CustomFieldKeyFormatter.prettify("formActionOrigin"))
        assertEquals("HTTP Realm", CustomFieldKeyFormatter.prettify("httpRealm"))
    }
}
