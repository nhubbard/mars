package edu.missouristate.mars.api

@MarsListenerDsl
class SimulatorListenerBuilder internal constructor() {
  private var stepAction: SimulatorListener.(StepEvent) -> Unit = { _ -> }
  private var backstepAction: SimulatorListener.(Long) -> Unit = { _ -> }
  private var stopAction: SimulatorListener.(StopEvent) -> Unit = { _ -> }
  private var errorAction: SimulatorListener.(ErrorEvent) -> Unit = { _ -> }

  fun onStep(block: @MarsListenerDsl SimulatorListener.(StepEvent) -> Unit) {
    stepAction = block
  }

  fun onBackstep(block: @MarsListenerDsl SimulatorListener.(Long) -> Unit) {
    backstepAction = block
  }

  fun onStop(block: @MarsListenerDsl SimulatorListener.(StopEvent) -> Unit) {
    stopAction = block
  }

  fun onError(block: @MarsListenerDsl SimulatorListener.(ErrorEvent) -> Unit) {
    errorAction = block
  }

  internal fun build(): SimulatorListener {
    val stepCallback = stepAction
    val backstepCallback = backstepAction
    val stopCallback = stopAction
    val errorCallback = errorAction
    return object : SimulatorListener {
      override fun onStep(event: StepEvent) = stepCallback(this, event)

      override fun onBackstep(stepCount: Long) = backstepCallback(this, stepCount)

      override fun onStop(event: StopEvent) = stopCallback(this, event)

      override fun onError(event: ErrorEvent) = errorCallback(this, event)
    }
  }
}

fun simulatorListener(configure: SimulatorListenerBuilder.() -> Unit): SimulatorListener =
    SimulatorListenerBuilder().apply(configure).build()
