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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.projectFilePath
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path
import javax.swing.JCheckBox
import kotlin.io.path.invariantSeparatorsPathString

private const val MAX_BACKSTEP_LIMIT = 100_000

private fun VirtualFile.isProjectAssemblySource() = isLocalSource() && !fileType.isBinary

@Service(Service.Level.PROJECT)
@State(name = "MarsAssemblySettings", storages = [Storage("mars.xml")])
internal class MarsAssemblySettings(private val project: Project) :
    SerializablePersistentStateComponent<MarsAssemblySettings.Settings>(Settings()) {
    data class Settings(
        @JvmField var exceptionHandler: String? = null,
        @JvmField @XCollection(style = XCollection.Style.v2) var sources: List<String> = emptyList(),
        @JvmField var assembleBeforeJvmCompile: Boolean = false,
        @JvmField var assembleOnOpen: Boolean = false,
        @JvmField var allowPseudoInstructions: Boolean = true,
        @JvmField var warningsAreErrors: Boolean = false,
        @JvmField var startAtMain: Boolean = false,
        @JvmField var memoryLayout: String = MarsMemoryLayout.DEFAULT.identifier,
        @JvmField var delayedBranching: Boolean = false,
        @JvmField var selfModifyingCode: Boolean = false,
        @JvmField var bareMachine: Boolean = false,
        @JvmField var assemblerErrorLimit: Int = ApplicationInfo.maximumErrorMessages,
        @JvmField var backstepLimit: Int = ApplicationInfo.maximumBacksteps,
    )

    val sourceChanges = SimpleModificationTracker()
    val exceptionHandler get() = projectFilePath(project, getState().exceptionHandler)
    val assembleBeforeJvmCompile get() = getState().assembleBeforeJvmCompile
    val assembleOnOpen get() = getState().assembleOnOpen
    val startAtMain get() = getState().startAtMain
    val delayedBranching get() = getState().delayedBranching
    val selfModifyingCode get() = getState().selfModifyingCode
    val bareMachine get() = getState().bareMachine
    val memoryLayout get() = MarsMemoryLayout.fromIdentifier(getState().memoryLayout)
    val assemblerErrorLimit get() = getState().assemblerErrorLimit.takeIf { it > 0 } ?: ApplicationInfo.maximumErrorMessages
    val backstepLimit get() = getState().backstepLimit.takeIf { it in 1..MAX_BACKSTEP_LIMIT } ?: ApplicationInfo.maximumBacksteps
    val executionOptions get() = getState().let {
        MarsExecutionOptions(
            delayedBranching = it.delayedBranching,
            selfModifyingCode = it.selfModifyingCode,
            allowPseudoInstructions = it.allowPseudoInstructions,
            warningsAreErrors = it.warningsAreErrors,
            memoryLayout = MarsMemoryLayout.fromIdentifier(it.memoryLayout),
            bareMachine = it.bareMachine,
            assemblerErrorLimit = assemblerErrorLimit,
        )
    }
    val projectSources: List<Path> get() = getState().sources.mapNotNull { projectFilePath(project, it) }.distinct()
    fun projectSourceSet(): MarsSourceSet? = projectSources.takeIf { it.isNotEmpty() }
        ?.let { MarsSourceSet(it, MarsAssemblyMode.PROJECT) }

    internal data class SourcePaths(val sources: List<String>, val handler: String?)

    internal val sourcePaths get() = getState().let { SourcePaths(it.sources, it.exceptionHandler) }

    internal fun replaceSourcePaths(from: SourcePaths, to: SourcePaths) {
        val current = sourcePaths
        val sources = if (current.sources == from.sources) {
            to.sources
        } else {
            // Undo can run after the user added, removed, or reordered other project inputs.
            val candidates = from.sources.zip(to.sources).groupBy({ it.first }, { it.second })
            current.sources.map { candidates[it].orEmpty().distinct().singleOrNull() ?: it }
        }
        val handler = if (current.handler == from.handler) to.handler else current.handler
        if (sources != current.sources || handler != current.handler) {
            change { it.copy(sources = sources, exceptionHandler = handler) }
        }
    }

    override fun loadState(state: Settings) {
        super.loadState(state)
        sourceChanges.incModificationCount()
    }

    fun changeExceptionHandler(value: String) {
        val path = projectFilePath(project, value.trim())
        val stored = PathMacroManager.getInstance(project).collapsePath(path?.invariantSeparatorsPathString)
        if (stored == getState().exceptionHandler) return
        change { it.copy(exceptionHandler = stored) }
    }

    fun changeSources(paths: List<String>) {
        val macros = PathMacroManager.getInstance(project)
        val stored = paths.mapNotNull {
            val path = projectFilePath(project, it)
            macros.collapsePath(path?.invariantSeparatorsPathString ?: it)
        }.distinct()
        if (stored == getState().sources) return
        change { it.copy(sources = stored) }
    }

    fun changeAssembleOnOpen(value: Boolean) {
        updateState { it.copy(assembleOnOpen = value) }
    }

    fun changeJvmCompile(value: Boolean) {
        updateState { it.copy(assembleBeforeJvmCompile = value) }
    }

    fun changeStartAtMain(value: Boolean) {
        updateState { it.copy(startAtMain = value) }
    }

    fun changeDelayedBranching(value: Boolean) {
        if (delayedBranching != value) change { it.copy(delayedBranching = value) }
    }

    fun changeSelfModifyingCode(value: Boolean) {
        updateState { it.copy(selfModifyingCode = value) }
    }

    fun changeBareMachine(value: Boolean) {
        if (bareMachine != value) change { it.copy(bareMachine = value) }
    }

    fun changeMemoryLayout(layout: MarsMemoryLayout) {
        if (getState().memoryLayout == layout.identifier) return
        change { it.copy(memoryLayout = layout.identifier) }
    }

    fun changeAssemblerOptions(allowPseudoInstructions: Boolean, warningsAreErrors: Boolean) {
        val current = executionOptions
        if (current.allowPseudoInstructions == allowPseudoInstructions && current.warningsAreErrors == warningsAreErrors) return
        change { it.copy(allowPseudoInstructions = allowPseudoInstructions, warningsAreErrors = warningsAreErrors) }
    }

    fun changeAssemblerErrorLimit(value: Int) {
        require(value > 0) { "Assembler error limit must be positive" }
        if (getState().assemblerErrorLimit != value) change { it.copy(assemblerErrorLimit = value) }
    }

    fun changeBackstepLimit(value: Int) {
        require(value in 1..MAX_BACKSTEP_LIMIT) { "Backstep history limit is outside the supported range" }
        updateState { it.copy(backstepLimit = value) }
    }

    fun applyImportedSettings(settings: Settings) {
        if (getState() != settings) change { settings }
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
            .withFileFilter(VirtualFile::isProjectAssemblySource)
            .withEnvironmentRestricted(true)
        val sources = ToolbarDecorator.createDecorator(files, rows).setAddAction {
            FileChooser.chooseFiles(sourceChooser, project, files, null) { selected ->
                val localSources = selected.filter(VirtualFile::isProjectAssemblySource)
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
        row {
            checkBox(messages.getMessage("build.settings.open"))
                .bindSelected({ settings.assembleOnOpen }, settings::changeAssembleOnOpen)
                .comment(messages.getMessage("build.settings.open.help"))
        }
        row {
            checkBox(messages.getMessage("run.start.at.main"))
                .bindSelected({ settings.startAtMain }, settings::changeStartAtMain)
                .comment(messages.getMessage("run.start.at.main.help"))
        }
        row {
            checkBox(messages.getMessage("run.delayed.branching"))
                .bindSelected({ settings.delayedBranching }, settings::changeDelayedBranching)
        }
        row {
            checkBox(messages.getMessage("run.self.modifying.code"))
                .bindSelected({ settings.selfModifyingCode }, settings::changeSelfModifyingCode)
        }
        row(messages.getMessage("build.settings.backstep.limit")) {
            intTextField(1..MAX_BACKSTEP_LIMIT)
                .bindIntText({ settings.backstepLimit }, settings::changeBackstepLimit)
                .comment(messages.getMessage("build.settings.backstep.limit.help"))
                .validationOnApply {
                    val value = it.text.toIntOrNull()
                    if (value == null || value !in 1..MAX_BACKSTEP_LIMIT) {
                        error(messages.getMessage("build.settings.backstep.limit.invalid", MAX_BACKSTEP_LIMIT))
                    } else null
                }
        }
        group(messages.getMessage("build.settings.policy")) {
            lateinit var pseudo: JCheckBox
            lateinit var warnings: JCheckBox
            row { pseudo = checkBox(messages.getMessage("build.settings.pseudo")).component }
            row { warnings = checkBox(messages.getMessage("build.settings.warnings")).component }
            row {
                checkBox(messages.getMessage("run.bare.machine"))
                    .bindSelected({ settings.bareMachine }, settings::changeBareMachine)
            }
            row(messages.getMessage("build.settings.error.limit")) {
                intTextField(1..Int.MAX_VALUE)
                    .bindIntText({ settings.assemblerErrorLimit }, settings::changeAssemblerErrorLimit)
                    .comment(messages.getMessage("build.settings.error.limit.help"))
                    .validationOnApply {
                        val value = it.text.toIntOrNull()
                        if (value == null || value <= 0) error(messages.getMessage("build.settings.error.limit.invalid")) else null
                    }
            }
            onReset {
                val options = settings.executionOptions
                pseudo.isSelected = options.allowPseudoInstructions
                warnings.isSelected = options.warningsAreErrors
            }
            onIsModified {
                val options = settings.executionOptions
                pseudo.isSelected != options.allowPseudoInstructions || warnings.isSelected != options.warningsAreErrors
            }
            onApply { settings.changeAssemblerOptions(pseudo.isSelected, warnings.isSelected) }
        }
        row(messages.getMessage("build.settings.memory")) {
            val layout = MutableProperty({ settings.memoryLayout }, settings::changeMemoryLayout)
            val renderer = textListCellRenderer<MarsMemoryLayout>("") { messages.getMessage(it.messageKey) }
            comboBox(MarsMemoryLayout.entries, renderer).bindItem(layout.toNullableProperty(MarsMemoryLayout.DEFAULT))
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
