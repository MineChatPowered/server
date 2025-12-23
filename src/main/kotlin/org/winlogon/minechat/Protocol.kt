package org.winlogon.minechat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize

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

/**
 * Represents the common packet envelope as defined by the MineChat Protocol.
 * {
 *   0: packet_type (int),
 *   1: payload (map)
 * }
 */
data class MineChatPacket @JsonCreator constructor(
    @JsonProperty("0") val packetType: Int,
    @JsonProperty("1") val payload: Map<Int, Any?> // Payload fields use integer keys
)

// Payload data classes
data class LinkPayload @JsonCreator constructor(
    @JsonProperty("0") val linkingCode: String,
    @JsonProperty("1") val clientUuid: String
)

data class LinkOkPayload @JsonCreator constructor(
    @JsonProperty("0") val minecraftUuid: String
)

data class CapabilitiesPayload @JsonCreator constructor(
    @JsonProperty("0") val supportsComponents: Boolean
)

class AuthOkPayload

data class ChatMessagePayload @JsonCreator constructor(
    @JsonProperty("0") val format: String,
    @JsonProperty("1") val content: String
)

data class PingPayload @JsonCreator constructor(
    @JsonProperty("0") val timestampMs: Long
)

data class PongPayload @JsonCreator constructor(
    @JsonProperty("0") val timestampMs: Long
)

data class ModerationPayload @JsonCreator constructor(
    @JsonProperty("0") val action: Int,
    @JsonProperty("1") val scope: Int,
    @JsonProperty("2") val reason: String?, // Optional
    @JsonProperty("3") val durationSeconds: Int? // Optional
)

data class DisconnectPayload @JsonCreator constructor(
    @JsonProperty("0") val reason: String
)
