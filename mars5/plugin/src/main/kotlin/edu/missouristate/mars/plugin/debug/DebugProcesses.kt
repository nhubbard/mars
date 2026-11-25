package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import edu.missouristate.mars.util.javaClassOf

internal inline fun <reified T : XDebugProcess> XDebuggerManager.getDebugProcesses(): List<T> =
    getDebugProcesses(javaClassOf<T>())
