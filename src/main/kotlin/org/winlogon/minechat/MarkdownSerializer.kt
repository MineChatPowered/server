package org.winlogon.minechat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.ComponentSerializer

class MarkdownSerializer : ComponentSerializer<Component, Component, String> {
    override fun deserialize(input: String): Component {
        return parseMarkdown(input)
    }

    override fun serialize(component: Component): String {
        val sb = StringBuilder()
        serializeComponent(component, sb)
        return sb.toString()
    }

    private fun parseMarkdown(input: String): Component {
        if (input.isEmpty()) return Component.empty()

        val builder = Component.text()
        var remaining = input
        var currentText = StringBuilder()

        while (remaining.isNotEmpty()) {
            when {
                remaining.startsWith("**") -> {
                    if (currentText.isNotEmpty()) {
                        builder.append(Component.text(currentText.toString()))
                        currentText = StringBuilder()
                    }
                    val endIndex = remaining.indexOf("**", 2)
                    if (endIndex != -1) {
                        val boldContent = remaining.substring(2, endIndex)
                        builder.append(Component.text(boldContent).decorate(TextDecoration.BOLD))
                        remaining = remaining.substring(endIndex + 2)
                    } else {
                        currentText.append(remaining.take(2))
                        remaining = remaining.substring(2)
                    }
                }
                remaining.startsWith("~~") -> {
                    if (currentText.isNotEmpty()) {
                        builder.append(Component.text(currentText.toString()))
                        currentText = StringBuilder()
                    }
                    val endIndex = remaining.indexOf("~~", 2)
                    if (endIndex != -1) {
                        val strikeContent = remaining.substring(2, endIndex)
                        builder.append(Component.text(strikeContent).decorate(TextDecoration.STRIKETHROUGH))
                        remaining = remaining.substring(endIndex + 2)
                    } else {
                        currentText.append(remaining.take(2))
                        remaining = remaining.substring(2)
                    }
                }
                remaining.startsWith("*") -> {
                    if (currentText.isNotEmpty()) {
                        builder.append(Component.text(currentText.toString()))
                        currentText = StringBuilder()
                    }
                    val endIndex = remaining.indexOf("*", 1)
                    if (endIndex > 1) {
                        val italicContent = remaining.substring(1, endIndex)
                        builder.append(Component.text(italicContent).decorate(TextDecoration.ITALIC))
                        remaining = remaining.substring(endIndex + 1)
                    } else {
                        currentText.append(remaining.take(1))
                        remaining = remaining.substring(1)
                    }
                }
                remaining.startsWith("`") -> {
                    if (currentText.isNotEmpty()) {
                        builder.append(Component.text(currentText.toString()))
                        currentText = StringBuilder()
                    }
                    val endIndex = remaining.indexOf("`", 1)
                    if (endIndex != -1) {
                        val codeContent = remaining.substring(1, endIndex)
                        builder.append(Component.text(codeContent).color(NamedTextColor.GRAY))
                        remaining = remaining.substring(endIndex + 1)
                    } else {
                        currentText.append(remaining.take(1))
                        remaining = remaining.substring(1)
                    }
                }
                else -> {
                    currentText.append(remaining.first())
                    remaining = remaining.substring(1)
                }
            }
        }

        if (currentText.isNotEmpty()) {
            builder.append(Component.text(currentText.toString()))
        }

        return builder.build()
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
