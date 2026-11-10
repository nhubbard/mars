package edu.missouristate.mars.plugin.debug

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import edu.missouristate.mars.plugin.run.runMessages
import java.nio.file.Path
import java.util.Locale
import java.util.regex.Pattern
import javax.swing.JButton
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter
import javax.swing.table.TableStringConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class LoadedSymbols(symbols: AssemblySymbolMap, val sources: MarsExecutionSources) {
    val rows = buildList {
        for ((name, definition) in symbols.globals()) add(AssembledSymbol(name, definition, null))
        for ((unit, definitions) in symbols.locals()) {
            for ((name, definition) in definitions) add(AssembledSymbol(name, definition, unit))
        }
    }
}

internal data class AssembledSymbol(val name: String, val definition: Definition, val unit: Path?) {
    val location get() = definition.origin()?.location()
    val scope get() = when {
        definition.macroPrivate() -> "macro"
        unit != null -> "local"
        else -> "global"
    }
}

internal class MarsSymbolsView(
    private val project: Project,
    private val image: StateFlow<LoadedSymbols?>,
) : Disposable {
    private val display = service<MarsDebuggerDisplaySettings>()
    private val scope = CoroutineScope(Dispatchers.EDT)
    private val changes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply { tryEmit(Unit) }
    private val status = JBLabel(runMessages.getMessage("debug.symbols.waiting"))
    private val filter = SearchTextField(false).apply {
        textEditor.emptyText.text = runMessages.getMessage("debug.symbols.filter")
        textEditor.accessibleContext.accessibleName = runMessages.getMessage("debug.symbols.filter")
        textEditor.columns = 22
    }
    private val openSource = JButton(runMessages.getMessage("debug.disassembly.open.source")).apply { isEnabled = false }
    private val inspectAddress = JButton(runMessages.getMessage("debug.symbols.inspect")).apply { isEnabled = false }
    private val model = ListTableModel<AssembledSymbol>(
        column("name", AssembledSymbol::name),
        column("address", { display.formatAddress(it.definition.address()) }, compareBy { it.definition.address().toUInt() }),
        column("kind") { runMessages.getMessage("debug.symbols.kind.${it.definition.kind().name.lowercase(Locale.ROOT)}") },
        column("scope") { runMessages.getMessage("debug.symbols.scope.${it.scope}") },
        column("unit") { it.unit?.let(::displayPath).orEmpty() },
        column("definition") { it.location?.let { site -> "${displayPath(site.file())}:${site.line()}" }.orEmpty() },
    )
    private val table = TableView(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = runMessages.getMessage("debug.symbols.empty")
    }
    @Suppress("UNCHECKED_CAST") // JBTable creates a TableRowSorter for sortable ColumnInfo columns.
    private val sorter = table.rowSorter as TableRowSorter<TableModel>
    private var displayed: LoadedSymbols? = null

    val focus get() = table
    val component = panel {
        row {
            cell(filter)
            cell(openSource)
            cell(inspectAddress)
            cell(status)
        }
        row { scrollCell(table).align(Align.FILL) }.resizableRow()
    }

    fun bindAddressInspection(session: XDebugSession, inspect: (Definition) -> Unit) {
        fun update() {
            inspectAddress.isEnabled = table.selectedObject != null && session.isSuspended
        }
        table.selectionModel.addListSelectionListener { update() }
        inspectAddress.addActionListener { inspect(table.selectedObject!!.definition) }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = UIUtil.invokeLaterIfNeeded { update() }
            override fun sessionResumed() = UIUtil.invokeLaterIfNeeded { update() }
            override fun sessionStopped() = UIUtil.invokeLaterIfNeeded { update() }
        }, this)
        update()
    }

    init {
        val properties = PropertiesComponent.getInstance(project)
        val sortColumn = properties.getInt("mars.debug.symbols.sortColumn", 0).coerceIn(0, model.columnCount - 1)
        val sortOrder = if (properties.getBoolean("mars.debug.symbols.sortDescending")) SortOrder.DESCENDING else SortOrder.ASCENDING
        sorter.sortKeys = listOf(SortKey(sortColumn, sortOrder))
        sorter.addRowSorterListener {
            sorter.sortKeys.firstOrNull()?.let { key ->
                properties.setValue("mars.debug.symbols.sortColumn", key.column, 0)
                properties.setValue("mars.debug.symbols.sortDescending", key.sortOrder == SortOrder.DESCENDING)
            }
        }
        sorter.stringConverter = object : TableStringConverter() {
            override fun toString(model: TableModel, row: Int, column: Int) = model.getValueAt(row, column).toString()
        }
        display.addListener(this) {
            sorter.sort()
            table.repaint()
        }
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: SwingDocumentEvent) {
                sorter.rowFilter = RowFilter.regexFilter("(?iu)" + Pattern.quote(filter.text.trim()))
            }
        })
        table.selectionModel.addListSelectionListener { openSource.isEnabled = table.selectedObject?.location != null }
        openSource.addActionListener {
            val location = table.selectedObject!!.location!!
            displayed!!.sources.hyperlink(project, location).navigate(project)
        }

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val loaded = image.value ?: return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (loaded.sources.contains(file)) changes.tryEmit(Unit)
            }
        }, this)
        VirtualFileManager.getInstance().addAsyncFileListenerBackgroundable({
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() { changes.tryEmit(Unit) }
            }
        }, this)

        scope.launch {
            image.collectLatest { loaded ->
                if (loaded == null) return@collectLatest
                displayed = loaded
                model.items = loaded.rows
                changes.collectLatest {
                    val current = readAction { loaded.sources.isCurrent() }
                    status.text = runMessages.getMessage(if (current) "debug.symbols.loaded" else "debug.symbols.stale")
                }
            }
        }
    }

    private fun displayPath(path: Path): String {
        val base = project.basePath?.let(Path::of)
        return if (base != null && path.startsWith(base)) base.relativize(path).toString() else path.toString()
    }

    private fun column(key: String, value: (AssembledSymbol) -> String, order: Comparator<AssembledSymbol>) =
        object : ColumnInfo<AssembledSymbol, String>(runMessages.getMessage("debug.symbols.column.$key")) {
            override fun valueOf(item: AssembledSymbol) = value(item)
            override fun getComparator() = order
        }

    private fun column(key: String, value: (AssembledSymbol) -> String) = column(key, value, compareBy(value))

    override fun dispose() = scope.cancel()
}
