package edu.missouristate.mars.plugin.project

import com.intellij.DynamicBundle
import com.intellij.application.options.CodeStyle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

private val messages = DynamicBundle(MarsImportVenusPreferencesAction::class.java, "messages.MarsBundle")
private const val importedKey = "mars.venus.project.preferences.imported"

internal class MarsImportVenusPreferencesAction(
    private val preferences: () -> Preferences = Preferences::userRoot,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !PropertiesComponent.getInstance(project).isTrueValue(importedKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = PropertiesComponent.getInstance(project)
        if (properties.isTrueValue(importedKey)) return
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
        val previous = assembly.getState()
        val previousTabSize = projectTabSize(project)
        val preview = legacy.preview(project, previous, previousTabSize)
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
        if (assembly.getState() != previous || projectTabSize(project) != previousTabSize) {
            Messages.showInfoMessage(project, messages.getMessage("import.venus.stale"), title)
            return
        }

        if (preview.tabSize != previousTabSize) {
            val manager = CodeStyleSettingsManager.getInstance(project)
            val style = manager.cloneSettings(CodeStyle.getSettings(project))
            style.getIndentOptions(MipsFileType).TAB_SIZE = preview.tabSize
            CodeStyle.setMainProjectSettings(project, style)
            manager.notifyCodeStyleSettingsChanged()
        }
        assembly.applyImportedSettings(preview.assembly)
        properties.setValue(importedKey, true)
    }

    private fun projectTabSize(project: Project): Int = CodeStyle.getSettings(project).getIndentOptions(MipsFileType).TAB_SIZE
}

private data class VenusImportPreview(
    val assembly: MarsAssemblySettings.Settings,
    val tabSize: Int,
    val changes: List<String>,
)

private class VenusPreferences(private val values: Map<String, String>, private val handler: Path?) {
    fun preview(project: Project, assembly: MarsAssemblySettings.Settings, tabSize: Int): VenusImportPreview {
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

        val updated = assembly.copy(
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
        val newTabSize = values["EditorTabSize"]?.toIntOrNull()?.takeIf { it in 1..32 } ?: tabSize
        change("import.venus.tab.width", tabSize.toString(), newTabSize.toString())
        return VenusImportPreview(updated, newTabSize, changes)
    }
}

private fun flagText(value: Boolean) = messages.getMessage(if (value) "import.venus.enabled" else "import.venus.disabled")

private fun String?.venusBoolean(): Boolean? = this?.lowercase()?.toBooleanStrictOrNull()

private val importKeys = listOf(
    "ExtendedAssembler", "BareMachine", "AssembleOnOpen", "DelayedBranching", "WarningsAreErrors", "StartAtMain",
    "SelfModifyingCode", "MemoryConfiguration", "LoadExceptionHandler", "ExceptionHandler", "EditorTabSize",
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
