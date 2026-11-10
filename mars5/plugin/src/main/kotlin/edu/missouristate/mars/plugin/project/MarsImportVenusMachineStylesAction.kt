package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import edu.missouristate.mars.plugin.debug.MarsMachineHighlight
import java.awt.Font
import java.util.prefs.Preferences

internal class MarsImportVenusMachineStylesAction(preferences: () -> Preferences = Preferences::userRoot) :
    VenusStyleImportAction(preferences, "mars.venus.machine.styles.v2.imported", "import.venus.machine", machineKeys,
        ::venusMachineChanges, ::describeMachineStyle)

private class VenusMachineStyle(val highlight: MarsMachineHighlight, colorPrefix: String, val fontKey: String) {
    val foreground = "${colorPrefix}Foreground"
    val background = "${colorPrefix}Background"
}

private val machineStyles = listOf(
    VenusMachineStyle(MarsMachineHighlight.CURRENT, "TextSegmentHighlight", " TextSegmentHighlightFontStyle"),
    VenusMachineStyle(MarsMachineHighlight.DELAY, "TextSegmentDelaySlotHighlight", "TextSegmentDelayslotHighightFontStyle"),
    VenusMachineStyle(MarsMachineHighlight.WRITE, "DataSegmentHighlight", "DataSegmentHighlightFontStyle"),
    VenusMachineStyle(MarsMachineHighlight.REGISTER, "RegisterHighlight", "RegisterHighlightFontStyle"),
)
private val machineKeys = machineStyles.flatMap { listOf(it.foreground, it.background, it.fontKey) }

private fun venusMachineChanges(base: EditorColorsScheme, saved: Map<String, String>): List<VenusStyleChange> {
    val earlierImport = PropertiesComponent.getInstance().isTrueValue("mars.venus.machine.styles.imported")
    return machineStyles.mapNotNull { style ->
        if (earlierImport && style.highlight != MarsMachineHighlight.REGISTER) return@mapNotNull null
        val before = style.highlight.attributes(base)
        val after = before.clone()
        saved[style.foreground]?.let(::readVenusColor)?.let { after.foregroundColor = it }
        saved[style.background]?.let(::readVenusColor)?.let { after.backgroundColor = it }
        after.fontType = when (saved[style.fontKey]) {
            "Plain" -> Font.PLAIN
            "Bold" -> Font.BOLD
            "Italic" -> Font.ITALIC
            "Bold + Italic" -> Font.BOLD or Font.ITALIC
            else -> before.fontType
        }
        if (after == before) null else VenusStyleChange(style.highlight.colorKey, style.highlight.tag, before.clone(), after)
    }
}

private fun describeMachineStyle(attributes: TextAttributes): String = venusImportMessages.getMessage(
    "import.venus.machine.style",
    venusForegroundStyle(attributes),
    attributes.backgroundColor?.let(ColorUtil::toHtmlColor) ?: venusImportMessages.getMessage("import.venus.machine.inherited"),
)
