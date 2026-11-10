package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import edu.missouristate.mars.plugin.debug.MarsMachineHighlight

internal class MipsColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = bundle.getMessage("colors.name")
    override fun getIcon() = MipsFileType.icon
    override fun getHighlighter() = MipsSyntaxHighlighter()
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()

    override fun getAttributeDescriptors() = (semanticColors + syntaxColors).map { (tag, key) ->
        AttributesDescriptor(bundle.getMessage("colors.$tag"), key)
    }.toTypedArray()

    override fun getAdditionalHighlightingTagToDescriptorMap() = semanticColors

    override fun getDemoText() = """
        # MIPS source
        .data
        .asciiz "MARS"
        .byte 'A', 'bad'
        # Debugger previews
        <current.instruction>Current instruction</current.instruction>
        <delay.slot>Next delay slot</delay.slot>
        <memory.write>Changed memory</memory.write>
        .eqv <equivalence>COUNT</equivalence> 3
        .eqv <equivalence>AGAIN</equivalence> <replacement>loop</replacement>
        .macro <macro>decrement</macro> (%counter)
            <instruction>addiu</instruction> %counter, %counter, -1
        .end_macro
        .text
        <label>main</label>: <instruction>li</instruction> ${'$'}t0, <equivalence>COUNT</equivalence>
        <label>loop</label>: <macro>decrement</macro> (${'$'}t0)
              <instruction>bgtz</instruction> ${'$'}t0, <equivalence>AGAIN</equivalence>
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
            "replacement" to equivalenceReplacementColor,
            "parameter" to macroParameterColor,
            "punctuation" to punctuationColor,
        ) + MarsMachineHighlight.entries.associate { it.tag to it.colorKey }
        val syntaxColors = mapOf(
            "directive" to directiveColor,
            "string" to stringColor,
            "character" to characterColor,
            "invalid" to invalidColor,
            "comment" to commentColor,
            "register" to registerColor,
        )
    }
}
