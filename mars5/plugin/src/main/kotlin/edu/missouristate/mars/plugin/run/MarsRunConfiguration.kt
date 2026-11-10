package edu.missouristate.mars.plugin.run

import com.intellij.DynamicBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toMutableProperty
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.not
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.execution.ParametersListUtil
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.build.MemoryExportSelection
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.projectAbsolutePath
import edu.missouristate.mars.plugin.projectFilePath
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import javax.swing.JTextField

internal val runMessages = DynamicBundle(MarsRunConfiguration::class.java, "messages.MarsBundle")

internal enum class ProjectOptionOverride(private val messageKey: String, private val override: Boolean?) {
    PROJECT("run.option.override.project", null),
    ENABLED("run.option.override.enabled", true),
    DISABLED("run.option.override.disabled", false);

    val title get() = runMessages.getMessage(messageKey)
    fun resolve(projectDefault: Boolean) = override ?: projectDefault
}

internal class MarsRunOptions : RunConfigurationOptions() {
    var sourcePath: String? by string()
    var programArguments: String? by string()
    var workingDirectory: String? by string()
    var instructionLimit: Int by property(0)
    var runSpeed: Float by property(0f)
    var delayedBranching: Boolean by property(false)
    var allowPseudoInstructions by enum(ProjectOptionOverride.PROJECT)
    var warningsAreErrors by enum(ProjectOptionOverride.PROJECT)
    var startAtMain by enum(ProjectOptionOverride.PROJECT)
    var assembleDirectory: Boolean by property(false)
    var useProjectSources: Boolean by property(false)
    var selfModifyingCode: Boolean by property(false)
    var captureBitmap: Boolean by property(false)
    var captureReferences: Boolean by property(false)
    var connectDigitalLab: Boolean by property(false)
    var connectKeyboardDisplay: Boolean by property(false)
    var connectMarsBot: Boolean by property(false)
    var connectScavengerHunt: Boolean by property(false)
    var memoryDumps by list<RunMemoryDumpState>()
}

internal class MarsRunType : SimpleConfigurationType(
    "MarsMipsRun", runMessages.getMessage("run.type"), runMessages.getMessage("run.type.description"),
    NotNullLazyValue.lazy { MipsFileType.icon },
) {
    override fun createTemplateConfiguration(project: Project) = MarsRunConfiguration(project, this, displayName)
    override fun getOptionsClass() = MarsRunOptions::class.java
}

internal class MarsRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<MarsRunOptions>(project, factory, name) {

    public override fun getOptions() = super.getOptions() as MarsRunOptions

    private fun memoryDumps(entry: Path, invalid: (String) -> Exception): List<MemoryExportSelection> {
        val outputs = mutableSetOf<Path>()
        return options.memoryDumps.mapIndexed { index, state ->
            val selection = state.resolve(project) ?: throw invalid(runMessages.getMessage("run.export.invalid", index + 1))
            if (selection.output == entry) throw invalid(runMessages.getMessage("run.export.source", selection.output))
            if (!outputs.add(selection.output)) throw invalid(runMessages.getMessage("run.export.duplicate", selection.output))
            selection
        }
    }

    private fun arguments(invalid: (String) -> Exception): List<String> {
        val arguments = options.programArguments.orEmpty()
        if (arguments.any { it !in '\u0001'..'\u00ff' }) {
            throw invalid(runMessages.getMessage("run.arguments.encoding"))
        }
        return ParametersListUtil.parse(arguments)
    }

    private fun instructionLimit(invalid: (String) -> Exception): Int {
        val limit = options.instructionLimit
        if (limit < 0) throw invalid(runMessages.getMessage("run.instruction.limit.invalid"))
        return limit
    }

    private fun workingDirectory(entry: Path, invalid: (String) -> Exception): Path {
        val value = options.workingDirectory.orEmpty().trim()
        if (value.isEmpty()) return entry.parent
        return projectAbsolutePath(project, value)?.takeIf(Files::isDirectory)
            ?: throw invalid(runMessages.getMessage("run.working.directory.invalid", value))
    }

    private fun runSpeed(invalid: (String) -> Exception): Float {
        val speed = options.runSpeed
        if (speed !in runSpeeds) throw invalid(runMessages.getMessage("run.speed.invalid"))
        return speed
    }

    private fun checkDevices(layout: MarsMemoryLayout, invalid: (String) -> Exception) {
        if (options.connectMarsBot && options.connectScavengerHunt) {
            throw invalid(runMessages.getMessage("run.connect.deviceConflict"))
        }
        if (options.connectScavengerHunt && layout != MarsMemoryLayout.DEFAULT) {
            throw invalid(runMessages.getMessage("run.connect.scavengerHunt.layout"))
        }
    }

