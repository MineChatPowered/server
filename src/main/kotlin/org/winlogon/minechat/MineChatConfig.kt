package org.winlogon.minechat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TlsConfig(
    val keystore: String = "keystore.p12",
    @SerialName("keystore-password")
    val keystorePassword: String = "password"
)

@Serializable
data class ModerationDefaults(
    @SerialName("ban-message")
    val banMessage: String = "Banned by an operator.",
    @SerialName("mute-message")
    val muteMessage: String = "Muted by an operator.",
    @SerialName("kick-message")
    val kickMessage: String = "Kicked by an operator.",
    @SerialName("warn-message")
    val warnMessage: String = "Warning issued by an operator."
)

@Serializable
data class MineChatConfig(
    val port: Int = 7632,
    @SerialName("expiry-code-minutes")
    val expiryCodeMinutes: Int = 5,
    @SerialName("keep-alive-seconds")
    val keepAliveSeconds: Int = 15,
    @SerialName("mute-default-minutes")
    val muteDefaultMinutes: Int = -1,
    val tls: TlsConfig = TlsConfig(),
    @SerialName("moderation-defaults")
    val moderationDefaults: ModerationDefaults = ModerationDefaults()
)
