package edu.missouristate.mars.plugin.run

import com.intellij.CommonBundle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.api.SessionSyscallEnvironment.ReadKind
import edu.missouristate.mars.api.SyscallEnvironment
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JOptionPane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class MarsIdeSyscallEnvironment(
    private val execution: Job,
    private val io: SyscallEnvironment,
    private val dialogs: MarsSyscallDialogs,
    private val clear: () -> Unit,
) : SyscallEnvironment by io {
    override fun playTone(pitch: Int, duration: Int, instrument: Int, volume: Int, synchronous: Boolean) {
        if (synchronous) {
            runBlocking(execution) {
                runInterruptible { io.playTone(pitch, duration, instrument, volume, true) }
            }
        } else {
            io.playTone(pitch, duration, instrument, volume, false)
        }
    }

    override fun sleepMillis(milliseconds: Int) {
        require(milliseconds >= 0) { "timeout value is negative" }
        runBlocking(execution) { delay(milliseconds.toLong()) }
    }

    override fun showInputDialog(message: String?) = dialogs.showInputDialog(message!!)
    override fun showConfirmDialog(message: String?) = dialogs.showConfirmDialog(message!!)
    override fun showMessageDialog(message: String?, title: String?, messageType: Int) =
        dialogs.showMessageDialog(message!!, title, messageType)
    override fun clearConsole() = onEdt(execution) { clear() }
}

internal class MarsSyscallDialogs(
    private val project: Project,
    private val sourceName: String,
    private val execution: Job,
) {
    fun readStandardInput(kind: ReadKind, maximumLength: Int): String? {
        val key = when (kind) {
            ReadKind.INTEGER -> "run.input.integer"
            ReadKind.FLOAT -> "run.input.float"
            ReadKind.DOUBLE -> "run.input.double"
            ReadKind.STRING -> "run.input.string"
            ReadKind.CHARACTER -> "run.input.character"
        }
        return showInputDialog(runMessages.getMessage(key, maximumLength))
    }

    fun showInputDialog(message: String): String? = onEdt(execution) {
        val field = JBTextField(36)
        val dialog = MarsSyscallDialog(
            project, runMessages.getMessage("run.dialog.input", sourceName), message,
            Messages.getQuestionIcon(), MarsSyscallDialog.Kind.INPUT, field,
        )
        if (dialog.showAndAwaitClose() == DialogWrapper.OK_EXIT_CODE) field.text else null
    }

    fun showConfirmDialog(message: String): Int = onEdt(execution) {
        val dialog = MarsSyscallDialog(
            project, runMessages.getMessage("run.dialog.confirm", sourceName), message, Messages.getQuestionIcon(), MarsSyscallDialog.Kind.CONFIRM,
        )
        when (dialog.showAndAwaitClose()) {
            DialogWrapper.OK_EXIT_CODE -> 0
            DialogWrapper.NEXT_USER_EXIT_CODE -> 1
            else -> 2
        }
    }

    fun showMessageDialog(message: String, title: String?, messageType: Int) = onEdt(execution) {
        val icon = when (messageType) {
            JOptionPane.ERROR_MESSAGE -> Messages.getErrorIcon()
            JOptionPane.INFORMATION_MESSAGE -> Messages.getInformationIcon()
            JOptionPane.WARNING_MESSAGE -> Messages.getWarningIcon()
            JOptionPane.QUESTION_MESSAGE -> Messages.getQuestionIcon()
            else -> null
        }
        val dialog = MarsSyscallDialog(
            project, title ?: runMessages.getMessage("run.dialog.message", sourceName), message, icon, MarsSyscallDialog.Kind.MESSAGE,
        )
        dialog.showAndAwaitClose()
        Unit
    }

    private suspend fun MarsSyscallDialog.showAndAwaitClose(): Int {
        val closed = CompletableDeferred<Int>()
        Disposer.register(disposable) { closed.complete(exitCode) }
        try {
            show()
            return closed.await()
        } finally {
            if (!closed.isCompleted) close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}

// Only the simulator worker waits. The execution job cancels pending UI work on Stop.
private fun <T> onEdt(execution: Job, block: suspend () -> T): T = runBlocking(execution) {
    withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }
}

internal class MarsSyscallDialog(
    project: Project, title: String, message: String, graphic: Icon?, private val kind: Kind,
    private val input: JBTextField? = null,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
    enum class Kind { INPUT, CONFIRM, MESSAGE }

    private val text = JBTextArea(message, 2, 44).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = lineCount.coerceIn(2, 8)
    }
    val fields = panel {
        row {
            if (graphic != null) icon(graphic)
            cell(JBScrollPane(text)).align(Align.FILL)
        }.resizableRow()
        if (input != null) {
            row(runMessages.getMessage("run.dialog.response")) {
                cell(input).align(AlignX.FILL)
            }
        }
    }

    init {
        setTitle(title)
        isResizable = true
        if (kind == Kind.CONFIRM) setOKButtonText(CommonBundle.getYesButtonText())
        init()
    }

    override fun createCenterPanel() = fields
    override fun getPreferredFocusedComponent() = input

    public override fun createActions(): Array<Action> = when (kind) {
        Kind.INPUT -> super.createActions()
        Kind.MESSAGE -> arrayOf(okAction)
        Kind.CONFIRM -> arrayOf(okAction, object : AbstractAction() {
            init { UIUtil.setActionNameAndMnemonic(CommonBundle.getNoButtonText(), this) }

            override fun actionPerformed(e: ActionEvent) {
                close(NEXT_USER_EXIT_CODE)
            }
        }, cancelAction)
    }
}