    override fun checkConfiguration() {
        checkDevices(project.service<MarsAssemblySettings>().memoryLayout) { RuntimeConfigurationError(it) }
        val sources = resolveSourceSet { RuntimeConfigurationError(it) }
        memoryDumps(sources.entry) { RuntimeConfigurationError(it) }
        arguments { RuntimeConfigurationError(it) }
        workingDirectory(sources.entry) { RuntimeConfigurationError(it) }
        instructionLimit { RuntimeConfigurationError(it) }
        runSpeed { RuntimeConfigurationError(it) }
    }

    private fun resolveSourceSet(invalid: (String) -> Exception): MarsSourceSet {
        if (options.useProjectSources) {
            return project.service<MarsAssemblySettings>().projectSourceSet()
                ?: throw invalid(runMessages.getMessage("run.assemble.project.empty"))
        }
        val entry = projectFilePath(project, options.sourcePath) ?: throw invalid(runMessages.getMessage("run.source.invalid"))
        val mode = if (options.assembleDirectory) MarsAssemblyMode.DIRECTORY else MarsAssemblyMode.SINGLE
        return MarsSourceSet(entry, mode)
    }

    override fun getConfigurationEditor(): SettingsEditor<MarsRunConfiguration> = object : SettingsEditor<MarsRunConfiguration>() {
        private var source = ""
        private var programArguments = ""
        private var workingDirectory = ""
        private var instructionLimit = 0
        private var runSpeed = 0f
        private var delayedBranching = false
        private var assembleDirectory = false
        private var allowPseudoInstructions = ProjectOptionOverride.PROJECT
        private var warningsAreErrors = ProjectOptionOverride.PROJECT
        private var startAtMain = ProjectOptionOverride.PROJECT
        private var useProjectSources = false
        private var selfModifyingCode = false
        private var captureBitmap = false
        private var captureReferences = false
        private var connectDigitalLab = false
        private var connectKeyboardDisplay = false
        private var connectMarsBot = false
        private var connectScavengerHunt = false
        private val dumps = RunMemoryDumpEditor(project) {
            if (projectMode.isSelected) project.service<MarsAssemblySettings>().projectSourceSet()?.entry
            else projectFilePath(project, sourceField.text.trim())
        }
        private lateinit var projectMode: JCheckBox
        private lateinit var sourceField: JTextField
        private val form = panel {
            lateinit var useFile: ComponentPredicate
            row {
                val choice = checkBox(runMessages.getMessage("run.assemble.project")).bindSelected(::useProjectSources)
                projectMode = choice.component
                useFile = choice.selected.not()
            }
            row(runMessages.getMessage("run.source")) {
                sourceField = textField().bindText(::source).enabledIf(useFile).component
            }
            row {
                checkBox(runMessages.getMessage("run.assemble.directory")).bindSelected(::assembleDirectory)
                    .enabledIf(useFile)
            }
            row(runMessages.getMessage("run.arguments")) {
                cell(RawCommandLineEditor().withMonospaced(true)).align(AlignX.FILL)
                    .bind(RawCommandLineEditor::getText, RawCommandLineEditor::setText, ::programArguments.toMutableProperty())
                    .comment(runMessages.getMessage("run.arguments.help"))
            }
            val directoryChooser = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(runMessages.getMessage("run.working.directory.browse"))
                .withEnvironmentRestricted(true)
            row(runMessages.getMessage("run.working.directory")) {
                textFieldWithBrowseButton(directoryChooser, project)
                    .bindText(::workingDirectory)
                    .align(AlignX.FILL)
                    .comment(runMessages.getMessage("run.working.directory.help"))
            }
            row(runMessages.getMessage("run.instruction.limit")) {
                intTextField(0..Int.MAX_VALUE).bindIntText(::instructionLimit)
                    .validationOnApply {
                        if ((it.text.toIntOrNull() ?: -1) < 0) error(runMessages.getMessage("run.instruction.limit.invalid"))
                        else null
                    }
                    .comment(runMessages.getMessage("run.instruction.limit.help"))
            }
            row(runMessages.getMessage("run.speed.title")) {
                comboBox(runSpeeds, textListCellRenderer("") { runSpeedTitle(it) })
                    .bindItem(::runSpeed.toNullableProperty())
            }
            val overrideRenderer = textListCellRenderer<ProjectOptionOverride>("") { it.title }
            row(runMessages.getMessage("run.assembly.pseudo")) {
                comboBox(ProjectOptionOverride.entries, overrideRenderer)
                    .bindItem(::allowPseudoInstructions.toNullableProperty())
            }
            row(runMessages.getMessage("run.assembly.warnings")) {
                comboBox(ProjectOptionOverride.entries, overrideRenderer)
                    .bindItem(::warningsAreErrors.toNullableProperty())
            }
            row(runMessages.getMessage("run.start.at.main")) {
                comboBox(ProjectOptionOverride.entries, overrideRenderer)
                    .bindItem(::startAtMain.toNullableProperty())
                    .comment(runMessages.getMessage("run.start.at.main.help"))
            }
            row { checkBox(runMessages.getMessage("run.delayed.branching")).bindSelected(::delayedBranching) }
            row { checkBox(runMessages.getMessage("run.self.modifying.code")).bindSelected(::selfModifyingCode) }
            row { checkBox(runMessages.getMessage("run.capture.bitmap")).bindSelected(::captureBitmap) }
            row { checkBox(runMessages.getMessage("run.capture.references")).bindSelected(::captureReferences) }
            row { checkBox(runMessages.getMessage("run.connect.digitalLab")).bindSelected(::connectDigitalLab) }
            row { checkBox(runMessages.getMessage("run.connect.keyboardDisplay")).bindSelected(::connectKeyboardDisplay) }
            row { checkBox(runMessages.getMessage("run.connect.marsBot")).bindSelected(::connectMarsBot) }
            row { checkBox(runMessages.getMessage("run.connect.scavengerHunt")).bindSelected(::connectScavengerHunt) }
            row(runMessages.getMessage("run.export.title")) {
                cell(dumps.component).align(AlignX.FILL).comment(runMessages.getMessage("run.export.help"))
            }
        }.also { it.registerValidators(this) }
        override fun resetEditorFrom(configuration: MarsRunConfiguration) {
            source = configuration.options.sourcePath.orEmpty()
            programArguments = configuration.options.programArguments.orEmpty()
            workingDirectory = configuration.options.workingDirectory.orEmpty()
            instructionLimit = configuration.options.instructionLimit
            runSpeed = configuration.options.runSpeed
            delayedBranching = configuration.options.delayedBranching
            allowPseudoInstructions = configuration.options.allowPseudoInstructions
            warningsAreErrors = configuration.options.warningsAreErrors
            startAtMain = configuration.options.startAtMain
            assembleDirectory = configuration.options.assembleDirectory
            useProjectSources = configuration.options.useProjectSources
            selfModifyingCode = configuration.options.selfModifyingCode
            captureBitmap = configuration.options.captureBitmap
            captureReferences = configuration.options.captureReferences
            connectDigitalLab = configuration.options.connectDigitalLab
            connectKeyboardDisplay = configuration.options.connectKeyboardDisplay
            connectMarsBot = configuration.options.connectMarsBot
            connectScavengerHunt = configuration.options.connectScavengerHunt
            dumps.reset(configuration.options.memoryDumps)
            form.reset()
        }
        override fun applyEditorTo(configuration: MarsRunConfiguration) {
            val invalid = form.validateAll().firstOrNull()
            if (invalid != null) throw ConfigurationException(invalid.message)
            form.apply()
            configuration.options.sourcePath = source.trim()
            configuration.options.programArguments = programArguments
            configuration.options.workingDirectory = workingDirectory.trim()
            configuration.options.instructionLimit = instructionLimit
            configuration.options.runSpeed = runSpeed
            configuration.options.delayedBranching = delayedBranching
            configuration.options.allowPseudoInstructions = allowPseudoInstructions
            configuration.options.warningsAreErrors = warningsAreErrors
            configuration.options.startAtMain = startAtMain
            configuration.options.assembleDirectory = assembleDirectory
            configuration.options.useProjectSources = useProjectSources
            configuration.options.selfModifyingCode = selfModifyingCode
            configuration.options.captureBitmap = captureBitmap
            configuration.options.captureReferences = captureReferences
            configuration.options.connectDigitalLab = connectDigitalLab
            configuration.options.connectKeyboardDisplay = connectKeyboardDisplay
            configuration.options.connectMarsBot = connectMarsBot
            configuration.options.connectScavengerHunt = connectScavengerHunt
            configuration.options.memoryDumps = dumps.copyRows()
        }
        override fun createEditor() = form
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException(runMessages.getMessage("run.project.untrusted"))
        }
        val sources = resolveSourceSet { ExecutionException(it) }
        val settings = project.service<MarsAssemblySettings>()
        val defaults = settings.executionOptions
        checkDevices(defaults.memoryLayout) { ExecutionException(it) }
        val dumps = memoryDumps(sources.entry) { ExecutionException(it) }
        val executionOptions = defaults.copy(
            delayedBranching = options.delayedBranching,
            selfModifyingCode = options.selfModifyingCode,
            allowPseudoInstructions = options.allowPseudoInstructions.resolve(defaults.allowPseudoInstructions),
            warningsAreErrors = options.warningsAreErrors.resolve(defaults.warningsAreErrors),
        )
        val arguments = arguments { ExecutionException(it) }
        val directory = workingDirectory(sources.entry) { ExecutionException(it) }
        val limit = instructionLimit { ExecutionException(it) }
        val speed = runSpeed { ExecutionException(it) }
        val launch = MarsLaunchAssembly(sources, executionOptions)
        environment.putUserData(launchAssemblyKey, launch)
        return MarsRunState(
            environment, launch, dumps, arguments, directory, limit, speed, options.startAtMain.resolve(settings.startAtMain),
            options.captureBitmap, options.captureReferences,
            options.connectDigitalLab, options.connectKeyboardDisplay, options.connectMarsBot, options.connectScavengerHunt,
        )
    }
}

