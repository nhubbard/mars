package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.LO_REGISTER
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary

/** Captures the registers while the simulation is suspended. The UI only reads the captured values. */
internal class MarsStackFrame(private val position: XSourcePosition?, machine: MarsContext) : XStackFrame() {
    private val registerGroups = XValueChildrenList().apply {
        val cpu = machine.registerFile
        addTopGroup(RegisterGroup("debug.registers.cpu", XValueChildrenList().apply {
            add(hexRegister(cpu.programCounterRegister))
            cpu.registers.forEach { add(hexRegister(it)) }
            add(hexValue("hi", cpu.getValueNoNotify(HI_REGISTER)))
            add(hexValue("lo", cpu.getValueNoNotify(LO_REGISTER)))
        }, initiallyExpanded = true))
        addTopGroup(RegisterGroup("debug.registers.cp0", XValueChildrenList().apply {
            machine.coprocessor0.registers.forEach { add(hexRegister(it)) }
        }))

        val fpu = machine.coprocessor1
        val registers = fpu.registers.map { it.name to it.valueNoNotify }
        addTopGroup(RegisterGroup("debug.registers.cp1", XValueChildrenList().apply {
            add(hexValue("FCC", fpu.conditionFlagsNoNotify))
            for ((name, bits) in registers) {
                add(RegisterValue(name) { "${Float.fromBits(bits)} (${Binary.intToHexString(bits)})" })
            }
            val pairs = XValueChildrenList()
            for ((lowRegister, highRegister) in registers.chunked(2)) {
                val (lowName, low) = lowRegister
                val (highName, high) = highRegister
                val bits = Binary.twoIntsToLong(high, low)
                pairs.add(RegisterValue("$lowName/$highName") { "${Double.fromBits(bits)} (${Binary.longToHexString(bits)})" })
            }
            addBottomGroup(RegisterGroup("debug.registers.double", pairs))
        }))
    }

    override fun getSourcePosition() = position
    override fun getEqualityObject(): Any = MarsStackFrame::class.java
    override fun computeChildren(node: XCompositeNode) = node.addChildren(registerGroups, true)
}

private fun hexRegister(register: Register) = hexValue(register.name, register.valueNoNotify)
private fun hexValue(name: String, bits: Int) = RegisterValue(name) { Binary.intToHexString(bits) }

private class RegisterGroup(key: String, private val values: XValueChildrenList, private val initiallyExpanded: Boolean = false) :
    XValueGroup(runMessages.getMessage(key)) {
    override fun isAutoExpand() = initiallyExpanded
    override fun isRestoreExpansion() = true
    override fun computeChildren(node: XCompositeNode) {
        node.setAlreadySorted(true)
        node.addChildren(values, true)
    }
}

private class RegisterValue(name: String, private val format: () -> String) : XNamedValue(name) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) =
        node.setPresentation(null, null, format(), false)
}
