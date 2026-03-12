@file:OptIn(ExperimentalSerializationApi::class)

package org.winlogon.minechat

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
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
    const val DISCONNECT = 0x80
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

object ChatGradients {
    val JOIN = Pair("#27AE60", "#2ECC71")
    val LEAVE = Pair("#C0392B", "#E74C3C")
    val AUTH = Pair("#8E44AD", "#9B59B6")
    val INFO = Pair("#2980B9", "#3498DB")
}

@Serializable
sealed class PacketPayload

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("link")
data class LinkPayload(
    @CborLabel(0)
    val linking_code: String,
    @CborLabel(1)
    val client_uuid: String
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("link_ok")
data class LinkOkPayload(
    @CborLabel(0)
    val minecraft_uuid: String
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("capabilities")
data class CapabilitiesPayload(
    @CborLabel(0)
    val supports_components: Boolean
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("auth_ok")
class AuthOkPayload : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("chat_message")
data class ChatMessagePayload(
    @CborLabel(0)
    val format: String,
    @CborLabel(1)
    val content: String
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("ping")
data class PingPayload(
    @CborLabel(0)
    val timestamp_ms: Long
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("pong")
data class PongPayload(
    @CborLabel(0)
    val timestamp_ms: Long
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("moderation")
data class ModerationPayload(
    @CborLabel(0)
    val action: Int,
    @CborLabel(1)
    val scope: Int,
    @CborLabel(2)
    val reason: String? = null,
    @CborLabel(3)
    val duration_seconds: Int? = null
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("disconnect")
data class DisconnectPayload(
    @CborLabel(0)
    val reason: String
) : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("empty")
class EmptyPayload : PacketPayload()

@OptIn(ExperimentalSerializationApi::class)
data class MineChatPacket(
    val packetType: Int,
    val payload: PacketPayload
) {
    companion object {
        val serializer = MineChatPacketSerializer
    }
}

@OptIn(ExperimentalSerializationApi::class)
object MineChatPacketSerializer : KSerializer<MineChatPacket> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MineChatPacket") {
        element<Int>("packetType")
        element<PacketPayload>("payload")
    }

    override fun serialize(encoder: Encoder, value: MineChatPacket) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.packetType)
            @Suppress("UNCHECKED_CAST")
            encodeSerializableElement(
                descriptor, 1, 
                payloadSerializer(value.packetType) as kotlinx.serialization.SerializationStrategy<PacketPayload>, 
                value.payload
            )
        }
    }

    override fun deserialize(decoder: Decoder): MineChatPacket {
        return decoder.decodeStructure(descriptor) {
            var packetType: Int? = null
            var payload: PacketPayload? = null

            while (true) {
                when (decodeElementIndex(descriptor)) {
                    0 -> packetType = decodeIntElement(descriptor, 0)
                    1 -> {
                        val pt = packetType ?: throw SerializationException("packetType must be before payload")
                        payload = decodeSerializableElement(descriptor, 1, payloadSerializer(pt))
                    }
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("Unknown index")
                }
            }

            if (packetType == null) throw SerializationException("Missing packetType")
            if (payload == null) throw SerializationException("Missing payload")

            MineChatPacket(packetType, payload)
        }
    }

    private fun payloadSerializer(packetType: Int): KSerializer<out PacketPayload> {
        return when (packetType) {
            PacketTypes.LINK -> LinkPayload.serializer()
            PacketTypes.LINK_OK -> LinkOkPayload.serializer()
            PacketTypes.CAPABILITIES -> CapabilitiesPayload.serializer()
            PacketTypes.AUTH_OK -> AuthOkPayload.serializer()
            PacketTypes.CHAT_MESSAGE -> ChatMessagePayload.serializer()
            PacketTypes.PING -> PingPayload.serializer()
            PacketTypes.PONG -> PongPayload.serializer()
            PacketTypes.MODERATION -> ModerationPayload.serializer()
            PacketTypes.DISCONNECT -> DisconnectPayload.serializer()
            else -> EmptyPayload.serializer()
        }
    }
}

fun createCbor(): Cbor = Cbor {
    preferCborLabelsOverNames = true
    ignoreUnknownKeys = true
    encodeDefaults = false
}
