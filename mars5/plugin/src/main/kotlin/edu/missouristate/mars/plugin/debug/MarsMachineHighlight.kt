package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font
import javax.swing.JTable

internal enum class MarsMachineHighlight(
    key: String,
    val tag: String,
    private val defaultFont: Int,
) {
    CURRENT("MARS_CURRENT_INSTRUCTION", "current.instruction", Font.PLAIN),
    DELAY("MARS_NEXT_DELAY_SLOT", "delay.slot", Font.PLAIN),
    WRITE("MARS_MEMORY_WRITE", "memory.write", Font.BOLD);

    val colorKey = TextAttributesKey.createTextAttributesKey(key)

    fun attributes(scheme: EditorColorsScheme): TextAttributes =
        scheme.getAttributes(colorKey, false) ?: TextAttributes().apply { fontType = defaultFont }

    fun tableStyle(): SimpleTextAttributes =
        SimpleTextAttributes.fromTextAttributes(attributes(EditorColorsManager.getInstance().globalScheme))
}

internal fun repaintMachineHighlights(lifetime: Disposable, table: JTable) {
    ApplicationManager.getApplication().messageBus.connect(lifetime)
        .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { table.repaint() })
}
