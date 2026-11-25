package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XDebugSessionListener
import edu.missouristate.mars.api.MarsListenerDsl

@MarsListenerDsl
internal class DebugSessionListenerBuilder internal constructor() {
  private var sessionPausedAction: XDebugSessionListener.() -> Unit = {}
  private var sessionResumedAction: XDebugSessionListener.() -> Unit = {}
  private var sessionStoppedAction: XDebugSessionListener.() -> Unit = {}
  private var stackFrameChangedAction: XDebugSessionListener.() -> Unit = {}
  private var beforeSessionResumeAction: XDebugSessionListener.() -> Unit = {}
  private var settingsChangedAction: XDebugSessionListener.() -> Unit = {}
  private var breakpointsMutedAction: XDebugSessionListener.(Boolean) -> Unit = { _ -> }

  fun sessionPaused(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    sessionPausedAction = block
  }

  fun sessionResumed(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    sessionResumedAction = block
  }

  fun sessionStopped(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    sessionStoppedAction = block
  }

  fun stackFrameChanged(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    stackFrameChangedAction = block
  }

  fun beforeSessionResume(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    beforeSessionResumeAction = block
  }

  fun settingsChanged(block: @MarsListenerDsl XDebugSessionListener.() -> Unit) {
    settingsChangedAction = block
  }

  fun breakpointsMuted(block: @MarsListenerDsl XDebugSessionListener.(Boolean) -> Unit) {
    breakpointsMutedAction = block
  }

  internal fun build(): XDebugSessionListener {
    val sessionPausedCallback = sessionPausedAction
    val sessionResumedCallback = sessionResumedAction
    val sessionStoppedCallback = sessionStoppedAction
    val stackFrameChangedCallback = stackFrameChangedAction
    val beforeSessionResumeCallback = beforeSessionResumeAction
    val settingsChangedCallback = settingsChangedAction
    val breakpointsMutedCallback = breakpointsMutedAction
    return object : XDebugSessionListener {
      override fun sessionPaused() = sessionPausedCallback(this)

      override fun sessionResumed() = sessionResumedCallback(this)

      override fun sessionStopped() = sessionStoppedCallback(this)

      override fun stackFrameChanged() = stackFrameChangedCallback(this)

      override fun beforeSessionResume() = beforeSessionResumeCallback(this)

      override fun settingsChanged() = settingsChangedCallback(this)

      override fun breakpointsMuted(muted: Boolean) = breakpointsMutedCallback(this, muted)
    }
  }
}

internal fun debugSessionListener(
    configure: DebugSessionListenerBuilder.() -> Unit,
): XDebugSessionListener = DebugSessionListenerBuilder().apply(configure).build()
