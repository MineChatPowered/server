@file:OptIn(ExperimentalSerializationApi::class)

package org.winlogon.minechat

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

object PacketTypes {
    const val LINK = 0x01
    const val LINK_OK = 0x02
    const val CAPABILITIES = 0x03
    const val AUTH_OK = 0x04
    const val CHAT_MESSAGE = 0x05
    const val PING = 0x06
    const val PONG = 0x07
    const val MODERATION = 0x08
    const val SYSTEM_DISCONNECT = 0x09
}

object ModerationAction {
    const val WARN = 0
    const val MUTE = 1
    const val KICK = 2
    const val BAN = 3
}

object ModerationScope {
    const val CLIENT = 0
    const val ACCOUNT = 1
}

object SystemDisconnectReason {
    const val SHUTDOWN = 0
    const val MAINTENANCE = 1
    const val INTERNAL_ERROR = 2
    const val OVERLOADED = 3
}

object ChatGradients {
    val JOIN = Pair("#27AE60", "#2ECC71")
    val LEAVE = Pair("#C0392B", "#E74C3C")
    val AUTH = Pair("#8E44AD", "#9B59B6")
    val INFO = Pair("#2980B9", "#3498DB")
}

@Serializable
sealed class PacketPayload

@Serializable
data class LinkPayload(
    val linking_code: String,
    val client_uuid: String
) : PacketPayload()

@Serializable
data class LinkOkPayload(
    val minecraft_uuid: String
) : PacketPayload()

@Serializable
data class CapabilitiesPayload(
    val supports_components: Boolean
) : PacketPayload()

@Serializable
class AuthOkPayload : PacketPayload()

@Serializable
data class ChatMessagePayload(
    val format: String,
    val content: String
) : PacketPayload()

@Serializable
data class PingPayload(
    val timestamp_ms: Long
) : PacketPayload()

@Serializable
data class PongPayload(
    val timestamp_ms: Long
) : PacketPayload()

@Serializable
data class ModerationPayload(
    val action: Int,
    val scope: Int,
    val reason: String? = null,
    val duration_seconds: Int? = null
) : PacketPayload()

@Serializable
data class SystemDisconnectPayload(
    val reason_code: Int,
    val message: String
) : PacketPayload()

@Serializable
class EmptyPayload : PacketPayload()

data class MineChatPacket(
    @CborLabel(0) val packetType: Int,
    @CborLabel(1) val payload: PacketPayload
) {
    companion object : KSerializer<MineChatPacket> {
        private val packetDescriptor = buildClassSerialDescriptor("MineChatPacket") {
            element<Int>("packetType", annotations = listOf(CborLabel(0)))
            element<PacketPayload>("payload", annotations = listOf(CborLabel(1)))
        }

        override val descriptor: SerialDescriptor get() = packetDescriptor

        override fun serialize(encoder: Encoder, value: MineChatPacket) {
            encoder.encodeStructure(packetDescriptor) {
                encodeIntElement(packetDescriptor, 0, value.packetType)
                @Suppress("UNCHECKED_CAST")
                encodeSerializableElement(
                    packetDescriptor, 1,
                    getPayloadSerializer(value.packetType) as kotlinx.serialization.SerializationStrategy<PacketPayload>,
                    value.payload
                )
            }
        }

        override fun deserialize(decoder: Decoder): MineChatPacket {
            var packetType = 0
            var payload: PacketPayload = EmptyPayload()

            decoder.decodeStructure(packetDescriptor) {
                while (true) {
                    when (decodeElementIndex(packetDescriptor)) {
                        0 -> packetType = decodeIntElement(packetDescriptor, 0)
                        1 -> {
                            @Suppress("UNCHECKED_CAST")
                            payload = decodeSerializableElement(
                                packetDescriptor, 1,
                                getPayloadSerializer(packetType) as kotlinx.serialization.DeserializationStrategy<PacketPayload>
                            )
                        }
                        CompositeDecoder.DECODE_DONE -> break
                    }
                }
            }
            return MineChatPacket(packetType, payload)
        }

        private fun getPayloadSerializer(type: Int): KSerializer<out PacketPayload> = when (type) {
            PacketTypes.LINK -> LinkPayload.serializer()
            PacketTypes.LINK_OK -> LinkOkPayload.serializer()
            PacketTypes.CAPABILITIES -> CapabilitiesPayload.serializer()
            PacketTypes.AUTH_OK -> AuthOkPayload.serializer()
            PacketTypes.CHAT_MESSAGE -> ChatMessagePayload.serializer()
            PacketTypes.PING -> PingPayload.serializer()
            PacketTypes.PONG -> PongPayload.serializer()
            PacketTypes.MODERATION -> ModerationPayload.serializer()
            PacketTypes.SYSTEM_DISCONNECT -> SystemDisconnectPayload.serializer()
            else -> EmptyPayload.serializer()
        }
    }
}

fun createCbor(): Cbor = Cbor {
    preferCborLabelsOverNames = true
    ignoreUnknownKeys = true
    encodeDefaults = false
}
