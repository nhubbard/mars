package edu.missouristate.mars.plugin.build

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueIs
import edu.missouristate.mars.mips.dump.DumpFormat
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.util.Binary
import java.nio.file.Path
import javax.swing.JTextField

internal val memoryExportMessages = DynamicBundle(MarsMemoryExportAction::class.java, "messages.MarsBundle")

internal class MarsMemoryExportAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sourceFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = sourceFile(e) ?: return
        val path = entry.toNioPath()
        val (options, output) = chooseMemoryExport(project, path) ?: return
        project.service<MarsMemoryExportService>().exportInBackground(path, options, output)
    }

    private fun sourceFile(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf {
        it.isLocalSource() && it.fileType == MipsFileType
    }
}

internal data class MemoryExportSelection(val options: MemoryExportOptions, val output: Path)

internal fun chooseMemoryExport(
    project: Project, entry: Path?, existing: MemoryExportSelection? = null,
): MemoryExportSelection? {
    val dialog = MarsMemoryExportDialog(project, existing?.options)
    dialog.show()
    if (!dialog.isOK) return null

    val options = dialog.options()
    val descriptor = FileSaverDescriptor(
        memoryExportMessages.getMessage("export.save.title"), options.format.description.trim(),
    )
    options.format.fileExtension?.let { descriptor.withExtensionFilter(it) }
    val chooser = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
    val suggested = existing?.output ?: entry?.resolveSibling("${entry.fileName.toString().substringBeforeLast('.')}-memory")
    val output = chooser.save(suggested?.parent, suggested?.fileName?.toString() ?: "memory")?.file?.toPath() ?: return null
    return MemoryExportSelection(options, output)
}

internal enum class MemoryExportRegion(val label: String) {
    TEXT("export.region.text"),
    DATA("export.region.data"),
    RANGE("export.region.custom"),
}

internal data class MemoryExportOptions(
    val format: DumpFormat,
    val region: MemoryExportRegion,
    val range: UIntRange = UIntRange.EMPTY,
)

internal class MarsMemoryExportDialog(project: Project, existing: MemoryExportOptions?) : DialogWrapper(project) {
    private val messages = memoryExportMessages
    private lateinit var region: ComboBox<MemoryExportRegion>
    private lateinit var format: ComboBox<DumpFormat>
    private lateinit var first: JTextField
    private lateinit var last: JTextField

    val fields = panel {
        row(messages.getMessage("export.region")) {
            region = comboBox(MemoryExportRegion.entries, textListCellRenderer("") { messages.getMessage(it.label) }).component
        }
        row(messages.getMessage("export.format")) {
            format = comboBox(DumpFormatLoader.FORMATS).component
        }
        row(messages.getMessage("export.first")) {
            first = textField().columns(16).component
        }.visibleIf(region.selectedValueIs(MemoryExportRegion.RANGE))
        row(messages.getMessage("export.last")) {
            last = textField().columns(16).component
        }.visibleIf(region.selectedValueIs(MemoryExportRegion.RANGE))
    }

    init {
        if (existing != null) {
            region.item = existing.region
            format.item = existing.format
            if (existing.region == MemoryExportRegion.RANGE) {
                first.text = Binary.intToHexString(existing.range.first.toInt())
                last.text = Binary.intToHexString(existing.range.last.toInt())
            }
        }
        title = messages.getMessage("export.title")
        init()
    }

    override fun createCenterPanel() = fields

    override fun doValidate(): ValidationInfo? {
        if (region.item != MemoryExportRegion.RANGE) return null
        val start = parseAddress(first.text) ?: return ValidationInfo(messages.getMessage("export.address.invalid"), first)
        val end = parseAddress(last.text) ?: return ValidationInfo(messages.getMessage("export.address.invalid"), last)
        if (start > end) return ValidationInfo(messages.getMessage("export.address.order"), last)
        return null
    }

    fun options(): MemoryExportOptions {
        val range = if (region.item == MemoryExportRegion.RANGE) {
            parseAddress(first.text)!!..parseAddress(last.text)!!
        } else {
            UIntRange.EMPTY
        }
        return MemoryExportOptions(format.item, region.item, range)
    }

    private fun parseAddress(input: String): UInt? {
        val text = input.trim()
        val address = if (text.startsWith("0x", ignoreCase = true)) text.drop(2).toUIntOrNull(16) else text.toUIntOrNull()
        return address?.takeIf { MemoryState.wordAligned(it.toInt()) }
    }
}
