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
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.projectFilePath

@Service(Service.Level.PROJECT)
@State(name = "MarsAssemblySettings", storages = [Storage("mars.xml")])
internal class MarsAssemblySettings(private val project: Project) :
    SerializablePersistentStateComponent<MarsAssemblySettings.Settings>(Settings()) {
    data class Settings(@JvmField var exceptionHandler: String? = null)

    val sourceChanges = SimpleModificationTracker()
    val exceptionHandler get() = projectFilePath(project, getState().exceptionHandler)

    override fun loadState(state: Settings) {
        super.loadState(state)
        sourceChanges.incModificationCount()
    }

    fun changeExceptionHandler(value: String) {
        val path = projectFilePath(project, value.trim())
        val stored = PathMacroManager.getInstance(project).collapsePath(path?.toString())
        if (stored == getState().exceptionHandler) return
        runWriteAction {
            updateState { it.copy(exceptionHandler = stored) }
            sourceChanges.incModificationCount()
        }
        DaemonCodeAnalyzer.getInstance(project).restart("MARS exception handler changed")
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
