package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

internal class MipsColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = bundle.getMessage("colors.name")
    override fun getIcon() = MipsFileType.icon
    override fun getHighlighter() = MipsSyntaxHighlighter()
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()

    override fun getAttributeDescriptors() =
        arrayOf(AttributesDescriptor(bundle.getMessage("colors.instruction"), instructionMnemonicColor))

    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf("instruction" to instructionMnemonicColor)

    override fun getDemoText() = """
        .text
        main: <instruction>addiu</instruction> ${'$'}t0, ${'$'}zero, 1
              <instruction>li</instruction> ${'$'}v0, 10
              <instruction>syscall</instruction>
    """.trimIndent()

    private companion object {
        val bundle = DynamicBundle(MipsColorSettingsPage::class.java, "messages.MarsBundle")
    }
}
