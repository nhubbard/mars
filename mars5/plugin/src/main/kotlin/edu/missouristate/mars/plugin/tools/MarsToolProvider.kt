package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.api.StepEvent
import java.util.EventListener
import javax.swing.JComponent
import kotlinx.coroutines.delay

/** Contributes a tab to MARS Tools. Keep the provider lightweight and construct UI in [createContent]. */
interface MarsToolProvider {
    val id: String
    val title: String

    /** Called on the EDT when the tab is selected. Register owned resources with [MarsToolContext.disposable]. */
    fun createContent(context: MarsToolContext): JComponent

    companion object {
        internal val EP = ExtensionPointName<MarsToolProvider>("edu.missouristate.mars.toolProvider")
    }
}

interface MarsToolExecution {
    /** Delivers future instructions on the simulation worker. Listeners must return promptly. */
    fun addInstructionListener(listener: MarsToolInstructionListener, lifetime: Disposable)
}

fun interface MarsToolInstructionListener : EventListener {
    fun instructionCompleted(event: StepEvent)
}

class MarsToolContext internal constructor(val project: Project, val disposable: Disposable) {
    /** Creates the native execution selector. Selection changes are delivered on the EDT. */
    fun executionSelector(onSelectionChanged: (MarsToolExecution?) -> Unit): JComponent {
        lateinit var selector: MarsToolExecutionSelector
        selector = MarsToolExecutionSelector(project) { onSelectionChanged(selector.process) }
        selector.refresh()
        val job = selector.component.launchOnShow("Refresh MARS tool executions") {
            while (true) {
                selector.refresh()
                delay(200)
            }
        }
        Disposer.register(disposable) { job.cancel() }
        return selector.component
    }
}
