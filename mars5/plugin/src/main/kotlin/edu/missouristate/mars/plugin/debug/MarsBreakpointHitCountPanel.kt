package edu.missouristate.mars.plugin.debug

import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import edu.missouristate.mars.plugin.run.runMessages
import java.text.ParseException

internal interface MarsBreakpointHitCount {
    var hitInterval: Int
}

internal class MarsBreakpointHitCountPanel<B : XBreakpoint<*>> : XBreakpointCustomPropertiesPanel<B>() {
    private lateinit var interval: JBIntSpinner
    private val content = panel {
        row(runMessages.getMessage("debug.breakpoint.hit.interval")) {
            interval = spinner(1..Int.MAX_VALUE).component
            label(runMessages.getMessage("debug.breakpoint.hit.interval.hits"))
        }
    }

    override fun getComponent() = content

    override fun loadFrom(breakpoint: B) {
        interval.number = (breakpoint.properties as? MarsBreakpointHitCount)?.hitInterval ?: 1
    }

    override fun saveTo(breakpoint: B) {
        val properties = breakpoint.properties as? MarsBreakpointHitCount ?: return
        try {
            interval.commitEdit()
        } catch (_: ParseException) {
            return
        }
        properties.hitInterval = interval.number
    }
}