internal class MarsRunState(
    private val environment: ExecutionEnvironment, private val launch: MarsLaunchAssembly,
    private val memoryDumps: List<MemoryExportSelection>,
    private val arguments: List<String>,
    private val workingDirectory: Path,
    private val instructionLimit: Int,
    private val runSpeed: Float,
    private val startAtMain: Boolean,
    private val captureBitmap: Boolean,
    private val captureReferences: Boolean,
    private val connectDigitalLab: Boolean,
    private val connectKeyboardDisplay: Boolean,
    private val connectMarsBot: Boolean,
    private val connectScavengerHunt: Boolean,
) :
    CommandLineState(environment) {
    val entry: Path get() = launch.sources.entry

    override fun createActions(console: ConsoleView?, processHandler: ProcessHandler, executor: Executor?): Array<AnAction> =
        arrayOf(MarsRunSpeedAction(processHandler as MarsProcessHandler))

    override fun startProcess() =
        service().createProcess(entry, memoryDumps = memoryDumps, captureBitmap = captureBitmap,
            captureReferences = captureReferences, connectDigitalLab = connectDigitalLab, connectKeyboardDisplay = connectKeyboardDisplay,
            connectMarsBot = connectMarsBot, connectScavengerHunt = connectScavengerHunt, launch = launch,
            arguments = arguments, workingDirectory = workingDirectory, instructionLimit = instructionLimit,
            runSpeed = runSpeed, startAtMain = startAtMain)

    fun debugProcess(execute: suspend MarsProcessHandler.(MarsContext, CapturedAssemblySources) -> StopEvent) =
        service().createProcess(entry, memoryDumps = memoryDumps, captureBitmap = captureBitmap,
            captureReferences = captureReferences, connectDigitalLab = connectDigitalLab, connectKeyboardDisplay = connectKeyboardDisplay,
            connectMarsBot = connectMarsBot, connectScavengerHunt = connectScavengerHunt, launch = launch,
            arguments = arguments, workingDirectory = workingDirectory, instructionLimit = instructionLimit,
            runSpeed = runSpeed, startAtMain = startAtMain, execute = execute)

    private fun service() = environment.project.getService(MarsRunService::class.java)
}

internal class MarsRunConfigurationProducer : LazyRunConfigurationProducer<MarsRunConfiguration>() {
    override fun getConfigurationFactory() = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)

    override fun setupConfigurationFromContext(
        configuration: MarsRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = entryFile(sourceElement.get()) ?: return false
        configuration.options.sourcePath = file.path
        configuration.name = file.nameWithoutExtension
        return true
    }

    override fun isConfigurationFromContext(configuration: MarsRunConfiguration, context: ConfigurationContext): Boolean {
        if (configuration.options.useProjectSources) return false
        val file = entryFile(context.psiLocation) ?: return false
        return PathMacroManager.getInstance(configuration.project).expandPath(configuration.options.sourcePath) == file.path
    }

    private fun entryFile(element: PsiElement?) = (element?.containingFile as? MipsFile)?.virtualFile?.takeIf {
        it.fileSystem === LocalFileSystem.getInstance()
    }
}

internal class MarsRunRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "MarsMipsRunner"
    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is MarsRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment) =
        showRunContent(state.execute(environment.executor, this), environment)
}
