package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State.CONDITION_FLAG_MASK
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.LO_REGISTER
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.plugin.debug.RegisterBank.COPROCESSOR_0
import edu.missouristate.mars.plugin.debug.RegisterBank.CPU
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_DOUBLE
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_FLAGS
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_SINGLE
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.channels.SendChannel

/** Captures the registers while the simulation is suspended. The UI only reads the captured values. */
internal class MarsStackFrame(
    private val position: XSourcePosition?,
    machine: MarsContext,
    private val project: Project,
    private val localSymbols: Map<String, Definition>,
    private val globalSymbols: Map<String, Definition>,
    private val watchRequests: SendChannel<WatchMemoryRequest>,
    private val registerEdits: SendChannel<RegisterEditRequest>,
    private val isCurrent: () -> Boolean,
) : XStackFrame() {
    private data class Snapshot(val evaluator: MarsExpressionEvaluator, val groups: XValueChildrenList)
    @Volatile private var snapshot = capture(machine)

    fun refresh(machine: MarsContext) {
        snapshot = capture(machine)
    }

    private fun capture(machine: MarsContext): Snapshot {
        val evaluator = MarsExpressionEvaluator(project, machine, localSymbols, globalSymbols, watchRequests)
        return Snapshot(evaluator, registerGroups(machine))
    }

    private fun registerGroups(machine: MarsContext) = XValueChildrenList().apply {
        val cpu = machine.registerFile
        addTopGroup(RegisterGroup("debug.registers.cpu", XValueChildrenList().apply {
            add(hexRegister(cpu.programCounterRegister))
            cpu.registers.forEach {
                add(if (it.number == 0) hexRegister(it) else editableWord(CPU, it.name, it.valueNoNotify, it.number))
            }
            add(editableWord(CPU, "hi", cpu.getValueNoNotify(HI_REGISTER), HI_REGISTER))
            add(editableWord(CPU, "lo", cpu.getValueNoNotify(LO_REGISTER), LO_REGISTER))
        }, initiallyExpanded = true))
        addTopGroup(RegisterGroup("debug.registers.cp0", XValueChildrenList().apply {
            machine.coprocessor0.registers.forEach { add(editableWord(COPROCESSOR_0, it.name, it.valueNoNotify, it.number)) }
        }))

        val fpu = machine.coprocessor1
        val registers = fpu.registers.map { it.name to it.valueNoNotify }
        addTopGroup(RegisterGroup("debug.registers.cp1", XValueChildrenList().apply {
            add(editableWord(FPU_FLAGS, "FCC", fpu.conditionFlagsNoNotify and CONDITION_FLAG_MASK, 0))
            for ((number, entry) in registers.withIndex()) {
                val (name, bits) = entry
                val hex = Binary.intToHexString(bits)
                add(editableValue(FPU_SINGLE, number, name, hex) { "${Float.fromBits(bits)} ($hex)" })
            }
            val pairs = XValueChildrenList()
            for ((index, pair) in registers.chunked(2).withIndex()) {
                val (lowRegister, highRegister) = pair
                val (lowName, low) = lowRegister
                val (highName, high) = highRegister
                val bits = Binary.twoIntsToLong(high, low)
                val hex = Binary.longToHexString(bits)
                pairs.add(editableValue(FPU_DOUBLE, index * 2, "$lowName/$highName", hex) { "${Double.fromBits(bits)} ($hex)" })
            }
            addBottomGroup(RegisterGroup("debug.registers.double", pairs))
        }))
    }

    private fun editableWord(bank: RegisterBank, name: String, bits: Int, number: Int): RegisterValue {
        val hex = Binary.intToHexString(bits)
        return editableValue(bank, number, name, hex) { hex }
    }

    private fun editableValue(bank: RegisterBank, number: Int, name: String, initial: String, format: () -> String) =
        RegisterValue(name, RegisterModifier(bank, number, initial, registerEdits, isCurrent), format)

    override fun getSourcePosition() = position
    override fun getEvaluator() = snapshot.evaluator
    override fun getEqualityObject(): Any = MarsStackFrame::class.java
    override fun computeChildren(node: XCompositeNode) = node.addChildren(snapshot.groups, true)
}

private fun hexRegister(register: Register): RegisterValue {
    val bits = register.valueNoNotify
    return RegisterValue(register.name) { Binary.intToHexString(bits) }
}

private class RegisterGroup(key: String, private val values: XValueChildrenList, private val initiallyExpanded: Boolean = false) :
    XValueGroup(runMessages.getMessage(key)) {
    override fun isAutoExpand() = initiallyExpanded
    override fun isRestoreExpansion() = true
    override fun computeChildren(node: XCompositeNode) {
        node.setAlreadySorted(true)
        node.addChildren(values, true)
    }
}

internal class RegisterValue(
    name: String,
    private val modifier: XValueModifier? = null,
    private val format: () -> String,
) : XNamedValue(name) {
    override fun getModifier() = modifier

    override fun computePresentation(node: XValueNode, place: XValuePlace) =
        node.setPresentation(null, null, format(), false)
}
