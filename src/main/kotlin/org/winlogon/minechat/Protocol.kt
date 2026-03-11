package org.winlogon.minechat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Packet Type IDs as defined in the spec
object PacketTypes {
    const val LINK = 0x01
    const val LINK_OK = 0x02
    const val CAPABILITIES = 0x03
    const val AUTH_OK = 0x04
    const val CHAT_MESSAGE = 0x05
    const val PING = 0x06
    const val PONG = 0x07
    const val MODERATION = 0x08

    // Custom/implementation-private packet types (0x80-0xFF)
    const val DISCONNECT = 0x80
}

object ModerationAction {
    const val KICK = 0
    const val BAN = 1
}

object ModerationScope {
    const val GLOBAL = 0
    const val LOCAL = 1
}

object ChatGradients {
    val JOIN = Pair("#27AE60", "#2ECC71")
    val LEAVE = Pair("#C0392B", "#E74C3C")
    val AUTH = Pair("#8E44AD", "#9B59B6")
    val INFO = Pair("#2980B9", "#3498DB")
}

/**
 * Represents the common packet envelope as defined by the MineChat Protocol.
 * {
 *   0: packet_type (int),
 *   1: payload (map)
 * }
 */
@Serializable
data class MineChatPacket(
    @SerialName("0") val packetType: Int,
    @SerialName("1") val payload: ByteArray // Raw CBOR bytes for the payload map
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MineChatPacket

        if (packetType != other.packetType) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

// Payload data classes
@Serializable
data class LinkPayload(
    @SerialName("0") val linkingCode: String,
    @SerialName("1") val clientUuid: String
)

@Serializable
data class LinkOkPayload(
    @SerialName("0") val minecraftUuid: String
)

@Serializable
data class CapabilitiesPayload(
    @SerialName("0") val supportsComponents: Boolean
)

@Serializable
class AuthOkPayload

@Serializable
data class ChatMessagePayload(
    @SerialName("0") val format: String,
    @SerialName("1") val content: String
)

@Serializable
data class PingPayload(
    @SerialName("0") val timestampMs: Long
)

@Serializable
data class PongPayload(
    @SerialName("0") val timestampMs: Long
)

@Serializable
data class ModerationPayload(
    @SerialName("0") val action: Int,
    @SerialName("1") val scope: Int,
    @SerialName("2") val reason: String? = null,
    @SerialName("3") val durationSeconds: Int? = null
)

@Serializable
data class DisconnectPayload(
    @SerialName("2") val reason: String
)

@Serializable
class EmptyPayload