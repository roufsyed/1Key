package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class SeedDataUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    suspend operator fun invoke(): AppResult<Int> {
        val now = System.currentTimeMillis()
        var count = 0
        for (credential in sampleCredentials(now)) {
            val result = credentialRepository.saveCredential(credential)
            if (result is AppResult.Success) count++
        }
        return AppResult.Success(count)
    }

    private fun sampleCredentials(now: Long): List<Credential> = listOf(
        Credential(
            id = "",
            title = "Google Account",
            username = "john.doe@gmail.com",
            password = "Str0ng!Passw0rd#2024",
            url = "https://accounts.google.com",
            notes = "Primary Google account used for Gmail and Drive.",
            totpSecret = null,
            tags = listOf("Login"),
            customFields = emptyList(),
            isFavorite = true,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "GitHub",
            username = "johndoe",
            password = "ghp_ExampleTokenValue123",
            url = "https://github.com",
            notes = "Work GitHub account. 2FA enabled.",
            totpSecret = null,
            tags = listOf("Login"),
            customFields = listOf(
                CustomField(key = "Access Token", value = "ghp_ExampleTokenValue123456", isSensitive = true),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Home WiFi",
            username = "",
            password = "MyWiFiPassword@Home2024",
            url = "",
            notes = "SSID: MyHomeNetwork_5G. Router admin at 192.168.1.1.",
            totpSecret = null,
            tags = listOf("Secure Note"),
            customFields = listOf(
                CustomField(key = "SSID", value = "MyHomeNetwork_5G", isSensitive = false),
                CustomField(key = "Router IP", value = "192.168.1.1", isSensitive = false),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Visa Platinum",
            username = "John Doe",
            password = "4532 1234 5678 9012",
            url = "",
            notes = "Expires 12/2027. Billing zip: 10001.",
            totpSecret = null,
            tags = listOf("Credit Card"),
            customFields = listOf(
                CustomField(key = "CVV", value = "123", isSensitive = true),
                CustomField(key = "Expiry", value = "12/2027", isSensitive = false),
                CustomField(key = "PIN", value = "1234", isSensitive = true),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Netflix",
            username = "john.doe@gmail.com",
            password = "Netf1ix\$ecure!Pass",
            url = "https://netflix.com",
            notes = "Premium plan, shared with family.",
            totpSecret = null,
            tags = listOf("Password"),
            customFields = emptyList(),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Chase Checking",
            username = "john.doe",
            password = "BankingP@ss2024!",
            url = "https://chase.com",
            notes = "Primary checking account.",
            totpSecret = null,
            tags = listOf("Bank Account"),
            customFields = listOf(
                CustomField(key = "Account Number", value = "000123456789", isSensitive = true),
                CustomField(key = "Routing Number", value = "021000021", isSensitive = true),
            ),
            isFavorite = true,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Production PostgreSQL",
            username = "prod_admin",
            password = "Pg\$ecureP@ssw0rd!",
            url = "postgres://db.example.com:5432/prod_db",
            notes = "Production DB. Connect via VPN only.",
            totpSecret = null,
            tags = listOf("Database"),
            customFields = listOf(
                CustomField(key = "Database", value = "prod_db", isSensitive = false),
                CustomField(key = "Port", value = "5432", isSensitive = false),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Work Email",
            username = "john.doe@company.com",
            password = "W0rkEmail!Secure#",
            url = "https://mail.company.com",
            notes = "Corporate email. IMAP port 993, SMTP port 587.",
            totpSecret = null,
            tags = listOf("Email Account"),
            customFields = listOf(
                CustomField(key = "IMAP", value = "mail.company.com:993", isSensitive = false),
                CustomField(key = "SMTP", value = "smtp.company.com:587", isSensitive = false),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
        Credential(
            id = "",
            title = "Production VPS",
            username = "root",
            password = "Serv3r\$ecureP@ss!",
            url = "ssh://198.51.100.42:22",
            notes = "Ubuntu 22.04 LTS. 4 GB RAM. DigitalOcean.",
            totpSecret = null,
            tags = listOf("Server"),
            customFields = listOf(
                CustomField(key = "IP Address", value = "198.51.100.42", isSensitive = false),
                CustomField(key = "SSH Key", value = "~/.ssh/id_rsa_prod", isSensitive = false),
            ),
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
        ),
    )
}
