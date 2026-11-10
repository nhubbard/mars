package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.debug.MarsDebuggerDisplaySettings
import edu.missouristate.mars.plugin.debug.MarsSymbolColumn
import edu.missouristate.mars.plugin.debug.MarsSymbolSort
import edu.missouristate.mars.plugin.debug.MarsSymbolsView
import edu.missouristate.mars.plugin.debug.storeSymbolSort
import edu.missouristate.mars.plugin.debug.symbolSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.prefs.BackingStoreException
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder

@TestApplication
class MarsImportVenusDebuggerPreferencesPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val project get() = projectFixture.get()
    private val projectProperties get() = PropertiesComponent.getInstance(project)
    private val appProperties get() = PropertiesComponent.getInstance()
    private val display get() = service<MarsDebuggerDisplaySettings>()
    private val numberMarker = "mars.venus.debugger.numbers.imported"
    private val sortMarker = "mars.venus.debugger.symbols.imported"

    @Test
    fun `debugger import previews both scopes and updates open Symbols views in the current project`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withDefaults {
                val registered = assertInstanceOf(MarsImportVenusDebuggerPreferencesAction::class.java,
                    ActionManager.getInstance().getAction("Mars.ImportVenusDebuggerPreferences"))
                assertEquals(ActionUpdateThread.BGT, registered.actionUpdateThread)
                val noProject = event(registered, DataContext.EMPTY_CONTEXT)
                assertFalse(noProject.also(registered::update).presentation.isEnabledAndVisible)
                registered.actionPerformed(noProject)
                withDialog({ Messages.CANCEL }) { registered.actionPerformed(event(registered)) }
                val root = MemoryPreferences()
                root.node("mars").apply {
                    putBoolean("DisplayAddressesInHex", false)
                    putBoolean("DisplayValuesInHex", false)
                    put("LabelSortState", "3")
                }
                val action = MarsImportVenusDebuggerPreferencesAction { root }
                val view = MarsSymbolsView(project, MutableStateFlow(null))
                val other = MarsSymbolsView(ProjectManager.getInstance().defaultProject, MutableStateFlow(null))
                val lifetime = Disposer.newDisposable()
                Disposer.register(lifetime, view)
                Disposer.register(lifetime, other)
                try {
                    var displayChanges = 0
                    display.addListener(lifetime) { displayChanges++ }
                    val otherSort = other.focus.rowSorter.sortKeys
                    assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
                    withDialog({ preview ->
                        assertTrue(preview.contains("Address format (all MARS projects)  Hexadecimal → Decimal"))
                        assertTrue(preview.contains("Value format (all MARS projects)  Hexadecimal → Decimal"))
                        assertTrue(preview.contains("Symbols sorting (this project)  Label ascending → Address descending"))
                        Messages.CANCEL
                    }) { action.actionPerformed(event(action)) }
                    assertEquals(listOf(SortKey(0, SortOrder.ASCENDING)), view.focus.rowSorter.sortKeys)
                    assertEquals(0, displayChanges)

                    withDialog(TestDialog.OK) { action.actionPerformed(event(action)) }
                    assertEquals(listOf(SortKey(1, SortOrder.DESCENDING)), view.focus.rowSorter.sortKeys)
                    assertEquals(otherSort, other.focus.rowSorter.sortKeys)
                    assertEquals("4294967295", display.formatAddress(-1))
                    assertEquals("-1", display.formatValue(-1))
                    assertEquals(1, displayChanges)
                    assertTrue(appProperties.isTrueValue(numberMarker))
                    assertTrue(projectProperties.isTrueValue(sortMarker))
                    assertFalse(event(action).also(action::update).presentation.isEnabledAndVisible)
                    action.actionPerformed(event(action))
                } finally {
                    Disposer.dispose(lifetime)
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "0, ADDRESS, false", "1, ADDRESS, true", "2, ADDRESS, false", "3, ADDRESS, true",
        "4, NAME, false", "5, NAME, false", "6, NAME, true", "7, NAME, true",
    )
    fun `Venus sort state restores the matching primary column and direction`(state: Int, column: String, descending: Boolean) =
        timeoutRunBlocking<Unit> {
            withContext(Dispatchers.EDT) {
                withDefaults {
                    appProperties.setValue(numberMarker, true)
                    projectProperties.storeSymbolSort(MarsSymbolSort(MarsSymbolColumn.KIND, false))
                    val root = MemoryPreferences()
                    root.node("edu/missouristate/mars").apply {
                        put("LabelSortState", state.toString())
                        putBoolean("DisplayAddressesInHex", false)
                    }
                    val action = MarsImportVenusDebuggerPreferencesAction { root }
                    assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
                    withDialog({ preview ->
                        assertFalse(preview.contains("Address format"))
                        Messages.OK
                    }) { action.actionPerformed(event(action)) }
                    assertEquals(MarsSymbolSort(MarsSymbolColumn.valueOf(column), descending), projectProperties.symbolSort())
                    assertTrue(projectProperties.isTrueValue(sortMarker))
                    assertTrue(display.hexAddresses)
                }
            }
        }

    @Test
    fun `numeric-only imports preserve the project sort and ignore invalid or omitted values`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withDefaults {
                val root = MemoryPreferences()
                val saved = root.node("mars")
                saved.put("LabelSortState", "9")
                saved.put("DisplayValuesInHex", "invalid")
                val action = MarsImportVenusDebuggerPreferencesAction { root }
                withDialog({ info ->
                    assertTrue(info.contains("No saved Venus debugger preferences differ"))
                    Messages.OK
                }) { action.actionPerformed(event(action)) }
                assertFalse(appProperties.isTrueValue(numberMarker))
                assertFalse(projectProperties.isTrueValue(sortMarker))
                projectProperties.setValue(sortMarker, true)
                saved.put("LabelSortState", "1")
                saved.putBoolean("DisplayAddressesInHex", false)
                withDialog({ preview ->
                    assertFalse(preview.contains("Symbols sorting"))
                    Messages.OK
                }) { action.actionPerformed(event(action)) }
                assertFalse(display.hexAddresses)
                assertTrue(display.hexValues)
                assertEquals(MarsSymbolSort(MarsSymbolColumn.NAME, false), projectProperties.symbolSort())
            }
        }
    }

    @Test
    fun `debugger import detects stale settings and leaves the action available after read errors`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            withDefaults {
                val unreadable = MarsImportVenusDebuggerPreferencesAction { throw BackingStoreException("unavailable") }
                withDialog({ info ->
                    assertEquals("Could not read local Venus preferences.", info)
                    Messages.OK
                }) { unreadable.actionPerformed(event(unreadable)) }
                val root = MemoryPreferences()
                root.node("mars").putBoolean("DisplayAddressesInHex", false)
                val action = MarsImportVenusDebuggerPreferencesAction { root }
                for (edit in listOf(
                    { display.setHexValues(false) },
                    { projectProperties.storeSymbolSort(MarsSymbolSort(MarsSymbolColumn.KIND, true)) },
                    { appProperties.setValue(numberMarker, true) },
                    { projectProperties.setValue(sortMarker, true) },
                )) {
                    var count = 0
                    withDialog({ message ->
                        if (count++ == 0) edit() else assertTrue(message.contains("Debugger display settings changed"))
                        Messages.OK
                    }) { action.actionPerformed(event(action)) }
                    assertEquals(2, count)
                    assertTrue(display.hexAddresses)
                    resetDefaults()
                }
                assertTrue(event(action).also(action::update).presentation.isEnabledAndVisible)
            }
        }
    }

    private fun withDefaults(operation: () -> Unit) {
        val oldNumbers = appProperties.getValue(numberMarker)
        val oldSortMarker = projectProperties.getValue(sortMarker)
        val oldSort = projectProperties.symbolSort()
        val oldDisplay = display.getState()
        try {
            resetDefaults()
            operation()
        } finally {
            appProperties.setValue(numberMarker, oldNumbers)
            projectProperties.setValue(sortMarker, oldSortMarker)
            projectProperties.storeSymbolSort(oldSort)
            display.loadState(oldDisplay)
        }
    }

    private fun resetDefaults() {
        appProperties.unsetValue(numberMarker)
        projectProperties.unsetValue(sortMarker)
        projectProperties.storeSymbolSort(MarsSymbolSort(MarsSymbolColumn.NAME, false))
        display.loadState(MarsDebuggerDisplaySettings.Settings())
    }

    private fun event(
        action: MarsImportVenusDebuggerPreferencesAction,
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
