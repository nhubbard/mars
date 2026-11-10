package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorUtil
import com.intellij.util.text.UniqueNameGenerator
import edu.missouristate.mars.plugin.language.characterColor
import edu.missouristate.mars.plugin.language.commentColor
import edu.missouristate.mars.plugin.language.directiveColor
import edu.missouristate.mars.plugin.language.instructionMnemonicColor
import edu.missouristate.mars.plugin.language.invalidColor
import edu.missouristate.mars.plugin.language.labelColor
import edu.missouristate.mars.plugin.language.macroParameterColor
import edu.missouristate.mars.plugin.language.registerColor
import edu.missouristate.mars.plugin.language.stringColor
import java.awt.Color
import java.awt.Font
import java.util.prefs.Preferences

private const val syntaxImportedKey = "mars.venus.syntax.styles.imported"
private val messages get() = venusImportMessages

internal class MarsImportVenusSyntaxStylesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !PropertiesComponent.getInstance().isTrueValue(syntaxImportedKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = PropertiesComponent.getInstance()
        if (properties.isTrueValue(syntaxImportedKey)) return
        val title = messages.getMessage("import.venus.syntax.title")
        val saved = loadVenusPreferences(project, title) { preferences().readSavedVenusPreferences(syntaxKeys) } ?: return
        val colors = EditorColorsManager.getInstance()
        val base = colors.globalScheme
        val changes = venusSyntaxChanges(base, saved)
        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.syntax.empty"), title)
            return
        }

        val schemeName = UniqueNameGenerator.generateUniqueName(messages.getMessage("import.venus.syntax.scheme"), "", "", " (", ")") {
            colors.getScheme(it) == null
        }
        val preview = messages.getMessage("import.venus.syntax.preview", schemeName, changes.joinToString("\n", transform = VenusSyntaxChange::describe))
        if (!confirmVenusImport(project, title, preview)) return
        if (properties.isTrueValue(syntaxImportedKey) || colors.globalScheme !== base ||
            colors.getScheme(schemeName) != null || changes != venusSyntaxChanges(base, saved)
        ) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.syntax.stale"), title)
            return
        }
        val scheme = base.clone() as AbstractColorsScheme
        scheme.name = schemeName
        for (change in changes) scheme.setAttributes(change.key, change.after)
        scheme.setSaveNeeded(true)
        colors.addColorScheme(scheme)
        properties.setValue(syntaxImportedKey, true)
    }
}

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

internal data class VenusSyntaxChange(
    val key: TextAttributesKey,
    private val label: String,
    val before: TextAttributes,
    val after: TextAttributes,
) {
    fun describe(): String = messages.getMessage("import.venus.change", messages.getMessage("colors.$label"), style(before), style(after))

    private fun style(attributes: TextAttributes): String {
        val color = attributes.foregroundColor?.let(ColorUtil::toHtmlColor) ?: messages.getMessage("import.venus.syntax.inherited")
        val fontKey = when (attributes.fontType) {
            Font.BOLD -> "bold"
            Font.ITALIC -> "italic"
            Font.BOLD or Font.ITALIC -> "bold.italic"
            else -> "plain"
        }
        return messages.getMessage("import.venus.syntax.style", color, messages.getMessage("import.venus.syntax.$fontKey"))
    }
}

internal fun venusSyntaxChanges(base: EditorColorsScheme, saved: Map<String, String>): List<VenusSyntaxChange> =
    syntaxCategories.mapNotNull { category ->
        val before = base.getAttributes(category.key)?.clone() ?: TextAttributes()
        val after = before.clone()
        saved[category.colorKey]?.let(::readColor)?.let { after.foregroundColor = it }
        after.fontType = before.fontType.withFontFlag(saved[category.boldKey].venusBoolean(), Font.BOLD)
            .withFontFlag(saved[category.italicKey].venusBoolean(), Font.ITALIC)
        if (after == before) null else VenusSyntaxChange(category.key, category.label, before, after)
    }

private fun Int.withFontFlag(value: Boolean?, flag: Int): Int =
    if (value == null) this else if (value) this or flag else this and flag.inv()

private fun readColor(value: String): Color? = try {
    Integer.decode(value).takeIf { it in 0..0xffffff }?.let(::Color)
} catch (_: NumberFormatException) {
    null
}
