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
import java.awt.Color
import java.awt.Font
import java.util.prefs.Preferences

private val messages get() = venusImportMessages

internal abstract class VenusStyleImportAction(
    private val preferences: () -> Preferences,
    private val importedKey: String,
    private val messagePrefix: String,
    private val keys: List<String>,
    private val resolve: (EditorColorsScheme, Map<String, String>) -> List<VenusStyleChange>,
    private val describe: (TextAttributes) -> String,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !PropertiesComponent.getInstance().isTrueValue(importedKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = PropertiesComponent.getInstance()
        if (properties.isTrueValue(importedKey)) return
        val title = messages.getMessage("$messagePrefix.title")
        val saved = loadVenusPreferences(project, title) { preferences().readSavedVenusPreferences(keys) } ?: return
        val colors = EditorColorsManager.getInstance()
        val base = colors.globalScheme
        val changes = resolve(base, saved)
        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, messages.getMessage("$messagePrefix.empty"), title)
            return
        }

        val schemeName = UniqueNameGenerator.generateUniqueName(messages.getMessage("$messagePrefix.scheme"), "", "", " (", ")") {
            colors.getScheme(it) == null
        }
        val preview = messages.getMessage("$messagePrefix.preview", schemeName, changes.joinToString("\n") { it.describe(describe) })
        if (!confirmVenusImport(project, title, preview)) return
        if (properties.isTrueValue(importedKey) || colors.globalScheme !== base ||
            colors.getScheme(schemeName) != null || changes != resolve(base, saved)
        ) {
            Messages.showInfoMessage(project, messages.getMessage("$messagePrefix.stale"), title)
            return
        }
        val scheme = base.clone() as AbstractColorsScheme
        scheme.name = schemeName
        for (change in changes) scheme.setAttributes(change.key, change.after)
        scheme.setSaveNeeded(true)
        colors.addColorScheme(scheme)
        properties.setValue(importedKey, true)
    }
}

internal data class VenusStyleChange(
    val key: TextAttributesKey,
    private val label: String,
    val before: TextAttributes,
    val after: TextAttributes,
) {
    fun describe(style: (TextAttributes) -> String): String =
        messages.getMessage("import.venus.change", messages.getMessage("colors.$label"), style(before), style(after))
}

internal fun venusForegroundStyle(attributes: TextAttributes): String {
    val color = attributes.foregroundColor?.let(ColorUtil::toHtmlColor) ?: messages.getMessage("import.venus.syntax.inherited")
    val fontKey = when (attributes.fontType) {
        Font.BOLD -> "bold"
        Font.ITALIC -> "italic"
        Font.BOLD or Font.ITALIC -> "bold.italic"
        else -> "plain"
    }
    return messages.getMessage("import.venus.syntax.style", color, messages.getMessage("import.venus.syntax.$fontKey"))
}

internal fun readVenusColor(value: String): Color? = try {
    Integer.decode(value).takeIf { it in 0..0xffffff }?.let(::Color)
} catch (_: NumberFormatException) {
    null
}
