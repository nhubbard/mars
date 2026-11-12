package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.launchOnShow
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** A working example of a tool contributed through the public provider API. */
internal class IntroToToolsProvider : MarsToolProvider {
    override val id = "edu.missouristate.mars.introToTools"
    override val title get() = toolsMessages.getMessage("tools.intro.name")

    override fun createContent(context: MarsToolContext): JComponent {
        val steps = JBLabel()
        val instructions = JBTextArea(toolsMessages.getMessage("tools.intro.guide")).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
        }
        var binding: Disposable? = null
        var count = AtomicLong()
        fun render() {
            steps.text = toolsMessages.getMessage("tools.intro.steps", count.get())
        }
        val selector = context.executionSelector { execution ->
            binding?.let(Disposer::dispose)
            binding = null
            val selectedCount = AtomicLong()
            count = selectedCount
            if (execution != null) {
                val lifetime = Disposer.newDisposable(context.disposable)
                execution.addInstructionListener({ selectedCount.incrementAndGet() }, lifetime)
                binding = lifetime
            }
            render()
        }
        val component = marsToolPanel {
            marsExecutionRow(selector)
            row { cell(steps) }
            row {
                cell(JBScrollPane(instructions).apply { preferredSize = JBDimension(310, 300) })
                    .align(Align.FILL).resizableColumn()
            }.resizableRow()
        }
        render()
        val job = component.launchOnShow("Refresh Introduction to Tools") {
            while (true) {
                render()
                delay(200.milliseconds)
            }
        }
        Disposer.register(context.disposable) { job.cancel() }
        return component
    }
}
