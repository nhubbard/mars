package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback

/** The session is not initialized yet; initialization still belongs in sessionInitialized. */
internal fun debugProcessStarter(start: (XDebugSession) -> XDebugProcess): XDebugProcessStarter =
    object : XDebugProcessStarter() {
      override fun start(session: XDebugSession): XDebugProcess = start(session)
    }

/** Both completion outcomes are required; no dispatching or exception handling is added. */
internal fun modificationCallback(
    valueModified: () -> Unit,
    errorOccurred: (String) -> Unit,
): XModificationCallback =
    object : XModificationCallback {
      override fun valueModified() = valueModified.invoke()

      override fun errorOccurred(errorMessage: String) = errorOccurred(errorMessage)
    }
