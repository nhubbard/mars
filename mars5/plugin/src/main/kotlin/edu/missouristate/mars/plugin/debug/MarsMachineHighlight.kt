package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font

internal enum class MarsMachineHighlight(
    key: String,
    val tag: String,
    private val defaultFont: Int,
    fallback: TextAttributesKey? = null,
) {
    CURRENT("MARS_CURRENT_INSTRUCTION", "current.instruction", Font.PLAIN),
    DELAY("MARS_NEXT_DELAY_SLOT", "delay.slot", Font.PLAIN),
    WRITE("MARS_MEMORY_WRITE", "memory.write", Font.BOLD),
    REGISTER("MARS_REGISTER_WRITE", "register.write", Font.PLAIN, DefaultLanguageHighlighterColors.NUMBER);

    val colorKey = if (fallback == null) TextAttributesKey.createTextAttributesKey(key) else TextAttributesKey.createTextAttributesKey(key, fallback)

    fun attributes(scheme: EditorColorsScheme): TextAttributes =
        scheme.getAttributes(colorKey, false) ?: TextAttributes().apply { fontType = defaultFont }

    fun tableStyle(): SimpleTextAttributes =
        SimpleTextAttributes.fromTextAttributes(attributes(EditorColorsManager.getInstance().globalScheme))
}

internal fun onMachineHighlightSchemeChange(lifetime: Disposable, update: () -> Unit) {
    ApplicationManager.getApplication().messageBus.connect(lifetime)
        .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { update() })
}
