package edu.missouristate.mars.plugin.run

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import edu.missouristate.mars.api.MarsListenerDsl

@MarsListenerDsl
internal class ExecutionListenerBuilder internal constructor() {
  private var scheduledAction: ExecutionListener.(String, ExecutionEnvironment) -> Unit = { _, _ ->
  }
  private var startingAction: ExecutionListener.(String, ExecutionEnvironment) -> Unit = { _, _ -> }
  private var notStartedAction: ExecutionListener.(String, ExecutionEnvironment) -> Unit = { _, _ ->
  }
  private var notStartedCauseAction:
      (ExecutionListener.(String, ExecutionEnvironment, Throwable?) -> Unit)? =
      null
  private var startingHandlerAction:
      ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit =
      { _, _, _ ->
      }
  private var startedAction:
      ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit =
      { _, _, _ ->
      }
  private var terminatingAction:
      (ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit)? =
      null
  private var terminatedAction:
      (ExecutionListener.(String, ExecutionEnvironment, ProcessHandler, Int) -> Unit)? =
      null

  fun processStartScheduled(
      block: @MarsListenerDsl ExecutionListener.(String, ExecutionEnvironment) -> Unit,
  ) {
    scheduledAction = block
  }

  fun processStarting(
      block: @MarsListenerDsl ExecutionListener.(String, ExecutionEnvironment) -> Unit,
  ) {
    startingAction = block
  }

  fun processNotStarted(
      block: @MarsListenerDsl ExecutionListener.(String, ExecutionEnvironment) -> Unit,
  ) {
    notStartedAction = block
  }

  fun processNotStarted(
      block: @MarsListenerDsl ExecutionListener.(String, ExecutionEnvironment, Throwable?) -> Unit,
  ) {
    notStartedCauseAction = block
  }

  fun processStarting(
      block:
          @MarsListenerDsl
          ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit,
  ) {
    startingHandlerAction = block
  }

  fun processStarted(
      block:
          @MarsListenerDsl
          ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit,
  ) {
    startedAction = block
  }

  fun processTerminating(
      block:
          @MarsListenerDsl
          ExecutionListener.(String, ExecutionEnvironment, ProcessHandler) -> Unit,
  ) {
    terminatingAction = block
  }

  fun processTerminated(
      block:
          @MarsListenerDsl
          ExecutionListener.(String, ExecutionEnvironment, ProcessHandler, Int) -> Unit,
  ) {
    terminatedAction = block
  }

  internal fun build(): ExecutionListener {
    val scheduledCallback = scheduledAction
    val startingCallback = startingAction
    val notStartedCallback = notStartedAction
    val notStartedCauseCallback = notStartedCauseAction
    val startingHandlerCallback = startingHandlerAction
    val startedCallback = startedAction
    val terminatingCallback = terminatingAction
    val terminatedCallback = terminatedAction
    return object : ExecutionListener {
      override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) =
          scheduledCallback(this, executorId, env)

      override fun processStarting(executorId: String, env: ExecutionEnvironment) =
          startingCallback(this, executorId, env)

      override fun processNotStarted(executorId: String, env: ExecutionEnvironment) =
          notStartedCallback(this, executorId, env)

      override fun processNotStarted(
          executorId: String,
          env: ExecutionEnvironment,
          cause: Throwable?,
      ) {
        val callback = notStartedCauseCallback
        if (callback != null) callback(this, executorId, env, cause)
        else super.processNotStarted(executorId, env, cause)
      }

      override fun processStarting(
          executorId: String,
          env: ExecutionEnvironment,
          handler: ProcessHandler,
      ) = startingHandlerCallback(this, executorId, env, handler)

      override fun processStarted(
          executorId: String,
          env: ExecutionEnvironment,
          handler: ProcessHandler,
      ) = startedCallback(this, executorId, env, handler)

      override fun processTerminating(
          executorId: String,
          env: ExecutionEnvironment,
          handler: ProcessHandler,
      ) {
        val callback = terminatingCallback
        if (callback != null) callback(this, executorId, env, handler)
        else super.processTerminating(executorId, env, handler)
      }

      override fun processTerminated(
          executorId: String,
          env: ExecutionEnvironment,
          handler: ProcessHandler,
          exitCode: Int,
      ) {
        val callback = terminatedCallback
        if (callback != null) callback(this, executorId, env, handler, exitCode)
        else super.processTerminated(executorId, env, handler, exitCode)
      }
    }
  }
}

internal fun executionListener(configure: ExecutionListenerBuilder.() -> Unit): ExecutionListener =
    ExecutionListenerBuilder().apply(configure).build()
