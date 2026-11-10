package edu.missouristate.mars.plugin.project

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.MipsFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException

@TestApplication
class MarsImportVenusPreferencesPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val settings get() = project.service<MarsAssemblySettings>()
    private val projectTabSize get() = CodeStyle.getSettings(project).getIndentOptions(MipsFileType).TAB_SIZE

    @Test
    fun `registered action requires a project and never imports without confirmation`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val action = assertInstanceOf(
                MarsImportVenusPreferencesAction::class.java, ActionManager.getInstance().getAction("Mars.ImportVenusPreferences"),
            )
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            val unavailable = event(action, DataContext.EMPTY_CONTEXT)
            action.update(unavailable)
            assertFalse(unavailable.presentation.isEnabledAndVisible)
            action.actionPerformed(unavailable)

            val before = settings.getState()
            val tabSize = projectTabSize
            val available = event(action)
            action.update(available)
            assertTrue(available.presentation.isEnabledAndVisible)
            // Exercise the production reader without assuming anything about the host's saved preferences.
            withDialog({ Messages.CANCEL }) { action.actionPerformed(available) }
            assertEquals(before, settings.getState())
            assertEquals(tabSize, projectTabSize)
        }
    }

    @Test
    fun `preview shows current and imported values and changes only the selected project once`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val handler = fixture.addFileToProject("handler & startup.s", ".ktext 0x80000180\nnop\n").virtualFile.toNioPath()
            CodeStyle.dropTemporarySettings(project)
            settings.changeJvmCompile(true)
            settings.changeSources(listOf(handler.toString()))
            val before = settings.getState()
            val initialTab = projectTabSize
            val globalTab = CodeStyle.getDefaultSettings().getIndentOptions(MipsFileType).TAB_SIZE
            val root = MemoryPreferences()
            root.node("mars").put("MemoryConfiguration", "CompactTextAtZero")
            val current = root.node("edu/missouristate/mars")
            mapOf(
                "ExtendedAssembler" to "False",
                "BareMachine" to "true",
                "AssembleOnOpen" to "true",
                "DelayedBranching" to "true",
                "WarningsAreErrors" to "true",
                "StartAtMain" to "true",
                "SelfModifyingCode" to "true",
                "MemoryConfiguration" to "CompactDataAtZero",
                "LoadExceptionHandler" to "true",
                "ExceptionHandler" to handler.toString(),
                "EditorTabSize" to "7",
                "EditorFontSize" to "20",
            ).forEach(current::put)
            val action = MarsImportVenusPreferencesAction { root }

            withDialog({ preview ->
                assertTrue(preview.contains("Allow pseudo-instructions  On → Off"))
                assertTrue(preview.contains("MIPS tab width (project code style)  $initialTab → 7"))
                assertTrue(preview.contains("handler &amp; startup.s"))
                assertFalse(preview.contains("EditorFontSize"))
                assertFalse(preview.contains("Compact, text at address 0"))
                Messages.CANCEL
            }) { action.actionPerformed(event(action)) }
            assertEquals(before, settings.getState())
            assertEquals(initialTab, projectTabSize)

            val generation = settings.sourceChanges.modificationCount
            withDialog(TestDialog.OK) { action.actionPerformed(event(action)) }
            val after = settings.getState()
            assertFalse(after.allowPseudoInstructions)
            assertTrue(after.bareMachine)
            assertTrue(after.assembleOnOpen)
            assertTrue(after.delayedBranching)
            assertTrue(after.warningsAreErrors)
            assertTrue(after.startAtMain)
            assertTrue(after.selfModifyingCode)
            assertEquals(MarsMemoryLayout.DATA_AT_ZERO, settings.memoryLayout)
            assertEquals(handler, settings.exceptionHandler)
            assertEquals(before.sources, after.sources)
            assertTrue(after.assembleBeforeJvmCompile)
            assertEquals(generation + 1, settings.sourceChanges.modificationCount)
            assertEquals(7, projectTabSize)
            assertEquals(globalTab, CodeStyle.getDefaultSettings().getIndentOptions(MipsFileType).TAB_SIZE)
            assertTrue(CodeStyle.usesOwnSettings(project))
            val repeated = event(action)
            action.update(repeated)
            assertFalse(repeated.presentation.isEnabledAndVisible)
            action.actionPerformed(repeated)
            assertEquals(after, settings.getState())
        }
    }

    @Test
    fun `classic Venus preferences can disable a handler and preserve unknown or unset settings`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val handler = fixture.addFileToProject("handler.asm", "nop\n").virtualFile.toNioPath()
            settings.changeExceptionHandler(handler.toString())
            settings.changeDelayedBranching(true)
            val root = MemoryPreferences()
            root.node("edu/missouristate/mars").put("EditorFontSize", "20")
            root.node("mars").apply {
                put("LoadExceptionHandler", "false")
                put("DelayedBranching", "invalid")
                put("EditorTabSize", "999")
                put("MemoryConfiguration", "Unknown")
            }
            val action = MarsImportVenusPreferencesAction { root }
            val before = settings.getState()
            val tabSize = projectTabSize
            withDialog({ preview ->
                assertTrue(preview.contains("Exception handler source  $handler → Off"))
                Messages.OK
            }) { action.actionPerformed(event(action)) }
            assertEquals(before.copy(exceptionHandler = null), settings.getState())
            assertEquals(tabSize, projectTabSize)
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["relative.s", "\u0000", "missing"])
    fun `unavailable handler values do not replace a configured handler`(source: String?) = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val handler = fixture.addFileToProject("handler.asm", "nop\n").virtualFile.toNioPath()
            settings.changeExceptionHandler(handler.toString())
            val root = MemoryPreferences()
            val saved = root.node("mars") as MemoryPreferences
            saved.put("LoadExceptionHandler", "true")
            if (source != null) saved.entries["ExceptionHandler"] =
                if (source == "missing") handler.resolveSibling("missing.s").toString() else source
            saved.put("EditorTabSize", if (source == null) "invalid" else "0")
            val action = MarsImportVenusPreferencesAction { root }
            withDialog({ info ->
                assertTrue(info.contains("No saved Venus preferences differ"))
                Messages.OK
            }) { action.actionPerformed(event(action)) }
            assertEquals(handler, settings.exceptionHandler)
            assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
        }
    }

    @Test
    fun `tab-only imports refresh native MIPS code style without changing assembly`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            CodeStyle.dropTemporarySettings(project)
            val root = MemoryPreferences()
            root.node("mars").putInt("EditorTabSize", 6)
            val action = MarsImportVenusPreferencesAction { root }
            val generation = settings.sourceChanges.modificationCount
            withDialog(TestDialog.OK) { action.actionPerformed(event(action)) }
            assertEquals(6, projectTabSize)
            assertEquals(generation, settings.sourceChanges.modificationCount)
        }
    }

    @Test
    fun `confirmation stops when assembly settings or code style changed since the preview`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            CodeStyle.dropTemporarySettings(project)
            val root = MemoryPreferences()
            root.node("mars").putBoolean("DelayedBranching", true)
            val action = MarsImportVenusPreferencesAction { root }
            val state = settings.getState()
            for (edit in listOf(
                { settings.changeJvmCompile(true) },
                {
                    val style = CodeStyleSettingsManager.getInstance(project).cloneSettings(CodeStyle.getSettings(project))
                    style.getIndentOptions(MipsFileType).TAB_SIZE = 6
                    CodeStyle.setMainProjectSettings(project, style)
                },
            )) {
                var dialogCount = 0
                withDialog({ message ->
                    if (dialogCount++ == 0) {
                        assertTrue(message.contains("Enable delayed branching  Off → On"))
                        edit()
                    } else {
                        assertTrue(message.contains("Project settings changed"))
                    }
                    Messages.OK
                }) { action.actionPerformed(event(action)) }
                assertEquals(2, dialogCount)
                assertFalse(settings.delayedBranching)
                assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
                settings.loadState(state)
            }
        }
    }

    @Test
    fun `missing preferences and read failures are reported without claiming the import`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val empty = MarsImportVenusPreferencesAction { MemoryPreferences() }
            withDialog({ info ->
                assertTrue(info.contains("No saved Venus preferences differ"))
                Messages.OK
            }) { empty.actionPerformed(event(empty)) }
            for (failure in listOf(BackingStoreException("read failed"), SecurityException("denied"))) {
                val action = MarsImportVenusPreferencesAction { throw failure }
                withDialog({ error ->
                    assertEquals("Could not read local Venus preferences.", error)
                    Messages.OK
                }) { action.actionPerformed(event(action)) }
                assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
            }
        }
    }

    private fun event(
        action: MarsImportVenusPreferencesAction,
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

    private class MemoryPreferences(parent: MemoryPreferences? = null, name: String = "") : AbstractPreferences(parent, name) {
        val entries = mutableMapOf<String, String>()
        private val children = mutableMapOf<String, MemoryPreferences>()

        override fun putSpi(key: String, value: String) { entries[key] = value }
        override fun getSpi(key: String): String? = entries[key]
        override fun removeSpi(key: String) { entries.remove(key) }
        override fun removeNodeSpi() { (parent() as MemoryPreferences).children.remove(name()) }
        override fun keysSpi() = entries.keys.toTypedArray()
        override fun childrenNamesSpi() = children.keys.toTypedArray()
        override fun childSpi(name: String) = children.getOrPut(name) { MemoryPreferences(this, name) }
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
    }
}
