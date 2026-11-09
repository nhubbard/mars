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

    override fun getAttributeDescriptors() = semanticColors.map { (tag, key) ->
        AttributesDescriptor(bundle.getMessage("colors.$tag"), key)
    }.toTypedArray()

    override fun getAdditionalHighlightingTagToDescriptorMap() = semanticColors

    override fun getDemoText() = """
        .eqv <equivalence>COUNT</equivalence> 3
        .macro <macro>decrement</macro> (%counter)
            <instruction>addiu</instruction> %counter, %counter, -1
        .end_macro
        .text
        <label>main</label>: <instruction>li</instruction> ${'$'}t0, <equivalence>COUNT</equivalence>
        <label>loop</label>: <macro>decrement</macro> (${'$'}t0)
              <instruction>bgtz</instruction> ${'$'}t0, <label>loop</label>
              <instruction>li</instruction> ${'$'}v0, 10
              <instruction>syscall</instruction>
    """.trimIndent()

    private companion object {
        val bundle = DynamicBundle(MipsColorSettingsPage::class.java, "messages.MarsBundle")
        val semanticColors = mapOf(
            "instruction" to instructionMnemonicColor,
            "label" to labelColor,
            "macro" to macroColor,
            "equivalence" to equivalenceColor,
        )
    }
}
