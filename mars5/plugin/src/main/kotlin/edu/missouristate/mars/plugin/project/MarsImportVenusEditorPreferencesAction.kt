package edu.missouristate.mars.plugin.project

import com.intellij.application.options.EditorFontsConstants
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontCache
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.awt.GraphicsEnvironment
import java.util.prefs.Preferences

private const val editorImportedKey = "mars.venus.editor.preferences.imported"
private val messages get() = venusImportMessages
private val editorKeys = listOf("EditorLineNumbersDisplayed", "CaretBlinkRate", "EditorFontFamily", "EditorFontSize")

internal class MarsImportVenusEditorPreferencesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !PropertiesComponent.getInstance().isTrueValue(editorImportedKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = PropertiesComponent.getInstance()
        if (properties.isTrueValue(editorImportedKey)) return
        val title = messages.getMessage("import.venus.editor.title")
        val legacy = loadVenusPreferences(project, title) {
            val values = preferences().readSavedVenusPreferences(editorKeys)
            val family = values["EditorFontFamily"]?.let { saved ->
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.firstOrNull { it.equals(saved, ignoreCase = true) }
            }
            VenusEditorPreferences(values, family)
        } ?: return

        val editor = EditorSettingsExternalizable.getInstance()
        val fonts = AppEditorFontOptions.getInstance()
        val previous = EditorPreferences(editor, fonts)
        val updated = legacy.resolve(previous)
        if (updated == previous) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.editor.empty"), title)
            return
        }
        val text = messages.getMessage("import.venus.editor.preview", previous.changesTo(updated))
        if (!confirmVenusImport(project, title, text)) return
        if (EditorPreferences(editor, fonts) != previous || properties.isTrueValue(editorImportedKey)) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.editor.stale"), title)
            return
        }

        editor.isLineNumbersShown = updated.lineNumbers
        editor.isBlinkCaret = updated.blinkCaret
        editor.blinkPeriod = updated.blinkPeriod
        if (updated.fontFamily != previous.fontFamily || updated.fontSize != previous.fontSize) {
            val preferences = FontPreferencesImpl()
            fonts.fontPreferences.copyTo(preferences)
            if (updated.fontFamily != previous.fontFamily) {
                val realFamilies = preferences.realFontFamilies
                val effectiveFamilies = preferences.effectiveFontFamilies
                preferences.setRealFontFamilies(listOf(updated.fontFamily) + realFamilies.drop(1).filterNot { it == updated.fontFamily })
                preferences.setEffectiveFontFamilies(listOf(updated.fontFamily) + effectiveFamilies.drop(1).filterNot { it == updated.fontFamily })
                preferences.regularSubFamily = null
                preferences.boldSubFamily = null
                preferences.setCharacterVariants(emptySet())
            }
            preferences.setTemplateFontSize(updated.fontSize)
            preferences.setFontSize(updated.fontFamily, updated.fontSize)
            fonts.update(preferences)
            EditorFontCache.getInstance().reset()
        }
        EditorFactory.getInstance().refreshAllEditors()
        properties.setValue(editorImportedKey, true)
    }
}

private class VenusEditorPreferences(private val values: Map<String, String>, private val fontFamily: String?) {
    fun resolve(previous: EditorPreferences): EditorPreferences {
        val rate = values["CaretBlinkRate"]?.toIntOrNull()?.takeIf {
            it == 0 || it in EditorSettingsExternalizable.BLINKING_RANGE.min..EditorSettingsExternalizable.BLINKING_RANGE.max
        }
        val fontSize = values["EditorFontSize"]?.toIntOrNull()?.takeIf {
            it in EditorFontsConstants.getMinEditorFontSize()..EditorFontsConstants.getMaxEditorFontSize()
        }
        return EditorPreferences(
            values["EditorLineNumbersDisplayed"].venusBoolean() ?: previous.lineNumbers,
            rate?.let { it > 0 } ?: previous.blinkCaret,
            rate?.takeIf { it > 0 } ?: previous.blinkPeriod,
            fontFamily ?: previous.fontFamily,
            fontSize?.toFloat() ?: previous.fontSize,
        )
    }
}

private data class EditorPreferences(
    val lineNumbers: Boolean,
    val blinkCaret: Boolean,
    val blinkPeriod: Int,
    val fontFamily: String,
    val fontSize: Float,
) {
    constructor(editor: EditorSettingsExternalizable, fonts: AppEditorFontOptions) : this(
        editor.isLineNumbersShown, editor.isBlinkCaret, editor.blinkPeriod,
        fonts.fontPreferences.fontFamily, fonts.fontPreferences.getSize2D(fonts.fontPreferences.fontFamily),
    )

    private fun blinkText() = if (blinkCaret) messages.getMessage("import.venus.editor.blink.rate", blinkPeriod) else venusFlagText(false)

    fun changesTo(updated: EditorPreferences): String = buildList {
        fun change(label: String, before: String, after: String) {
            if (before != after) add(messages.getMessage("import.venus.change", messages.getMessage(label), before, after))
        }
        change("import.venus.editor.lines", venusFlagText(lineNumbers), venusFlagText(updated.lineNumbers))
        change("import.venus.editor.blink", blinkText(), updated.blinkText())
        change("import.venus.editor.family", fontFamily, updated.fontFamily)
        change("import.venus.editor.size", fontSize.toString(), updated.fontSize.toString())
    }.joinToString("\n")
}
