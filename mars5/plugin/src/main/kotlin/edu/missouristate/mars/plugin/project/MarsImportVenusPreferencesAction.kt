package edu.missouristate.mars.plugin.project

import com.intellij.DynamicBundle
import com.intellij.application.options.CodeStyle
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil.escapeLineBreak
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsInputMode
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunOptions
import edu.missouristate.mars.plugin.run.MarsRunType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

private val messages = DynamicBundle(MarsImportVenusPreferencesAction::class.java, "messages.MarsBundle")
private const val importedKey = "mars.venus.project.preferences.imported"
private const val templateImportedKey = "mars.venus.run.template.imported"

internal class MarsImportVenusPreferencesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !PropertiesComponent.getInstance(project).isTrueValue(templateImportedKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = PropertiesComponent.getInstance(project)
        if (properties.isTrueValue(templateImportedKey)) return
        val title = messages.getMessage("import.venus.title")
        val legacy = try {
            runWithModalProgressBlocking(ModalTaskOwner.project(project), title) {
                withContext(Dispatchers.IO) { readVenusPreferences(preferences()) }
            }
        } catch (_: BackingStoreException) {
            Messages.showErrorDialog(project, messages.getMessage("import.venus.read.error"), title)
            return
        } catch (_: SecurityException) {
            Messages.showErrorDialog(project, messages.getMessage("import.venus.read.error"), title)
            return
        }
        val assembly = project.service<MarsAssemblySettings>()
        val factory = runConfigurationType<MarsRunType>()
        val runManager = RunManager.getInstance(project)
        fun templateOptions() = (runManager.getConfigurationTemplate(factory).configuration as MarsRunConfiguration).options
        val previous = VenusImportSettings(assembly.getState(), projectTabSize(project), RunDefaults(templateOptions()))
        val preview = legacy.preview(project, previous, includeProject = !properties.isTrueValue(importedKey))
        if (preview.changes.isEmpty()) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.empty"), title)
            return
        }
        val text = messages.getMessage("import.venus.preview", preview.changes.joinToString("\n"))
        val html = "<html>" + escapeXmlEntities(text).replace("\n", "<br>") + "</html>"
        val confirmed = MessageDialogBuilder.okCancel(title, html)
            .yesText(messages.getMessage("import.venus.apply"))
            .ask(project)
        if (!confirmed) return
        val template = templateOptions()
        if (VenusImportSettings(assembly.getState(), projectTabSize(project), RunDefaults(template)) != previous) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.stale"), title)
            return
        }

        val updated = preview.settings
        if (updated.tabSize != previous.tabSize) {
            val manager = CodeStyleSettingsManager.getInstance(project)
            val style = manager.cloneSettings(CodeStyle.getSettings(project))
            style.getIndentOptions(MipsFileType).TAB_SIZE = updated.tabSize
            CodeStyle.setMainProjectSettings(project, style)
            manager.notifyCodeStyleSettingsChanged()
        }
        assembly.applyImportedSettings(updated.assembly)
        template.assembleDirectory = updated.run.assembleDirectory
        template.inputMode = updated.run.inputMode
        properties.setValue(importedKey, true)
        properties.setValue(templateImportedKey, true)
    }

    private fun projectTabSize(project: Project): Int = CodeStyle.getSettings(project).getIndentOptions(MipsFileType).TAB_SIZE
}

private data class RunDefaults(val assembleDirectory: Boolean, val inputMode: MarsInputMode) {
    constructor(options: MarsRunOptions) : this(options.assembleDirectory, options.inputMode)
}

private data class VenusImportSettings(
    val assembly: MarsAssemblySettings.Settings,
    val tabSize: Int,
    val run: RunDefaults,
)

private data class VenusImportPreview(
    val settings: VenusImportSettings,
    val changes: List<String>,
)

