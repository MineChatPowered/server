package org.winlogon.minechat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TlsConfig(
    val enabled: Boolean = true,
    val keystore: String = "keystore.p12",
    @SerialName("keystore-password")
    val keystorePassword: String = "password"
)

@Serializable
data class MineChatConfig(
    val port: Int = 7632,
    @SerialName("expiry-code-minutes")
    val expiryCodeMinutes: Int = 5,
    val tls: TlsConfig = TlsConfig()
)
