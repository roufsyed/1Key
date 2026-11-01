package com.roufsyed.onekey.core.domain.webauthn

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** `authenticatorSelection.userVerification` and `PublicKeyCredentialRequestOptions.userVerification`. */
enum class UserVerificationRequirement {
    REQUIRED, PREFERRED, DISCOURAGED;

    /** Whether the authenticator should perform a fresh user verification for this ceremony. */
    val wantsVerification: Boolean get() = this != DISCOURAGED

    companion object {
        fun parse(value: String?): UserVerificationRequirement = when (value?.lowercase()) {
            "required" -> REQUIRED
            "discouraged" -> DISCOURAGED
            else -> PREFERRED
        }
    }
}

class CredentialDescriptor(val id: ByteArray, val type: String) {
    fun matches(credentialId: ByteArray): Boolean = type == "public-key" && id.contentEquals(credentialId)
}

/** The parts of `PublicKeyCredentialCreationOptions` (WebAuthn Level 3 section 5.4) the provider acts on. */
class CreationOptions(
    /** `rp.id`; null when the relying party left it to the client, in which case the caller's origin host applies. */
    val rpId: String?,
    val rpName: String,
    val userId: ByteArray,
    val userName: String,
    val userDisplayName: String,
    /** The challenge exactly as sent (base64url), echoed back inside client data. */
    val challenge: String,
    val algorithms: List<Int>,
    val excludeCredentials: List<CredentialDescriptor>,
    val userVerification: UserVerificationRequirement,
    val residentKey: String?,
    val attestation: String,
    val wantsCredProps: Boolean,
) {
    /** The algorithm 1Key will use: the first of the site's list it supports, or null when it supports none. An empty list means the spec defaults. */
    val algorithm: PasskeyAlgorithm? get() = PasskeyAlgorithm.firstSupported(algorithms)
}

/** The parts of `PublicKeyCredentialRequestOptions` (section 5.5) the provider acts on. */
class RequestOptions(
    val rpId: String?,
    val challenge: String,
    val allowCredentials: List<CredentialDescriptor>,
    val userVerification: UserVerificationRequirement,
) {
    fun allows(credentialId: ByteArray): Boolean =
        allowCredentials.isEmpty() || allowCredentials.any { it.matches(credentialId) }
}

/**
 * JSON in and out of the provider. Requests arrive as the WebAuthn
 * `*OptionsJSON` dictionaries Credential Manager relays verbatim; responses
 * go back as `RegistrationResponseJSON` and `AuthenticationResponseJSON`
 * (Level 3 section 5.1). Gson is already a dependency and is pure Java, so this
 * stays JVM-testable with no Android JSON classes.
 */
object WebAuthnJson {
    const val TYPE_PUBLIC_KEY = "public-key"
    private const val ATTACHMENT_PLATFORM = "platform"

