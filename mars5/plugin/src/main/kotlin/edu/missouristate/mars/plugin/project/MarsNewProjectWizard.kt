package edu.missouristate.mars.plugin.project

import com.intellij.DynamicBundle
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.runAfterOpened
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

private val messages = DynamicBundle(MarsNewProjectWizard::class.java, "messages.MarsBundle")

internal class MarsNewProjectWizard : GeneratorNewProjectWizard {
    override val id = "MarsMips"
    override val name get() = messages.getMessage("project.wizard.name")
    override val icon get() = MipsFileType.icon

    override fun createStep(context: WizardContext): NewProjectWizardStep = RootNewProjectWizardStep(context)
        .nextStep(::NewProjectWizardBaseStep)
        .nextStep(::StarterStep)

    private class StarterStep(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
        private var separateLibrary = false

        override fun setupUI(builder: Panel) {
            builder.row {
                checkBox(messages.getMessage("project.wizard.library")).bindSelected(::separateLibrary)
                    .comment(messages.getMessage("project.wizard.library.help"))
            }
        }

        override fun setupProject(project: Project) {
            val root = Path.of(base.path, base.name)
            val sources = writeStarterSources(root, separateLibrary)
            ApplicationManager.getApplication().invokeAndWait {
                val module = runWriteAction {
                    ModuleManager.getInstance(project).newModule(root.resolve(".idea").resolve("${base.name}.iml"), GeneralModuleType.TYPE_ID)
                }
                ModuleRootModificationUtil.updateModel(module) { model ->
                    val content = model.addContentEntry(fileUrl(root))
                    content.addSourceFolder(fileUrl(root.resolve("src")), false)
                    model.sdk = null
                }
                project.service<MarsAssemblySettings>().changeSources(sources.map(Path::toString))
                val manager = RunManager.getInstance(project)
                val factory = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
                val settings = manager.createConfiguration(messages.getMessage("project.wizard.run"), factory)
                (settings.configuration as MarsRunConfiguration).options.useProjectSources = true
                settings.storeInDotIdeaFolder()
                manager.addConfiguration(settings)
                manager.selectedConfiguration = settings
            }
            runAfterOpened(project) { opened -> openStarterInEditor(opened, sources.first()) }
        }
    }
}

private fun fileUrl(path: Path) = VfsUtilCore.pathToUrl(path.invariantSeparatorsPathString)

internal fun openStarterInEditor(project: Project, path: Path) {
    val entry = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    NavigationService.getInstance(project).requestNavigate(
        OpenFileDescriptor(project, entry), NavigationOptions.requestFocus(),
    )
}

/** Publish the complete source directory together, with the filesystem's existing-target protection. */
internal fun writeStarterSources(root: Path, separateLibrary: Boolean): List<Path> {
    val staging = Files.createTempDirectory(root, ".mars-starter-")
    val sources = root.resolve("src")
    try {
        val entry = "main.asm"
        val library = "console.s"
        Files.writeString(staging.resolve(entry), if (separateLibrary) linkedMain else standaloneMain)
        if (separateLibrary) Files.writeString(staging.resolve(library), consoleLibrary)
        Files.move(staging, sources)
        return listOf(sources.resolve(entry)) + listOfNotNull(sources.resolve(library).takeIf { separateLibrary })
    } finally {
        FileUtil.delete(staging.toFile())
    }
}

private val standaloneMain = $$"""
    .data
    greeting: .asciiz "Hello from MARS!\n"

    .text
    .globl main
    main:
        la $a0, greeting
        li $v0, 4
        syscall

        li $v0, 10
        syscall
""".trimIndent() + "\n"

private val linkedMain = $$"""
    .data
    greeting: .asciiz "Hello from MARS!\n"

    .text
    .globl main
    main:
        la $a0, greeting
        jal print_string
        nop

        li $v0, 10
        syscall
""".trimIndent() + "\n"

private val consoleLibrary = $$"""
    .text
    .globl print_string
    print_string:
        li $v0, 4
        syscall
        jr $ra
        nop
""".trimIndent() + "\n"
