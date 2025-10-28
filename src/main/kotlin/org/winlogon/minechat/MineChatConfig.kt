package org.winlogon.minechat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MineChatConfig(
    val port: Int = 25575,
    @SerialName("expiry-code-minutes")
    val expiryCodeMinutes: Int = 5
)
