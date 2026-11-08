package edu.missouristate.mars.desktop.runtime;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.simulator.SimulatorEngine;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Desktop presentation and input cancellation around the session's single execution engine. */
@Singleton
public final class DesktopSimulation {
  public static final double UNLIMITED_SPEED = 40;
  public static final int BREAKPOINT = 1,
      EXCEPTION = 2,
      MAX_STEPS = 3,
      NORMAL_TERMINATION = 4,
      CLIFF_TERMINATION = 5,
      PAUSE_OR_STOP = 6;

  public interface SimFinishCallback {
    void stepped(boolean done, int reason, ProcessingException error);

    void stopped(ProcessingException error, int reason);

    void paused(boolean done, int reason, ProcessingException error);
  }

  public interface StopListener {
    void stopped(DesktopSimulation simulation);
  }

  private final MarsContext context;
  private final SimulatorEngine engine;
  private final CopyOnWriteArrayList<Consumer<ExecutionNotice>> listeners =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<StopListener> stopListeners = new CopyOnWriteArrayList<>();
  private volatile Consumer<Runnable> dispatcher = Runnable::run;
  private volatile Supplier<Double> speed = () -> UNLIMITED_SPEED;
  private volatile SimulatorObserver observer;
  private volatile ProcessingException failure;
  private volatile SimulatorActionSource starter;
  private volatile int maximumSteps;

  @Inject
  public DesktopSimulation(MarsContext context) {
    this.context = context;
    engine = context.simulator();
    engine.setRunSpeedSource(
        () -> speed.get() >= UNLIMITED_SPEED ? Double.POSITIVE_INFINITY : speed.get());
    engine.addListener(
        new SimulatorListener() {
          public void onStep(StepEvent event) {
            var current = observer;
            if (current != null && speed.get() < UNLIMITED_SPEED)
              current.onStepComplete(event.pc());
          }

          public void onError(ErrorEvent event) {
            if (event.cause() instanceof ProcessingException error) failure = error;
            else {
              var errors = new ErrorList(context.settings);
              errors.add(new ErrorMessage((MIPSprogram) null, 0, 0, event.message()));
              failure = new ProcessingException(errors);
            }
          }

          public void onStop(StopEvent event) {
            emit(ExecutionNotice.SIMULATOR_STOP);
            var current = observer;
            if (current != null) current.onSimulationStop(event);
            var actor = starter;
            var error = failure;
            dispatcher.accept(() -> finish(actor, event, error));
          }
        });
  }

  public void setCompletionDispatcher(Consumer<Runnable> dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
  }

  public void setRunSpeedSource(Supplier<Double> speed) {
    this.speed = Objects.requireNonNull(speed);
  }

  public void setObserver(SimulatorObserver observer) {
    this.observer = observer;
  }

  public void addSimulatorListener(Consumer<ExecutionNotice> listener) {
    listeners.add(listener);
  }

  public void removeSimulatorListener(Consumer<ExecutionNotice> listener) {
    listeners.remove(listener);
  }

  public void addStopListener(StopListener listener) {
    stopListeners.add(listener);
  }

  public void removeStopListener(StopListener listener) {
    stopListeners.remove(listener);
  }

  public boolean simulate(
      MIPSprogram program, int pc, int limit, int[] points, SimulatorActionSource actor)
      throws ProcessingException {
    if (program != context.getProgram())
      throw new IllegalArgumentException("Program belongs to another session");
    if (engine.state() == SimulatorState.RUNNING)
      throw new IllegalStateException("Simulator is running");
    context.registerFile.initializeProgramCounter(pc);
    failure = null;
    starter = actor;
    maximumSteps = limit;
    emit(ExecutionNotice.SIMULATOR_START);
    java.util.concurrent.CompletableFuture<StopEvent> completion;
    try {
      completion = engine.runAsync(limit, points);
    } catch (RuntimeException error) {
      emit(ExecutionNotice.SIMULATOR_STOP);
      throw error;
    }
    if (actor != null) return true;
    try {
      var event = completion.get();
      if (failure != null) throw failure;
      return isDone(event);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      stopExecution(null);
      throw new IllegalStateException("Interrupted waiting for simulation", error);
    } catch (ExecutionException error) {
      throw new IllegalStateException(error.getCause());
    }
  }

  public void stopExecution(SimulatorActionSource actor) {
    if (engine.state() != SimulatorState.RUNNING) return;
    if (actor != null && "Pause".equals(actor.getActionName())) engine.pause();
    else engine.stop();
    for (var listener : stopListeners) listener.stopped(this);
  }

  private void emit(int action) {
    var notice =
        new ExecutionNotice(action, maximumSteps, speed.get(), context.registerFile.getPC());
    for (var listener : listeners) listener.accept(notice);
  }

  private static boolean isDone(StopEvent event) {
    return switch (event.reason()) {
      case PAUSE, BREAKPOINT, STEP_LIMIT, BACKSTEP -> false;
      default -> true;
    };
  }

  private static void finish(
      SimulatorActionSource actor, StopEvent event, ProcessingException error) {
    if (!(actor instanceof SimFinishCallback callback)) return;
    int reason =
        switch (event.reason()) {
          case BREAKPOINT -> BREAKPOINT;
          case EXCEPTION -> EXCEPTION;
          case STEP_LIMIT -> MAX_STEPS;
          case NORMAL_EXIT -> NORMAL_TERMINATION;
          case CLIFF_TERMINATION -> CLIFF_TERMINATION;
          default -> PAUSE_OR_STOP;
        };
    if ("Step".equals(actor.getActionName())) callback.stepped(isDone(event), reason, error);
    else if (event.reason() == StopEvent.Reason.PAUSE
        || event.reason() == StopEvent.Reason.BREAKPOINT) callback.paused(false, reason, error);
    else callback.stopped(error, reason);
  }
}
