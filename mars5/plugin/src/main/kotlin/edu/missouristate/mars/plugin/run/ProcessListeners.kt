package edu.missouristate.mars.plugin.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import edu.missouristate.mars.api.MarsListenerDsl

@MarsListenerDsl
internal class ProcessListenerBuilder internal constructor() {
  private var startNotifiedAction: ProcessListener.(ProcessEvent) -> Unit = { _ -> }
  private var processTerminatedAction: ProcessListener.(ProcessEvent) -> Unit = { _ -> }
  private var processWillTerminateAction: ProcessListener.(ProcessEvent, Boolean) -> Unit =
      { _, _ ->
      }
  private var onTextAvailableAction: ProcessListener.(ProcessEvent, Key<*>) -> Unit = { _, _ -> }
  private var processNotStartedAction: ProcessListener.() -> Unit = {}

  fun startNotified(block: @MarsListenerDsl ProcessListener.(ProcessEvent) -> Unit) {
    startNotifiedAction = block
  }

  fun processTerminated(block: @MarsListenerDsl ProcessListener.(ProcessEvent) -> Unit) {
    processTerminatedAction = block
  }

  fun processWillTerminate(
      block: @MarsListenerDsl ProcessListener.(ProcessEvent, Boolean) -> Unit,
  ) {
    processWillTerminateAction = block
  }

  fun onTextAvailable(block: @MarsListenerDsl ProcessListener.(ProcessEvent, Key<*>) -> Unit) {
    onTextAvailableAction = block
  }

  fun processNotStarted(block: @MarsListenerDsl ProcessListener.() -> Unit) {
    processNotStartedAction = block
  }

  internal fun build(): ProcessListener {
    val startNotifiedCallback = startNotifiedAction
    val processTerminatedCallback = processTerminatedAction
    val processWillTerminateCallback = processWillTerminateAction
    val onTextAvailableCallback = onTextAvailableAction
    val processNotStartedCallback = processNotStartedAction
    return object : ProcessListener {
      override fun startNotified(event: ProcessEvent) = startNotifiedCallback(this, event)

      override fun processTerminated(event: ProcessEvent) = processTerminatedCallback(this, event)

      override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) =
          processWillTerminateCallback(this, event, willBeDestroyed)

      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
          onTextAvailableCallback(this, event, outputType)

      override fun processNotStarted() = processNotStartedCallback(this)
    }
  }
}

internal fun processListener(configure: ProcessListenerBuilder.() -> Unit): ProcessListener =
    ProcessListenerBuilder().apply(configure).build()
