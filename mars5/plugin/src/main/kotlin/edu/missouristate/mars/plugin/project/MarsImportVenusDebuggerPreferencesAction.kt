package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import edu.missouristate.mars.plugin.debug.MarsDebuggerDisplaySettings
import edu.missouristate.mars.plugin.debug.MarsSymbolColumn
import edu.missouristate.mars.plugin.debug.MarsSymbolSort
import edu.missouristate.mars.plugin.debug.importSymbolSort
import edu.missouristate.mars.plugin.debug.symbolSort
import java.util.prefs.Preferences

private const val numbersImportedKey = "mars.venus.debugger.numbers.imported"
private const val symbolsImportedKey = "mars.venus.debugger.symbols.imported"
private val messages get() = venusImportMessages
private val debuggerKeys = listOf("DisplayAddressesInHex", "DisplayValuesInHex", "LabelSortState")

internal class MarsImportVenusDebuggerPreferencesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && (
            !PropertiesComponent.getInstance().isTrueValue(numbersImportedKey) ||
                !PropertiesComponent.getInstance(project).isTrueValue(symbolsImportedKey)
            )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val applicationProperties = PropertiesComponent.getInstance()
        val projectProperties = PropertiesComponent.getInstance(project)
        val numbersImported = applicationProperties.isTrueValue(numbersImportedKey)
        val symbolsImported = projectProperties.isTrueValue(symbolsImportedKey)
        if (numbersImported && symbolsImported) return
        val title = messages.getMessage("import.venus.debug.title")
        val values = loadVenusPreferences(project, title) { preferences().readSavedVenusPreferences(debuggerKeys) } ?: return

        val display = service<MarsDebuggerDisplaySettings>()
        val previousDisplay = display.getState()
        val previousSort = projectProperties.symbolSort()
        val updatedDisplay = if (!numbersImported) previousDisplay.copy(
            hexAddresses = values["DisplayAddressesInHex"].venusBoolean() ?: previousDisplay.hexAddresses,
            hexValues = values["DisplayValuesInHex"].venusBoolean() ?: previousDisplay.hexValues,
        ) else previousDisplay
        val updatedSort = if (!symbolsImported) venusSymbolSort(values["LabelSortState"]) ?: previousSort else previousSort
        val changes = buildList {
            fun change(label: String, before: String, after: String) {
                if (before != after) add(messages.getMessage("import.venus.change", messages.getMessage(label), before, after))
            }
            change("import.venus.debug.addresses", numberFormat(previousDisplay.hexAddresses), numberFormat(updatedDisplay.hexAddresses))
            change("import.venus.debug.values", numberFormat(previousDisplay.hexValues), numberFormat(updatedDisplay.hexValues))
            change("import.venus.debug.symbols", sortDescription(previousSort), sortDescription(updatedSort))
        }
        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.debug.empty"), title)
            return
        }
        if (!confirmVenusImport(project, title, messages.getMessage("import.venus.debug.preview", changes.joinToString("\n")))) return
        if (display.getState() != previousDisplay || projectProperties.symbolSort() != previousSort ||
            applicationProperties.isTrueValue(numbersImportedKey) != numbersImported ||
            projectProperties.isTrueValue(symbolsImportedKey) != symbolsImported
        ) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.debug.stale"), title)
            return
        }
        if (updatedDisplay != previousDisplay) {
            display.applyImportedSettings(updatedDisplay)
            applicationProperties.setValue(numbersImportedKey, true)
        }
        if (updatedSort != previousSort) {
            importSymbolSort(project, updatedSort)
            projectProperties.setValue(symbolsImportedKey, true)
        }
    }
}

// Venus stores the last direction of each column as well as the currently selected sort column.
private fun venusSymbolSort(value: String?): MarsSymbolSort? = when (value?.toIntOrNull()) {
    0, 2 -> MarsSymbolSort(MarsSymbolColumn.ADDRESS, false)
    1, 3 -> MarsSymbolSort(MarsSymbolColumn.ADDRESS, true)
    4, 5 -> MarsSymbolSort(MarsSymbolColumn.NAME, false)
    6, 7 -> MarsSymbolSort(MarsSymbolColumn.NAME, true)
    else -> null
}

private fun numberFormat(hex: Boolean) = messages.getMessage(if (hex) "import.venus.debug.hex" else "import.venus.debug.decimal")

private fun sortDescription(sort: MarsSymbolSort) = messages.getMessage(
    if (sort.descending) "import.venus.debug.sort.descending" else "import.venus.debug.sort.ascending", sort.column.title,
)
