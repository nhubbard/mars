package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointType

internal inline fun <reified T : XBreakpointType<*, *>> findBreakpointType(): T =
    XDebuggerUtil.getInstance().findBreakpointType(T::class.java)
