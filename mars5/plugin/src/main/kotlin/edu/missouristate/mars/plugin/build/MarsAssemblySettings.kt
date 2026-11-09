package edu.missouristate.mars.plugin.build

import com.intellij.DynamicBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.projectFilePath
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(name = "MarsAssemblySettings", storages = [Storage("mars.xml")])
internal class MarsAssemblySettings(private val project: Project) :
    SerializablePersistentStateComponent<MarsAssemblySettings.Settings>(Settings()) {
    data class Settings(
        @JvmField var exceptionHandler: String? = null,
        @JvmField @XCollection(style = XCollection.Style.v2) var sources: List<String> = emptyList(),
        @JvmField var assembleBeforeJvmCompile: Boolean = false,
    )

    val sourceChanges = SimpleModificationTracker()
    val exceptionHandler get() = projectFilePath(project, getState().exceptionHandler)
    val assembleBeforeJvmCompile get() = getState().assembleBeforeJvmCompile
    val projectSources: List<Path> get() = getState().sources.mapNotNull { projectFilePath(project, it) }.distinct()
    fun projectSourceSet(): MarsSourceSet? = projectSources.takeIf { it.isNotEmpty() }
        ?.let { MarsSourceSet(it, MarsAssemblyMode.PROJECT) }

    override fun loadState(state: Settings) {
        super.loadState(state)
        sourceChanges.incModificationCount()
    }

    fun changeExceptionHandler(value: String) {
        val path = projectFilePath(project, value.trim())
        val stored = PathMacroManager.getInstance(project).collapsePath(path?.toString())
        if (stored == getState().exceptionHandler) return
        change { it.copy(exceptionHandler = stored) }
    }

    fun changeSources(paths: List<String>) {
        val macros = PathMacroManager.getInstance(project)
        val stored = paths.mapNotNull { macros.collapsePath(it) }.distinct()
        if (stored == getState().sources) return
        change { it.copy(sources = stored) }
    }

    fun changeJvmCompile(value: Boolean) {
        updateState { it.copy(assembleBeforeJvmCompile = value) }
    }

    private fun change(transform: (Settings) -> Settings) {
        runWriteAction {
            updateState(transform)
            sourceChanges.incModificationCount()
        }
        DaemonCodeAnalyzer.getInstance(project).restart("MARS assembly settings changed")
        project.messageBus.syncPublisher(MipsAssemblyContextListener.TOPIC).assemblyEntryChanged()
    }
}

private val messages = DynamicBundle(MarsAssemblyConfigurable::class.java, "messages.MarsBundle")

internal class MarsAssemblyConfigurable(private val project: Project) :
    BoundConfigurable(messages.getMessage("build.settings.title")) {
    override fun apply() {
        val invalid = (createComponent() as DialogPanel).validateAll().firstOrNull()
        if (invalid != null) throw ConfigurationException(invalid.message)
        super.apply()
    }

    override fun createPanel() = panel {
        val settings = project.service<MarsAssemblySettings>()
        val rows = CollectionListModel<String>()
        val files = JBList(rows).apply {
            visibleRowCount = 5
            emptyText.text = messages.getMessage("build.settings.sources.empty")
        }
        val sourceChooser = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle(messages.getMessage("build.settings.sources.browse"))
            .withExtensionFilter(MipsFileType)
            .withEnvironmentRestricted(true)
        val sources = ToolbarDecorator.createDecorator(files, rows).setAddAction {
            FileChooser.chooseFiles(sourceChooser, project, files, null) { selected ->
                val localSources = selected.filter { it.isLocalSource() && it.fileType == MipsFileType }
                val combined = (rows.items + localSources.map { it.path }).distinct()
                rows.replaceAll(combined)
                files.selectedIndex = combined.lastIndex
            }
        }.createPanel()
        row(messages.getMessage("build.settings.sources")) {
            cell(sources).align(AlignX.FILL).comment(messages.getMessage("build.settings.sources.help"))
                .onReset { rows.replaceAll(settings.projectSources.map(Path::toString)) }
                .onIsModified { rows.items != settings.projectSources.map(Path::toString) }
                .onApply { settings.changeSources(rows.items) }
        }
        row {
            checkBox(messages.getMessage("build.settings.compiler"))
                .bindSelected({ settings.assembleBeforeJvmCompile }, settings::changeJvmCompile)
                .comment(messages.getMessage("build.settings.compiler.help"))
        }
        val chooser = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(messages.getMessage("build.settings.handler.browse"))
        row(messages.getMessage("build.settings.handler")) {
            textFieldWithBrowseButton(chooser, project)
                .bindText({ settings.exceptionHandler?.toString().orEmpty() }, settings::changeExceptionHandler)
                .align(AlignX.FILL)
                .comment(messages.getMessage("build.settings.handler.help"))
                .validationOnApply {
                    val value = it.text.trim()
                    if (value.isNotEmpty() && projectFilePath(project, value) == null) {
                        error(messages.getMessage("build.settings.handler.invalid"))
                    } else null
                }
        }
    }
}
