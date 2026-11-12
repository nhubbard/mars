package edu.missouristate.mars.plugin.debug

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.plugin.debug.DebuggerCallStack.CallerSite
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class MarsExecutionStack(
    private val frame: XStackFrame,
    private val callers: Sequence<CallerSite>,
    private val sourcePosition: (Origin?) -> XSourcePosition?,
    private val suspensionScope: CoroutineScope,
) : XExecutionStack(runMessages.getMessage("debug.cpu")) {
    override fun getTopFrame() = frame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        suspensionScope.launch(Dispatchers.Default) {
            val older = callers.drop((firstFrameIndex - 1).coerceAtLeast(0))
                .map { MarsCallerFrame(it, sourcePosition(it.origin)) }
            val frames = if (firstFrameIndex == 0) sequenceOf(frame) + older else older
            val batches = frames.chunked(64).iterator()
            while (!container.isObsolete) {
                ensureActive()
                val batch = if (batches.hasNext()) batches.next() else emptyList()
                val last = !batches.hasNext()
                container.addStackFrames(batch, last)
                if (last) return@launch
            }
        }
    }
}

/** Navigation and call metadata are historical. Only the live CPU frame offers register values. */
internal class MarsCallerFrame(private val site: CallerSite, private val position: XSourcePosition?) : XStackFrame() {
    override fun getSourcePosition() = position
    override fun getEqualityObject(): String = "mars-caller-${site.id}"

    override fun customizePresentation(component: ColoredTextContainer) {
        val location = position?.let { runMessages.getMessage("debug.frame.source", it.file.name, (it.line + 1).toString()) }
            ?: Binary.intToHexString(site.pc)
        val key = if (site.call == null) "debug.frame.interrupted" else "debug.frame.caller"
        component.append(runMessages.getMessage(key, location), REGULAR_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        val call = site.call
        val values = XValueChildrenList()
        val addressKey = if (call == null) "debug.frame.interrupted.address" else "debug.frame.call.address"
        values.add(RegisterValue(runMessages.getMessage(addressKey)) { Binary.intToHexString(site.pc) })
        if (call != null) {
            values.add(RegisterValue(runMessages.getMessage("debug.frame.return.address")) { Binary.intToHexString(call.returnAddress) })
            values.add(RegisterValue(runMessages.getMessage("debug.frame.link.register")) { "\$${call.linkRegister}" })
        }
        node.addChildren(values, true)
    }
}
