package edu.missouristate.mars.plugin.project

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import edu.missouristate.mars.plugin.language.characterColor
import edu.missouristate.mars.plugin.language.commentColor
import edu.missouristate.mars.plugin.language.directiveColor
import edu.missouristate.mars.plugin.language.instructionMnemonicColor
import edu.missouristate.mars.plugin.language.invalidColor
import edu.missouristate.mars.plugin.language.labelColor
import edu.missouristate.mars.plugin.language.macroParameterColor
import edu.missouristate.mars.plugin.language.registerColor
import edu.missouristate.mars.plugin.language.stringColor
import java.awt.Font
import java.util.prefs.Preferences

internal class MarsImportVenusSyntaxStylesAction(preferences: () -> Preferences = Preferences::userRoot) :
    VenusStyleImportAction(preferences, "mars.venus.syntax.styles.imported", "import.venus.syntax", syntaxKeys,
        ::venusSyntaxChanges, ::venusForegroundStyle)

// Positions are the on-disk Venus token IDs. Only styles exposed by the Venus MIPS editor are migrated.
private val syntaxCategories = listOf(
    VenusSyntaxCategory(1, "comment", commentColor),
    VenusSyntaxCategory(3, "string", stringColor),
    VenusSyntaxCategory(4, "character", characterColor),
    VenusSyntaxCategory(5, "label", labelColor),
    VenusSyntaxCategory(6, "instruction", instructionMnemonicColor),
    VenusSyntaxCategory(7, "directive", directiveColor),
    VenusSyntaxCategory(8, "register", registerColor),
    VenusSyntaxCategory(10, "invalid", invalidColor),
    VenusSyntaxCategory(11, "parameter", macroParameterColor),
)

private class VenusSyntaxCategory(position: Int, val label: String, val key: TextAttributesKey) {
    val colorKey = "SyntaxStyleColor_$position"
    val boldKey = "SyntaxStyleBold_$position"
    val italicKey = "SyntaxStyleItalic_$position"
}

private val syntaxKeys = syntaxCategories.flatMap { listOf(it.colorKey, it.boldKey, it.italicKey) }

internal fun venusSyntaxChanges(base: EditorColorsScheme, saved: Map<String, String>): List<VenusStyleChange> =
    syntaxCategories.mapNotNull { category ->
        val before = base.getAttributes(category.key)?.clone() ?: TextAttributes()
        val after = before.clone()
        saved[category.colorKey]?.let(::readVenusColor)?.let { after.foregroundColor = it }
        after.fontType = before.fontType.withFontFlag(saved[category.boldKey].venusBoolean(), Font.BOLD)
            .withFontFlag(saved[category.italicKey].venusBoolean(), Font.ITALIC)
        if (after == before) null else VenusStyleChange(category.key, category.label, before, after)
    }

private fun Int.withFontFlag(value: Boolean?, flag: Int): Int =
    if (value == null) this else if (value) this or flag else this and flag.inv()
