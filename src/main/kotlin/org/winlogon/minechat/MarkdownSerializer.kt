package org.winlogon.minechat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.ComponentSerializer

class MarkdownSerializer : ComponentSerializer<Component, Component, String> {
    override fun deserialize(input: String): Component = parseMarkdown(input)

    override fun serialize(component: Component): String {
        val sb = StringBuilder()
        serializeComponent(component, sb)
        return sb.toString()
    }

    private fun parseMarkdown(input: String): Component {
        if (input.isEmpty()) return Component.empty()

        val builder = Component.text() // builder to accumulate components
        val buffer = StringBuilder()
        var i = 0
        val n = input.length

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                builder.append(Component.text(buffer.toString()))
                buffer.setLength(0)
            }
        }

        fun appendDecorated(text: String, vararg decorations: TextDecoration) {
            var comp = Component.text(text)
            for (dec in decorations) comp = comp.decorate(dec)
            builder.append(comp)
        }

        // helper to find closing token start index, returns -1 if not found
        fun findClosing(startIndex: Int, token: String): Int =
            input.indexOf(token, startIndex + token.length).also { if (it == -1) { /* not found */ } }

        while (i < n) {
            // Try multi-char tokens first
            when {
                input.startsWith("**", i) -> {
                    val close = findClosing(i, "**")
                    if (close != -1) {
                        flushBuffer()
                        val content = input.substring(i + 2, close)
                        appendDecorated(content, TextDecoration.BOLD)
                        i = close + 2
                    } else {
                        // treat literally
                        buffer.append("**")
                        i += 2
                    }
                }

                input.startsWith("~~", i) -> {
                    val close = findClosing(i, "~~")
                    if (close != -1) {
                        flushBuffer()
                        val content = input.substring(i + 2, close)
                        appendDecorated(content, TextDecoration.STRIKETHROUGH)
                        i = close + 2
                    } else {
                        buffer.append("~~")
                        i += 2
                    }
                }

                // Single-char tokens
                input[i] == '*' -> {
                    // avoid matching "**" (already handled)
                    val close = input.indexOf('*', i + 1)
                    if (close != -1) {
                        flushBuffer()
                        val content = input.substring(i + 1, close)
                        appendDecorated(content, TextDecoration.ITALIC)
                        i = close + 1
                    } else {
                        buffer.append('*')
                        i++
                    }
                }

                input[i] == '`' -> {
                    val close = input.indexOf('`', i + 1)
                    if (close != -1) {
                        flushBuffer()
                        val content = input.substring(i + 1, close)
                        // inline code — gray color in original
                        builder.append(Component.text(content).color(NamedTextColor.GRAY))
                        i = close + 1
                    } else {
                        buffer.append('`')
                        i++
                    }
                }

                else -> {
                    buffer.append(input[i])
                    i++
                }
            }
        }

        flushBuffer()
        return builder.build()
    }

    private fun serializeComponent(component: Component, sb: StringBuilder) {
        // If this is a TextComponent, write markers for active decorations
        if (component is TextComponent) {
            val bold = component.hasDecoration(TextDecoration.BOLD)
            val italic = component.hasDecoration(TextDecoration.ITALIC)
            val strike = component.hasDecoration(TextDecoration.STRIKETHROUGH)

            // openers (fixed order)
            if (bold) sb.append("**")
            if (italic) sb.append("*")
            if (strike) sb.append("~~")

            sb.append(component.content())

            // closers in reverse order
            if (strike) sb.append("~~")
            if (italic) sb.append("*")
            if (bold) sb.append("**")
        }

        // serialize children
        for (child in component.children()) {
            serializeComponent(child, sb)
        }
    }

    companion object {
        private val INSTANCE = MarkdownSerializer()
        fun markdown(): MarkdownSerializer = INSTANCE
    }
}