private class VenusPreferences(private val values: Map<String, String>, private val handler: Path?) {
    fun preview(project: Project, previous: VenusImportSettings, includeProject: Boolean): VenusImportPreview {
        val changes = mutableListOf<String>()
        fun change(label: String, before: String, after: String) {
            if (before != after) {
                changes += messages.getMessage("import.venus.change", messages.getMessage(label), escapeLineBreak(before), escapeLineBreak(after))
            }
        }
        fun flag(key: String, label: String, current: Boolean): Boolean {
            val value = values[key].venusBoolean() ?: current
            change(label, flagText(current), flagText(value))
            return value
        }
        val updated = if (includeProject) {
            val assembly = projectAssembly(project, previous.assembly, ::change, ::flag)
            val tabSize = values["EditorTabSize"]?.toIntOrNull()?.takeIf { it in 1..32 } ?: previous.tabSize
            change("import.venus.tab.width", previous.tabSize.toString(), tabSize.toString())
            previous.copy(assembly = assembly, tabSize = tabSize)
        } else previous
        val run = previous.run
        val assembleDirectory = flag("AssembleAll", "import.venus.run.directory", run.assembleDirectory)
        val inputMode = values["PopupSyscallInput"].venusBoolean()?.let {
            if (it) MarsInputMode.DIALOG else MarsInputMode.CONSOLE
        } ?: run.inputMode
        change("import.venus.run.input", run.inputMode.title, inputMode.title)
        return VenusImportPreview(updated.copy(run = RunDefaults(assembleDirectory, inputMode)), changes)
    }

    private fun projectAssembly(
        project: Project,
        assembly: MarsAssemblySettings.Settings,
        change: (String, String, String) -> Unit,
        flag: (String, String, Boolean) -> Boolean,
    ): MarsAssemblySettings.Settings {
        val oldLayout = MarsMemoryLayout.fromIdentifier(assembly.memoryLayout)
        val layout = MarsMemoryLayout.entries.firstOrNull { it.identifier == values["MemoryConfiguration"] }
            ?: oldLayout
        change("import.venus.memory", messages.getMessage(oldLayout.messageKey), messages.getMessage(layout.messageKey))

        val macros = PathMacroManager.getInstance(project)
        val handlerSetting = if (values["LoadExceptionHandler"].venusBoolean() == false) {
            null
        } else {
            handler?.let { macros.collapsePath(it.toString()) } ?: assembly.exceptionHandler
        }
        val disabled = messages.getMessage("import.venus.disabled")
        change("import.venus.handler", macros.expandPath(assembly.exceptionHandler) ?: disabled, macros.expandPath(handlerSetting) ?: disabled)

        return assembly.copy(
            allowPseudoInstructions = flag("ExtendedAssembler", "build.settings.pseudo", assembly.allowPseudoInstructions),
            bareMachine = flag("BareMachine", "run.bare.machine", assembly.bareMachine),
            assembleOnOpen = flag("AssembleOnOpen", "build.settings.open", assembly.assembleOnOpen),
            delayedBranching = flag("DelayedBranching", "run.delayed.branching", assembly.delayedBranching),
            warningsAreErrors = flag("WarningsAreErrors", "build.settings.warnings", assembly.warningsAreErrors),
            startAtMain = flag("StartAtMain", "run.start.at.main", assembly.startAtMain),
            selfModifyingCode = flag("SelfModifyingCode", "run.self.modifying.code", assembly.selfModifyingCode),
            memoryLayout = if (layout == oldLayout) assembly.memoryLayout else layout.identifier,
            exceptionHandler = handlerSetting,
        )
    }
}

private fun flagText(value: Boolean) = messages.getMessage(if (value) "import.venus.enabled" else "import.venus.disabled")

private fun String?.venusBoolean(): Boolean? = this?.lowercase()?.toBooleanStrictOrNull()

private val importKeys = listOf(
    "ExtendedAssembler", "BareMachine", "AssembleOnOpen", "DelayedBranching", "WarningsAreErrors", "StartAtMain",
    "SelfModifyingCode", "MemoryConfiguration", "LoadExceptionHandler", "ExceptionHandler", "EditorTabSize",
    "AssembleAll", "PopupSyscallInput",
)

private fun readVenusPreferences(root: Preferences): VenusPreferences {
    val values = listOf("edu/missouristate/mars", "mars").firstNotNullOfOrNull { path ->
        if (!root.nodeExists(path)) return@firstNotNullOfOrNull null
        val node = root.node(path)
        importKeys.mapNotNull { key -> node.get(key, null)?.let { key to it } }.toMap().takeIf { it.isNotEmpty() }
    }.orEmpty()
    val handler = if (values["LoadExceptionHandler"].venusBoolean() == true) {
        values["ExceptionHandler"]?.let(::existingHandler)
    } else null
    return VenusPreferences(values, handler)
}

private fun existingHandler(value: String): Path? = try {
    Path.of(value).normalize().takeIf { it.isAbsolute && Files.isRegularFile(it) }
} catch (_: InvalidPathException) {
    null
}
