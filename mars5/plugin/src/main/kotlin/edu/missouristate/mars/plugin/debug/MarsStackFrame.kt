package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.components.service
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
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CONDITION_FLAG_MASK
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.Companion.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.Companion.LO_REGISTER
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.plugin.debug.RegisterBank.COPROCESSOR_0
import edu.missouristate.mars.plugin.debug.RegisterBank.CPU
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_DOUBLE
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_FLAGS
import edu.missouristate.mars.plugin.debug.RegisterBank.FPU_SINGLE
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.BackStepper.RegisterWrite
import edu.missouristate.mars.simulator.BackStepper.RegisterWriteBank
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.channels.SendChannel

/** Captures the registers while the simulation is suspended. The UI only reads the captured values. */
internal class MarsStackFrame(
    @Volatile private var position: XSourcePosition?,
    machine: MarsContext,
    private val project: Project,
    private val localSymbols: Map<String, Definition>,
    private val globalSymbols: Map<String, Definition>,
    private val loadedSymbols: LoadedSymbols,
    private val watchRequests: SendChannel<WatchMemoryRequest>,
    private val machineEdits: SendChannel<MachineEditRequest>,
    private val isCurrent: () -> Boolean,
) : XStackFrame() {
    private data class Snapshot(val evaluator: MarsExpressionEvaluator, val groups: XValueChildrenList)
    private val display = service<MarsDebuggerDisplaySettings>()
    @Volatile private var snapshot = capture(machine)

    fun refresh(machine: MarsContext, position: XSourcePosition?) {
        this.position = position
        snapshot = capture(machine)
    }

    private fun capture(machine: MarsContext): Snapshot {
        val evaluator = MarsExpressionEvaluator(project, machine, localSymbols, globalSymbols, loadedSymbols, watchRequests)
        return Snapshot(evaluator, registerGroups(machine))
    }

    private fun registerGroups(machine: MarsContext) = XValueChildrenList().apply {
        val cpu = machine.registerFile
        val wide = machine.target == MarsTarget.MARS64
        val writes = machine.backStepper.previousRegisterWrites()
        fun written(bank: RegisterWriteBank, number: Int) = RegisterWrite(bank, number) in writes
        addTopGroup(RegisterGroup("debug.registers.cpu", XValueChildrenList().apply {
            val pc = cpu.programCounterBits
            add(RegisterValue(cpu.programCounterRegister.name) { if (pc == pc.toInt().toLong()) display.formatUnsignedValue(pc.toInt())
                else display.formatWideValue(pc) })
            cpu.registers.forEach {
                if (wide) {
                    val bits = cpu.getLongValueNoNotify(it.number)
                    val format = { display.formatWideValue(bits) }
                    add(if (it.number == 0) RegisterValue(it.name, format = format)
                    else editableValue(CPU, it.number, it.name, written(RegisterWriteBank.CPU, it.number), format, format))
                } else {
                    val bits = it.valueNoNotify
                    add(if (it.number == 0) RegisterValue(it.name) { display.formatValue(bits) }
                    else editableWord(CPU, it.name, bits, it.number, written(RegisterWriteBank.CPU, it.number)))
                }
            }
            add(editableWord(CPU, "hi", cpu.getValueNoNotify(HI_REGISTER), HI_REGISTER, written(RegisterWriteBank.CPU, HI_REGISTER)))
            add(editableWord(CPU, "lo", cpu.getValueNoNotify(LO_REGISTER), LO_REGISTER, written(RegisterWriteBank.CPU, LO_REGISTER)))
        }, initiallyExpanded = true))
        addTopGroup(RegisterGroup("debug.registers.cp0", XValueChildrenList().apply {
            machine.coprocessor0.registers.forEach {
                add(editableWord(COPROCESSOR_0, it.name, it.valueNoNotify, it.number, written(RegisterWriteBank.COPROCESSOR_0, it.number)))
            }
        }))

        val fpu = machine.coprocessor1
        val registers = fpu.registers.map { it.name to it.valueNoNotify }
        addTopGroup(RegisterGroup("debug.registers.cp1", XValueChildrenList().apply {
            add(editableWord(FPU_FLAGS, "FCC", fpu.conditionFlagsNoNotify and CONDITION_FLAG_MASK, 0,
                written(RegisterWriteBank.CONDITION_FLAGS, 0)))
            for ((number, entry) in registers.withIndex()) {
                val (name, bits) = entry
                val hex = Binary.intToHexString(bits)
                add(editableValue(FPU_SINGLE, number, name, written(RegisterWriteBank.COPROCESSOR_1, number), { hex }) {
                    formatFloating(Float.fromBits(bits).toString(), hex)
                })
            }
            val doublewords = XValueChildrenList()
            for (number in registers.indices) {
                if (!wide && number % 2 != 0) continue
                val name = if (wide) registers[number].first else "${registers[number].first}/${registers[number + 1].first}"
                val bits = fpu.getDoublewordNoNotify(number)
                val hex = Binary.longToHexString(bits)
                val changed = written(RegisterWriteBank.COPROCESSOR_1, number) ||
                    (!wide && written(RegisterWriteBank.COPROCESSOR_1, number + 1))
                doublewords.add(editableValue(FPU_DOUBLE, number, name, changed, { hex }) {
                    formatFloating(Double.fromBits(bits).toString(), hex)
                })
            }
            val groupKey = if (wide) "debug.registers.double.wide" else "debug.registers.double"
            addBottomGroup(RegisterGroup(groupKey, doublewords))
        }))
        if (wide) {
            addTopGroup(RegisterGroup("debug.registers.vector", XValueChildrenList().apply {
                for (number in registers.indices) {
                    val bits = fpu.getVectorNoNotify(number)
                    add(VectorRegisterValue($$"$w$$number", bits, written(RegisterWriteBank.COPROCESSOR_1, number)))
                }
            }))
        }
    }

    private fun editableWord(bank: RegisterBank, name: String, bits: Int, number: Int, written: Boolean): RegisterValue {
        val format = { display.formatValue(bits) }
        return editableValue(bank, number, name, written, format, format)
    }

    private fun formatFloating(decimal: String, hex: String) = if (display.hexValues) "$decimal ($hex)" else decimal

    private fun editableValue(bank: RegisterBank, number: Int, name: String, written: Boolean, initial: () -> String, format: () -> String) =
        RegisterValue(name, RegisterModifier(bank, number, initial, machineEdits, isCurrent), written, format = format)

    override fun getSourcePosition() = position
    override fun getEvaluator() = snapshot.evaluator
    override fun getEqualityObject(): Any = MarsStackFrame::class.java
    override fun computeChildren(node: XCompositeNode) = node.addChildren(snapshot.groups, true)
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

internal open class RegisterValue(
    name: String,
    private val modifier: XValueModifier? = null,
    private val written: Boolean = false,
    private val expandable: Boolean = false,
    private val format: () -> String,
) : XNamedValue(name) {
    override fun getModifier() = modifier

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val value = format()
        if (!written || !service<MarsDebuggerDisplaySettings>().highlightRegisterWrites) {
            node.setPresentation(null, null, value, expandable)
            return
        }
        node.setPresentation(null, object : XValuePresentation() {
            override fun getSeparator() = " (${runMessages.getMessage("debug.registers.written")})${super.getSeparator()}"

            override fun renderValue(renderer: XValueTextRenderer) = renderer.renderValue(value, MarsMachineHighlight.REGISTER.colorKey)
        }, expandable)
    }
}

private class VectorRegisterValue(name: String, private val bits: VectorRegister, written: Boolean) :
    RegisterValue(name, written = written, expandable = true, format = bits::toHexString) {
    override fun computeChildren(node: XCompositeNode) {
        val values = XValueChildrenList()
        for (width in listOf(8, 16, 32, 64)) {
            val label = runMessages.getMessage("debug.registers.vector.integer.lanes", width)
            values.add(RegisterValue(label) {
                (0 until 128 / width).joinToString(", ", "[", "]") { index ->
                    "0x" + bits.unsignedLane(index, width).toULong().toString(16).padStart(width / 4, '0')
                }
            })
        }
        values.add(RegisterValue(runMessages.getMessage("debug.registers.vector.float.lanes", 32)) {
            (0 until 4).joinToString(", ", "[", "]") { Float.fromBits(bits.unsignedLane(it, 32).toInt()).toString() }
        })
        values.add(RegisterValue(runMessages.getMessage("debug.registers.vector.float.lanes", 64)) {
            (0 until 2).joinToString(", ", "[", "]") { Double.fromBits(bits.unsignedLane(it, 64)).toString() }
        })
        node.setAlreadySorted(true)
        node.addChildren(values, true)
    }
}