    @Throws(IllegalArgumentException::class)
    fun parseCreationOptions(json: String): CreationOptions {
        val root = parseObject(json)
        val rp = root.obj("rp") ?: throw IllegalArgumentException("rp missing")
        val user = root.obj("user") ?: throw IllegalArgumentException("user missing")
        val selection = root.obj("authenticatorSelection")
        val extensions = root.obj("extensions")
        return CreationOptions(
            rpId = rp.str("id")?.takeIf { it.isNotBlank() }?.lowercase(),
            rpName = rp.str("name").orEmpty(),
            userId = Base64Url.decode(user.str("id") ?: throw IllegalArgumentException("user.id missing")),
            userName = user.str("name").orEmpty(),
            userDisplayName = user.str("displayName").orEmpty(),
            challenge = root.str("challenge") ?: throw IllegalArgumentException("challenge missing"),
            algorithms = root.arr("pubKeyCredParams")?.mapNotNull { it.asObjectOrNull()?.get("alg")?.asIntOrNull() }.orEmpty(),
            excludeCredentials = descriptors(root.arr("excludeCredentials")),
            userVerification = UserVerificationRequirement.parse(selection?.str("userVerification")),
            residentKey = selection?.str("residentKey"),
            attestation = root.str("attestation") ?: "none",
            wantsCredProps = extensions?.get("credProps")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } ?: false,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun parseRequestOptions(json: String): RequestOptions {
        val root = parseObject(json)
        return RequestOptions(
            rpId = root.str("rpId")?.takeIf { it.isNotBlank() }?.lowercase(),
            challenge = root.str("challenge") ?: throw IllegalArgumentException("challenge missing"),
            allowCredentials = descriptors(root.arr("allowCredentials")),
            userVerification = UserVerificationRequirement.parse(root.str("userVerification")),
        )
    }

    /**
     * `RegistrationResponseJSON`. [clientDataJson] is null for privileged callers,
     * which supplied a client data hash and substitute their own client data; the
     * field is then an empty string, the placeholder Android's provider contract uses.
     */
    fun registrationResponse(
        credentialId: ByteArray,
        clientDataJson: ByteArray?,
        attestationObject: ByteArray,
        authenticatorData: ByteArray,
        publicKeySpki: ByteArray,
        publicKeyAlgorithm: Int,
        credProps: Boolean,
    ): String {
        val response = JsonObject().apply {
            addProperty("clientDataJSON", clientDataJson?.let(Base64Url::encode) ?: "")
            addProperty("attestationObject", Base64Url.encode(attestationObject))
            add("transports", JsonArray().apply { add("internal"); add("hybrid") })
            addProperty("publicKeyAlgorithm", publicKeyAlgorithm)
            addProperty("publicKey", Base64Url.encode(publicKeySpki))
            addProperty("authenticatorData", Base64Url.encode(authenticatorData))
        }
        val extensions = JsonObject().apply {
            if (credProps) add("credProps", JsonObject().apply { addProperty("rk", true) })
        }
        return envelope(credentialId, response, extensions).toString()
    }

    /** `AuthenticationResponseJSON`. Same [clientDataJson] rule as [registrationResponse]. */
    fun assertionResponse(
        credentialId: ByteArray,
        clientDataJson: ByteArray?,
        authenticatorData: ByteArray,
        signature: ByteArray,
        userHandle: ByteArray,
    ): String {
        val response = JsonObject().apply {
            addProperty("clientDataJSON", clientDataJson?.let(Base64Url::encode) ?: "")
            addProperty("authenticatorData", Base64Url.encode(authenticatorData))
            addProperty("signature", Base64Url.encode(signature))
            addProperty("userHandle", Base64Url.encode(userHandle))
        }
        return envelope(credentialId, response, JsonObject()).toString()
    }

    private fun envelope(credentialId: ByteArray, response: JsonObject, extensions: JsonObject): JsonObject {
        val id = Base64Url.encode(credentialId)
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("rawId", id)
            addProperty("type", TYPE_PUBLIC_KEY)
            addProperty("authenticatorAttachment", ATTACHMENT_PLATFORM)
            add("response", response)
            add("clientExtensionResults", extensions)
        }
    }

    // ── parsing helpers ─────────────────────────────────────────────────────

    private fun parseObject(json: String): JsonObject = try {
        JsonParser.parseString(json).asJsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("Malformed WebAuthn request JSON", e)
    }

    private fun descriptors(array: JsonArray?): List<CredentialDescriptor> =
        array?.mapNotNull { element ->
            val obj = element.asObjectOrNull() ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            runCatching { CredentialDescriptor(Base64Url.decode(id), obj.str("type") ?: TYPE_PUBLIC_KEY) }.getOrNull()
        }.orEmpty()

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asObjectOrNull()
    private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonElement.asIntOrNull(): Int? =
        if (isJsonPrimitive && asJsonPrimitive.isNumber) runCatching { asInt }.getOrNull() else null
}
