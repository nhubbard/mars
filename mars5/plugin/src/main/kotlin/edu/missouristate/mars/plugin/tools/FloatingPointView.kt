package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.launchOnShow
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.plugin.debug.MarsDebugProcess
import edu.missouristate.mars.plugin.debug.MarsSuspendContext
import edu.missouristate.mars.plugin.debug.RegisterBank
import edu.missouristate.mars.plugin.debug.RegisterEditRequest
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Kind
import edu.missouristate.mars.util.Binary
import javax.swing.JButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class FloatingPointView(private val project: Project) : Disposable {
    private val selector = MarsToolExecutionSelector(project, ::bindingChanged)
    private val register = ComboBox((listOf(toolsMessages.getMessage("float.detached")) +
        RegisterNames.floatingSpellings()).toTypedArray())
    private val decimal = JBTextField(16)
    private val hex = JBTextField(12)
    private val sign = JBTextField(1)
    private val exponent = JBTextField(8)
    private val fraction = JBTextField(23)
    private val fields = listOf(decimal, hex, sign, exponent, fraction)
    private val classification = JBLabel()
    private val formula = JBLabel()
    private val access = JBLabel()
    private val feedback = JBLabel()
    private val reset = JButton(toolsMessages.getMessage("counter.reset"))
    private var shown = FloatingPointRepresentation(0)
    private var editRevision = 0L

    private val controls = panel {
        row(toolsMessages.getMessage("counter.selector")) { cell(selector.component).align(AlignX.FILL).resizableColumn() }
        row(toolsMessages.getMessage("float.register")) { cell(register) }
        row { cell(access) }
        row(toolsMessages.getMessage("float.decimal")) { cell(decimal) }
        row(toolsMessages.getMessage("float.hex")) { cell(hex) }
        row(toolsMessages.getMessage("float.sign")) { cell(sign) }
        row(toolsMessages.getMessage("float.exponent")) { cell(exponent) }
        row(toolsMessages.getMessage("float.fraction")) { cell(fraction) }
        row(toolsMessages.getMessage("float.classification")) { cell(classification) }
        row { label(toolsMessages.getMessage("float.interpretation")) }
        row { cell(formula) }
        row { cell(reset) }
        row { cell(feedback) }
        row { comment(toolsMessages.getMessage("float.help")) }
    }
    val component = ScrollPaneFactory.createScrollPane(controls, true)

    init {
        register.addActionListener { bindingChanged() }
        decimal.addActionListener { apply(FloatingPointRepresentation.fromDecimal(decimal.text)) }
        hex.addActionListener { apply(FloatingPointRepresentation.fromHex(hex.text)) }
        for (field in listOf(sign, exponent, fraction)) {
            field.addActionListener { apply(FloatingPointRepresentation.fromBinary(sign.text, exponent.text, fraction.text)) }
        }
        reset.addActionListener { apply(FloatingPointRepresentation(0)) }
        display(shown)
        refresh()
    }

    private val refreshJob = component.launchOnShow("Refresh MARS floating-point representation") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200)
        }
    }

    private fun refresh() {
        selector.refresh()
        refreshBinding()
    }

    private fun bindingChanged() {
        editRevision++
        feedback.text = ""
        display(shown)
        refreshBinding()
    }

    private fun refreshBinding() {
        val process = selector.process
        val number = register.selectedIndex - 1
        val attached = process?.takeIf { number >= 0 }
        val stopped = attached?.let(::stoppedContext) != null
        val editable = attached == null || stopped
        val bits = attached?.floatingRegisterBits(number)
        register.isEnabled = process != null
        for (field in fields) field.isEditable = editable
        reset.isEnabled = editable
        access.text = toolsMessages.getMessage(when {
            attached == null -> "float.local"
            bits == null -> "float.waiting"
            stopped -> "float.paused"
            else -> "float.readOnly"
        })
        if (attached != null) {
            val current = bits ?: 0
            if (current != shown.bits) display(FloatingPointRepresentation(current))
        }
    }

    private fun stoppedContext(process: MarsProcessHandler): MarsSuspendContext? {
        val debugger = XDebuggerManager.getInstance(project).getDebugProcesses(MarsDebugProcess::class.java)
            .firstOrNull { it.processHandler === process } ?: return null
        return debugger.session.suspendContext as? MarsSuspendContext
    }

    private fun apply(value: FloatingPointRepresentation?) {
        val revision = ++editRevision
        if (value == null) {
            feedback.text = toolsMessages.getMessage("float.invalid")
            return
        }
        feedback.text = ""
        val process = selector.process
        val number = register.selectedIndex - 1
        if (process == null || number < 0) {
            display(value)
            return
        }
        val stopped = stoppedContext(process)
        if (stopped == null) {
            feedback.text = toolsMessages.getMessage("float.readOnly")
            return
        }
        val edit = RegisterEditRequest(RegisterBank.FPU_SINGLE, number, value.hex, object : XModificationCallback {
            override fun valueModified() {
                if (revision == editRevision) display(value)
            }

            override fun errorOccurred(errorMessage: String) {
                UIUtil.invokeLaterIfNeeded {
                    if (revision == editRevision) feedback.text = errorMessage
                }
            }
        })
        if (stopped.registerEdits.trySend(edit).isFailure) edit.unavailable()
    }

    private fun display(value: FloatingPointRepresentation) {
        shown = value
        decimal.text = value.decimal
        hex.text = value.hex
        sign.text = value.sign.toString()
        exponent.text = value.exponentBits
        fraction.text = value.fractionBits
        classification.text = toolsMessages.getMessage(value.kind.messageKey)
        formula.text = when (value.kind) {
            Kind.INFINITY -> toolsMessages.getMessage("float.formula.infinity")
            Kind.NAN -> toolsMessages.getMessage("float.formula.nan", Binary.intToHexString(value.fraction))
            else -> toolsMessages.getMessage("float.formula.finite", value.sign.toString(),
                value.finiteExponent.toString(), if (value.kind == Kind.NORMAL) "1" else "0", value.fractionBits)
        }
    }

    override fun dispose() {
        editRevision++
        refreshJob.cancel()
    }
}
