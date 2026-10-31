package com.roufsyed.onekey.feature.passkey.service

import androidx.credentials.PublicKeyCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the provider metadata Android reads before it ever binds to the
 * service. `CredentialManagerService` matches the request type against the
 * capabilities parsed from `res/xml/passkey_provider.xml` and silently skips
 * the provider ("Service does not have the required capabilities") when none
 * match. Two things have to be exactly right, and both were wrong on the first
 * device build:
 *
 *  - the value is the Jetpack string `androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL`;
 *    only the password type keeps the `android.` prefix;
 *  - the attribute is a bare `name` with no namespace, because the framework's
 *    `CredentialProviderInfoFactory` reads it with `getAttributeValue(null, "name")`.
 *    `android:name` is invisible to it. `android:settingsSubtitle` on the root
 *    element, by contrast, is read through a styleable and needs the prefix.
 */
class PasskeyProviderMetadataTest {

    private val xml = firstExisting("src/main/res/xml/passkey_provider.xml", "app/src/main/res/xml/passkey_provider.xml")
    private val manifest = firstExisting("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

    private val document by lazy { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml) }

    @Test fun capability_is_the_jetpack_passkey_type_in_an_unnamespaced_name_attribute() {
        val capabilities = document.getElementsByTagName("capability")
        assertEquals("passkeys only; passwords stay on the autofill service", 1, capabilities.length)
        val attributes = capabilities.item(0).attributes
        assertNull("android:name is not what the framework parser reads", attributes.getNamedItemNS(ANDROID_NS, "name"))
        assertEquals(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, attributes.getNamedItem("name")?.nodeValue)
        // The literal the framework compares against; it comes from the calling app's Jetpack library, not from android.jar.
        assertEquals("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL)
    }

    @Test fun structure_matches_what_the_framework_walks() {
        val root = document.documentElement
        assertEquals("credential-provider", root.tagName)
        assertNotNull("subtitle is read through a styleable, so it must be android:settingsSubtitle", root.attributes.getNamedItemNS(ANDROID_NS, "settingsSubtitle"))
        val capabilitiesParent = document.getElementsByTagName("capability").item(0).parentNode
        assertEquals("capabilities", capabilitiesParent.nodeName)
        assertEquals(root, capabilitiesParent.parentNode)
    }

    @Test fun manifest_points_the_provider_service_at_this_metadata() {
        val service = manifest.readText().substringAfter("OneKeyCredentialProviderService").substringBefore("</service>")
        assertTrue(service.contains("android:permission=\"android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE\""))
        assertTrue(service.contains("android.service.credentials.CredentialProviderService"))
        assertTrue(service.contains("android:name=\"android.credentials.provider\""))
        assertTrue(service.contains("android:resource=\"@xml/passkey_provider\""))
    }

    private fun firstExisting(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
