package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.SchemeState
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.debug.MarsMachineHighlight
import edu.missouristate.mars.plugin.language.characterColor
import edu.missouristate.mars.plugin.language.commentColor
import edu.missouristate.mars.plugin.language.directiveColor
import edu.missouristate.mars.plugin.language.instructionMnemonicColor
import edu.missouristate.mars.plugin.language.invalidColor
import edu.missouristate.mars.plugin.language.labelColor
import edu.missouristate.mars.plugin.language.macroParameterColor
import edu.missouristate.mars.plugin.language.registerColor
import edu.missouristate.mars.plugin.language.stringColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.util.prefs.BackingStoreException

@TestApplication
class MarsImportVenusSyntaxStylesPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val project get() = projectFixture.get()
    private val colors get() = EditorColorsManager.getInstance() as EditorColorsManagerImpl
    private val properties get() = PropertiesComponent.getInstance()
    private val marker = "mars.venus.syntax.styles.imported"

    @Test
    fun `syntax import previews all Venus categories and creates a separate MIPS color scheme once`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withSchemes {
                val registered = assertInstanceOf(
                    MarsImportVenusSyntaxStylesAction::class.java, ActionManager.getInstance().getAction("Mars.ImportVenusSyntaxStyles"),
                )
                val withoutProject = event(registered, DataContext.EMPTY_CONTEXT)
                assertEquals(ActionUpdateThread.BGT, registered.actionUpdateThread)
                registered.update(withoutProject)
                assertFalse(withoutProject.presentation.isEnabledAndVisible)
                registered.actionPerformed(withoutProject)
                withDialog({ Messages.CANCEL }) { registered.actionPerformed(event(registered)) }

                val base = addScheme("Venus import test source")
                colors.setGlobalScheme(base)
                val collision = addScheme("MARS Venus")
                val retained = TextAttributes(null, Color.YELLOW, Color.RED, EffectType.WAVE_UNDERSCORE, Font.BOLD)
                base.setAttributes(instructionMnemonicColor, retained)
                val root = MemoryPreferences()
                val modern = root.node("edu/missouristate/mars")
                val keys = mapOf(1 to commentColor, 3 to stringColor, 4 to characterColor, 5 to labelColor,
                    6 to instructionMnemonicColor, 7 to directiveColor, 8 to registerColor, 10 to invalidColor, 11 to macroParameterColor)
                for ((position, _) in keys) modern.put("SyntaxStyleColor_$position", "0x00012345")
                modern.putBoolean("SyntaxStyleBold_6", false)
                modern.putBoolean("SyntaxStyleItalic_6", true)
                modern.putBoolean("SyntaxStyleBold_1", true)
                modern.putBoolean("SyntaxStyleItalic_1", true)
                modern.putBoolean("SyntaxStyleBold_3", true)
                modern.putBoolean("SyntaxStyleItalic_3", false)
                modern.putBoolean("SyntaxStyleBold_4", false)
                modern.putBoolean("SyntaxStyleItalic_4", false)
                modern.put("SyntaxStyleColor_9", "0x00ffffff")
                val action = MarsImportVenusSyntaxStylesAction { root }
                assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
                withDialog({ preview ->
                    assertTrue(preview.contains("MARS Venus (2)"))
                    assertTrue(preview.contains("Instruction mnemonic"))
                    assertTrue(preview.contains("Default text color, bold → #012345, italic"))
                    assertTrue(preview.contains("#012345, bold italic"))
                    assertTrue(preview.contains("#012345, bold"))
                    assertTrue(preview.contains("#012345, plain"))
                    assertFalse(preview.contains("Punctuation and operators"))
                    Messages.CANCEL
                }) { action.actionPerformed(event(action)) }
                assertNull(colors.getScheme("MARS Venus (2)"))
                assertFalse(properties.isTrueValue(marker))

                withDialog(TestDialog.OK) { action.actionPerformed(event(action)) }
                val imported = assertInstanceOf(AbstractColorsScheme::class.java, colors.getScheme("MARS Venus (2)"))
                assertEquals(SchemeState.POSSIBLY_CHANGED, imported.schemeState)
                assertSame(base, colors.globalScheme)
                assertSame(collision, colors.getScheme("MARS Venus"))
                assertEquals(retained, base.getAttributes(instructionMnemonicColor))
                for (key in keys.values) assertEquals(Color(0x012345), imported.getAttributes(key).foregroundColor)
                assertEquals(Color.YELLOW, imported.getAttributes(instructionMnemonicColor).backgroundColor)
                assertEquals(Color.RED, imported.getAttributes(instructionMnemonicColor).effectColor)
                assertEquals(Font.ITALIC, imported.getAttributes(instructionMnemonicColor).fontType)
                assertEquals(base.getAttributes(DefaultLanguageHighlighterColors.KEYWORD),
                    imported.getAttributes(DefaultLanguageHighlighterColors.KEYWORD))
                assertTrue(properties.isTrueValue(marker))
                assertFalse(event(action).also(action::update).presentation.isEnabledAndVisible)
                action.actionPerformed(event(action))
            }
        }
    }

    @Test
    fun `syntax preview rejects stale schemes and reports empty or unreadable preferences`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withSchemes {
                val root = MemoryPreferences()
                val saved = root.node("mars")
                val action = MarsImportVenusSyntaxStylesAction { root }
                saved.put("SyntaxStyleColor_1", "invalid")
                saved.put("SyntaxStyleColor_3", "0x1000000")
                saved.put("SyntaxStyleColor_4", "-1")
                saved.put("SyntaxStyleBold_7", "invalid")
                withDialog({ info ->
                    assertTrue(info.contains("No saved Venus syntax styles differ"))
                    Messages.OK
                }) { action.actionPerformed(event(action)) }
                val unreadable = MarsImportVenusSyntaxStylesAction { throw BackingStoreException("unavailable") }
                withDialog({ info ->
                    assertEquals("Could not read local Venus preferences.", info)
                    Messages.OK
                }) { unreadable.actionPerformed(event(unreadable)) }
                val base = addScheme("Venus stale source")
                val other = addScheme("Venus other source")
                saved.put("SyntaxStyleColor_1", "0x00012345")
                colors.setGlobalScheme(base)
                for (change in listOf(
                    { properties.setValue(marker, true) },
                    { colors.setGlobalScheme(other) },
                    {
                        addScheme("MARS Venus")
                        Unit
                    },
                    { base.setAttributes(commentColor, TextAttributes().apply { foregroundColor = Color.CYAN }) },
                )) {
                    var count = 0
                    withDialog({ message ->
                        if (count++ == 0) change() else assertTrue(message.contains("Color schemes changed"))
                        Messages.OK
                    }) { action.actionPerformed(event(action)) }
                    assertEquals(2, count)
                    properties.unsetValue(marker)
                    colors.setGlobalScheme(base)
                    colors.getScheme("MARS Venus")?.let(colors::removeScheme)
                }
                assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
            }
        }
    }

    @Test
    fun `a partial saved style works when a base scheme has no attributes for that token`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val base = object : EditorColorsScheme by colors.globalScheme {
                override fun getAttributes(key: TextAttributesKey): TextAttributes? = null
            }
            val changes = venusSyntaxChanges(base, mapOf("SyntaxStyleBold_1" to "true"))
            val change = changes.single()
            assertSame(commentColor, change.key)
            assertEquals(Font.PLAIN, change.before.fontType)
            assertEquals(Font.BOLD, change.after.fontType)
            assertNull(change.after.foregroundColor)
        }
    }

    private fun addScheme(name: String): EditorColorsScheme = (colors.globalScheme.clone() as EditorColorsScheme).apply {
        this.name = name
        colors.addColorScheme(this)
    }

    @Test
    fun `machine styles migrate saved colors and font styles into a separate scheme`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withSchemes {
                val marker = "mars.venus.machine.styles.imported"
                val previous = properties.getValue(marker)
                try {
                    properties.unsetValue(marker)
                    assertInstanceOf(MarsImportVenusMachineStylesAction::class.java,
                        ActionManager.getInstance().getAction("Mars.ImportVenusMachineStyles"))
                    val base = colors.globalScheme
                    val root = MemoryPreferences()
                    val venus = root.node("mars")
                    val action = MarsImportVenusMachineStylesAction { root }
                    venus.put("DataSegmentHighlightFontStyle", "invalid")
                    withDialog({ info ->
                        assertTrue(info.contains("No saved Venus highlight styles differ"))
                        Messages.OK
                    }) { action.actionPerformed(event(action)) }
                    venus.put("TextSegmentHighlightForeground", "0x00012345")
                    venus.put(" TextSegmentHighlightFontStyle", "Italic")
                    venus.put("TextSegmentDelaySlotHighlightBackground", "0x00345566")
                    venus.put("TextSegmentDelayslotHighightFontStyle", "Plain")
                    venus.put("DataSegmentHighlightForeground", "0x00787980")
                    venus.put("DataSegmentHighlightBackground", "0x00010203")
                    venus.put("DataSegmentHighlightFontStyle", "Bold")
                    withDialog({ preview ->
                        assertTrue(preview.contains("Current instruction"))
                        assertTrue(preview.contains("#012345, italic"))
                        assertTrue(preview.contains("background #345566"))
                        assertTrue(preview.contains("#787980, bold, background #010203"))
                        Messages.CANCEL
                    }) { action.actionPerformed(event(action)) }
                    assertNull(colors.getScheme("MARS Venus Machine"))
                    venus.put("DataSegmentHighlightFontStyle", "Bold + Italic")
                    withDialog(TestDialog.OK) { action.actionPerformed(event(action)) }
                    val scheme = checkNotNull(colors.getScheme("MARS Venus Machine"))
                    assertSame(base, colors.globalScheme)
                    assertEquals(Font.ITALIC, MarsMachineHighlight.CURRENT.attributes(scheme).fontType)
                    assertEquals(Color(0x012345), MarsMachineHighlight.CURRENT.attributes(scheme).foregroundColor)
                    assertEquals(Color(0x345566), MarsMachineHighlight.DELAY.attributes(scheme).backgroundColor)
                    assertEquals(Font.PLAIN, MarsMachineHighlight.DELAY.attributes(scheme).fontType)
                    assertEquals(Font.BOLD or Font.ITALIC, MarsMachineHighlight.WRITE.attributes(scheme).fontType)
                    assertEquals(Color(0x010203), MarsMachineHighlight.WRITE.attributes(scheme).backgroundColor)
                    assertTrue(properties.isTrueValue(marker))
                } finally {
                    properties.setValue(marker, previous)
                }
            }
        }
    }

    private fun withSchemes(operation: () -> Unit) {
        val base = colors.globalScheme
        val before = colors.allSchemes.toSet()
        val oldMarker = properties.getValue(marker)
        try {
            properties.unsetValue(marker)
            operation()
        } finally {
            properties.setValue(marker, oldMarker)
            colors.setGlobalScheme(base)
            colors.allSchemes.filterNot { it in before }.forEach(colors::removeScheme)
        }
    }

    private fun event(
        action: AnAction,
        data: DataContext = SimpleDataContext.getSimpleContext(CommonDataKeys.PROJECT, project),
    ) = AnActionEvent.createEvent(action, data, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)

    private fun withDialog(dialog: TestDialog, operation: () -> Unit) {
        val previous = TestDialogManager.setTestDialog(dialog)
        try {
            operation()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }
}
