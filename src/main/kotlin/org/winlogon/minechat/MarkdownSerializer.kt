package org.winlogon.minechat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.ComponentSerializer

class MarkdownSerializer : ComponentSerializer<Component, Component, String> {
    override fun deserialize(input: String): Component {
        // Basic implementation: for now we just return a TextComponent.
        // A full markdown parser would be needed here (e.g., using commonmark-java).
        return Component.text(input)
    }

    override fun serialize(component: Component): String {
        val sb = StringBuilder()
        serializeComponent(component, sb)
        return sb.toString()
    }

    private fun serializeComponent(component: Component, sb: StringBuilder) {
        if (component is TextComponent) {
            val hasBold = component.hasDecoration(TextDecoration.BOLD)
            val hasItalic = component.hasDecoration(TextDecoration.ITALIC)
            val hasStrikethrough = component.hasDecoration(TextDecoration.STRIKETHROUGH)

            if (hasBold) sb.append("**")
            if (hasItalic) sb.append("*")
            if (hasStrikethrough) sb.append("~~")

            sb.append(component.content())

            if (hasStrikethrough) sb.append("~~")
            if (hasItalic) sb.append("*")
            if (hasBold) sb.append("**")
        }

        for (child in component.children()) {
            serializeComponent(child, sb)
        }
    }

    companion object {
        private val INSTANCE = MarkdownSerializer()
        fun markdown(): MarkdownSerializer = INSTANCE
    }
}
