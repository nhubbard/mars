package edu.missouristate.mars.simulator;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.MarsContext;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One session's asynchronous execution controller. Instructions, undo and callbacks run on a single
 * worker. Frontends dispatch callbacks to their own UI threads. Close requests a stop at the next
 * instruction boundary and releases default file handles after the worker has finished.
 */
public final class SimulatorEngine implements MarsSimulator, AutoCloseable {
  private enum Request {
    NONE,
    PAUSE,
    STOP
  }

  private final MarsContext session;
  private final ExecutorService executor;
  private final CopyOnWriteArrayList<SimulatorListener> listeners = new CopyOnWriteArrayList<>();
  private volatile SimulatorState state = SimulatorState.IDLE;
  private volatile Request request = Request.NONE;
  private boolean closed;
  private volatile Thread worker;
  private MIPSprogram program;
  private long stepCount;

  public SimulatorEngine(MarsContext session) {
    this.session = Objects.requireNonNull(session);
    executor =
        Executors.newSingleThreadExecutor(
            task -> {
              var thread = new Thread(task, "mars-simulator");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void run() {
    runAsync(0, null);
  }

  @Override
  public void step() {
    runAsync(1, null);
  }

  /** Completes after the stop callbacks; nonpositive limits mean unlimited execution. */
  public synchronized CompletableFuture<StopEvent> runAsync(long maximumSteps, int[] breakpoints) {
    if (closed) throw new IllegalStateException("Simulator is closed");
    if (state == SimulatorState.RUNNING) throw new IllegalStateException("Simulator is running");
    var current = session.getProgram();
    if (current == null) throw new IllegalStateException("No program assembled");
    if (current != program) {
      program = current;
      stepCount = 0;
      state = SimulatorState.IDLE;
    }
    if (state == SimulatorState.STOPPED) throw new IllegalStateException("Program has stopped");
    int[] points = breakpoints == null ? new int[0] : breakpoints.clone();
    Arrays.sort(points);
    request = Request.NONE;
    state = SimulatorState.RUNNING;
    var completion = new CompletableFuture<StopEvent>();
    executor.execute(() -> execute(current, maximumSteps, points, completion));
    return completion;
  }

  private void execute(
      MIPSprogram current,
      long maximumSteps,
      int[] points,
      CompletableFuture<StopEvent> completion) {
    worker = Thread.currentThread();
    StopEvent.Reason reason = StopEvent.Reason.EXCEPTION;
    boolean acquired = false;
    try (var lease = session.claimExecution()) {
      acquired = true;
      if (current != session.getProgram()) throw new IllegalStateException("Program was replaced");
      long steps = 0;
      while (true) {
        if (request != Request.NONE) {
          reason = request == Request.STOP ? StopEvent.Reason.STOP_REQUEST : StopEvent.Reason.PAUSE;
          break;
        }
        var result = current.executeSessionInstruction();
        if (result.executed()) {
          steps++;
          stepCount++;
          var event = new StepEvent(result.pc(), result.instruction(), stepCount);
          notifyListeners(listener -> listener.onStep(event));
        }
        if (result.stopReason() != null) {
          reason = result.stopReason();
          break;
        }
        if (request != Request.NONE) continue;
        if (Arrays.binarySearch(points, session.registerFile.getPC()) >= 0) {
          reason = StopEvent.Reason.BREAKPOINT;
          break;
        }
        if (maximumSteps > 0 && steps >= maximumSteps) {
          reason = StopEvent.Reason.STEP_LIMIT;
          break;
        }
      }
      if (reason != StopEvent.Reason.PAUSE
          && reason != StopEvent.Reason.BREAKPOINT
          && reason != StopEvent.Reason.STEP_LIMIT) session.syscallEnvironment.close();
    } catch (Exception error) {
      if (acquired) session.syscallEnvironment.close();
      reason = StopEvent.Reason.EXCEPTION;
      notifyListeners(listener -> listener.onError(new ErrorEvent(error.getMessage(), error)));
    }
    var event = new StopEvent(reason, current.getExitCode());
    synchronized (this) {
      state =
          switch (reason) {
            case PAUSE, BREAKPOINT, STEP_LIMIT -> SimulatorState.PAUSED;
            default -> SimulatorState.STOPPED;
          };
    }
    notifyListeners(listener -> listener.onStop(event));
    completion.complete(event);
  }

  @Override
  public synchronized void pause() {
    if (state == SimulatorState.RUNNING && request != Request.STOP) request = Request.PAUSE;
  }

  @Override
  public synchronized void stop() {
    if (closed) return;
    if (state == SimulatorState.RUNNING) request = Request.STOP;
    else {
      state = SimulatorState.RUNNING;
      executor.execute(
          () -> {
            worker = Thread.currentThread();
            session.syscallEnvironment.close();
            state = SimulatorState.STOPPED;
            notifyListeners(
                listener ->
                    listener.onStop(
                        new StopEvent(
                            StopEvent.Reason.STOP_REQUEST,
                            program == null ? 0 : program.getExitCode())));
          });
    }
  }

  @Override
  public void backstep() {
    backstepAsync();
  }

  public synchronized CompletableFuture<Void> backstepAsync() {
    if (closed) throw new IllegalStateException("Simulator is closed");
    if (state == SimulatorState.RUNNING) throw new IllegalStateException("Simulator is running");
    var current = session.getProgram();
    if (current == null) return CompletableFuture.completedFuture(null);
    if (program != current) {
      program = current;
      stepCount = 0;
    }
    var completion = new CompletableFuture<Void>();
    state = SimulatorState.RUNNING;
    executor.execute(
        () -> {
          worker = Thread.currentThread();
          try (var lease = session.claimExecution()) {
            if (program != session.getProgram())
              throw new IllegalStateException("Program was replaced");
            if (!session.backStepper.empty()) {
              session.backStepper.backStep();
              if (stepCount > 0) stepCount--;
            }
            state = SimulatorState.PAUSED;
            completion.complete(null);
          } catch (Exception error) {
            state = SimulatorState.STOPPED;
            notifyListeners(
                listener -> listener.onError(new ErrorEvent(error.getMessage(), error)));
            completion.completeExceptionally(error);
          }
        });
    return completion;
  }

  /** Used by the session to reject mutation while a worker owns execution. */
  public boolean executingOnAnotherThread() {
    return state == SimulatorState.RUNNING && worker != Thread.currentThread();
  }

  @Override
  public SimulatorState state() {
    return state;
  }

  @Override
  public void addListener(SimulatorListener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  @Override
  public void removeListener(SimulatorListener listener) {
    listeners.remove(listener);
  }

  private void notifyListeners(Consumer<SimulatorListener> callback) {
    for (var listener : listeners) {
      try {
        callback.accept(listener);
      } catch (RuntimeException error) {
        System.getLogger(SimulatorEngine.class.getName())
            .log(System.Logger.Level.WARNING, "Simulator listener failed", error);
      }
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    request = Request.STOP;
    executor.execute(
        () -> {
          session.syscallEnvironment.close();
          state = SimulatorState.STOPPED;
        });
    executor.shutdown();
  }
}
