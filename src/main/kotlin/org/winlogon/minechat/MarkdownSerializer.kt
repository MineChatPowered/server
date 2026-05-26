package org.winlogon.minechat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.ComponentSerializer

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

class MarkdownSerializer : ComponentSerializer<Component, Component, String> {
    override fun deserialize(input: String): Component = parseMarkdown(input)

    override fun serialize(component: Component): String {
        val sb = StringBuilder()
        serializeComponent(component, sb)
        return sb.toString()
    }

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    private fun parseMarkdown(input: String): Component {
        if (input.isEmpty()) return Component.empty()
        val tree = parser.buildMarkdownTreeFromString(input)
        val builder = Component.text()
        processChildren(tree, builder, input)
        return builder.build()
    }

    private fun processChildren(node: ASTNode, builder: TextComponent.Builder, source: String) {
        for (child in node.children) {
            processNode(child, builder, source)
        }
    }

    /**
     * Processes a Markdown AST node by recursively building its children,
     * then applying a transformation to the resulting component before appending it.
     *
     * @param node The AST node whose children will be processed.
     * @param source The original Markdown source string used for text extraction.
     * @param builder The parent component builder to append the result to.
     * @param transform A function that applies styling or modifications to the built component.
     */
    private fun processStyledNode(
        node: ASTNode,
        source: String,
        builder: TextComponent.Builder,
        transform: (TextComponent) -> Component
    ) {
        val child = Component.text().also {
            processChildren(node, it, source)
        }.build()

        builder.append(transform(child))
    }

    private fun processNode(node: ASTNode, builder: TextComponent.Builder, source: String) {
        when (node.type) {
            MarkdownElementTypes.STRONG ->
                processStyledNode(node, source, builder) {
                    it.decorate(TextDecoration.BOLD)
                }

            MarkdownElementTypes.EMPH ->
                processStyledNode(node, source, builder) {
                    it.decorate(TextDecoration.ITALIC)
                }

            GFMElementTypes.STRIKETHROUGH ->
                processStyledNode(node, source, builder) {
                    it.decorate(TextDecoration.STRIKETHROUGH)
                }

            MarkdownElementTypes.CODE_SPAN ->
                processStyledNode(node, source, builder) {
                    it.color(NamedTextColor.GRAY)
                }

            MarkdownTokenTypes.TEXT -> {
                val markdownText = source.substring(node.startOffset, node.endOffset)
                builder.append(Component.text(markdownText))
            }

            else -> processChildren(node, builder, source)
        }
    }

    private fun serializeComponent(component: Component, sb: StringBuilder) {
        if (component is TextComponent) {
            val bold = component.hasDecoration(TextDecoration.BOLD)
            val italic = component.hasDecoration(TextDecoration.ITALIC)
            val strike = component.hasDecoration(TextDecoration.STRIKETHROUGH)

            if (bold) sb.append("**")
            if (italic) sb.append("*")
            if (strike) sb.append("~~")

            sb.append(component.content())

            if (strike) sb.append("~~")
            if (italic) sb.append("*")
            if (bold) sb.append("**")
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
