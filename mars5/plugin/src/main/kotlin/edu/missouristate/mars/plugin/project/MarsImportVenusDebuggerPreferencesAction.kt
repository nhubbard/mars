package edu.missouristate.mars.plugin.project

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import edu.missouristate.mars.plugin.debug.MarsDebuggerDisplaySettings
import edu.missouristate.mars.plugin.debug.MarsDisassemblyColumn
import edu.missouristate.mars.plugin.debug.MarsSymbolColumn
import edu.missouristate.mars.plugin.debug.MarsSymbolSort
import edu.missouristate.mars.plugin.debug.importDisassemblyOrder
import edu.missouristate.mars.plugin.debug.importedDisassemblyOrder
import edu.missouristate.mars.plugin.debug.importSymbolSort
import edu.missouristate.mars.plugin.debug.symbolSort
import edu.missouristate.mars.plugin.debug.venusDisassemblyOrder
import java.util.prefs.Preferences

private const val numbersImportedKey = "mars.venus.debugger.numbers.imported"
private const val symbolsImportedKey = "mars.venus.debugger.symbols.imported"
private const val layoutImportedKey = "mars.venus.debugger.disassembly.imported"
private val messages get() = venusImportMessages
private val debuggerKeys = listOf("DisplayAddressesInHex", "DisplayValuesInHex", "LabelSortState", "TextColumnOrder")

internal class MarsImportVenusDebuggerPreferencesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val applicationProperties = PropertiesComponent.getInstance()
        e.presentation.isEnabledAndVisible = project != null && (
            !applicationProperties.isTrueValue(numbersImportedKey) ||
                !applicationProperties.isTrueValue(layoutImportedKey) ||
                !PropertiesComponent.getInstance(project).isTrueValue(symbolsImportedKey)
            )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val applicationProperties = PropertiesComponent.getInstance()
        val projectProperties = PropertiesComponent.getInstance(project)
        val numbersImported = applicationProperties.isTrueValue(numbersImportedKey)
        val symbolsImported = projectProperties.isTrueValue(symbolsImportedKey)
        val layoutImported = applicationProperties.isTrueValue(layoutImportedKey)
        if (numbersImported && symbolsImported && layoutImported) return
        val title = messages.getMessage("import.venus.debug.title")
        val values = loadVenusPreferences(project, title) { preferences().readSavedVenusPreferences(debuggerKeys) } ?: return

        val display = service<MarsDebuggerDisplaySettings>()
        val previousDisplay = display.getState()
        val previousSort = projectProperties.symbolSort()
        val previousOrder = applicationProperties.importedDisassemblyOrder()
        val updatedDisplay = if (!numbersImported) previousDisplay.copy(
            hexAddresses = values["DisplayAddressesInHex"].venusBoolean() ?: previousDisplay.hexAddresses,
            hexValues = values["DisplayValuesInHex"].venusBoolean() ?: previousDisplay.hexValues,
        ) else previousDisplay
        val updatedSort = if (!symbolsImported) venusSymbolSort(values["LabelSortState"]) ?: previousSort else previousSort
        val updatedOrder = if (layoutImported) null else venusDisassemblyOrder(values["TextColumnOrder"]).takeUnless { it == previousOrder }
        val changes = buildList {
            fun change(label: String, before: String, after: String) {
                if (before != after) add(messages.getMessage("import.venus.change", messages.getMessage(label), before, after))
            }
            change("import.venus.debug.addresses", numberFormat(previousDisplay.hexAddresses), numberFormat(updatedDisplay.hexAddresses))
            change("import.venus.debug.values", numberFormat(previousDisplay.hexValues), numberFormat(updatedDisplay.hexValues))
            change("import.venus.debug.symbols", sortDescription(previousSort), sortDescription(updatedSort))
            updatedOrder?.let { order ->
                add(messages.getMessage("import.venus.debug.layout", order.joinToString(" → ", transform = MarsDisassemblyColumn::title)))
            }
        }
        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.debug.empty"), title)
            return
        }
        if (!confirmVenusImport(project, title, messages.getMessage("import.venus.debug.preview", changes.joinToString("\n")))) return
        if (display.getState() != previousDisplay || projectProperties.symbolSort() != previousSort ||
            applicationProperties.isTrueValue(numbersImportedKey) != numbersImported ||
            projectProperties.isTrueValue(symbolsImportedKey) != symbolsImported ||
            applicationProperties.isTrueValue(layoutImportedKey) != layoutImported ||
            applicationProperties.importedDisassemblyOrder() != previousOrder
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
        if (updatedOrder != null) {
            importDisassemblyOrder(updatedOrder)
            applicationProperties.setValue(layoutImportedKey, true)
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
