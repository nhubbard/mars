package edu.missouristate.mars.simulator;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.MarsContext;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

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
  private long instructionLimit;
  private long instructionsRemaining;

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

  /** Sets the instruction budget for each program before this controller starts. Zero is unlimited. */
  public synchronized void setInstructionLimit(long limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("Instruction limit cannot be negative");
    }
    if (program != null) {
      throw new IllegalStateException("Set the instruction limit before starting the simulator");
    }
    instructionLimit = limit;
  }

  private boolean instructionLimitReached() {
    return instructionLimit > 0 && instructionsRemaining == 0;
  }

  private volatile java.util.function.DoubleSupplier runSpeed = () -> Double.POSITIVE_INFINITY;

  /** Instructions per second; a nonpositive or infinite rate executes without throttling. */
  public void setRunSpeedSource(java.util.function.DoubleSupplier source) {
    runSpeed = Objects.requireNonNull(source);
  }

  private void awaitNextInstruction() {
    double rate = runSpeed.getAsDouble();
    if (rate <= 0 || !Double.isFinite(rate)) return;
    long delay = (long) Math.min(1_000_000_000.0 / rate, Long.MAX_VALUE / 2.0);
    long start = System.nanoTime();
    while (request == Request.NONE && System.nanoTime() - start < delay) {
      java.util.concurrent.locks.LockSupport.parkNanos(
          Math.min(10_000_000, delay - (System.nanoTime() - start)));
    }
  }

  /** Completes after the stop callbacks; nonpositive limits mean unlimited execution. */
  public CompletableFuture<StopEvent> runAsync(long maximumSteps, int[] breakpoints) {
    int[] points = breakpoints == null ? new int[0] : breakpoints.clone();
    Arrays.sort(points);
    return startAsync(maximumSteps, points, null);
  }

  /**
   * Checks the supplied predicate on the simulator worker before each instruction, including the
   * first one. A match suspends execution at that PC without executing the instruction. A debugger
   * resuming at a hit can have its predicate ignore that PC on the first check. Nonpositive step
   * limits mean unlimited execution. The future completes after the stop callbacks.
   */
  public CompletableFuture<StopEvent> runUntilAsync(long maximumSteps, IntPredicate shouldBreak) {
    return startAsync(maximumSteps, null, Objects.requireNonNull(shouldBreak));
  }

  private synchronized CompletableFuture<StopEvent> startAsync(
      long maximumSteps, int[] points, IntPredicate shouldBreak) {
    if (closed || session.isClosing()) {
      throw new IllegalStateException("Simulator is closed");
    }
    if (state == SimulatorState.RUNNING) {
      throw new IllegalStateException("Simulator is running");
    }
    var current = session.getProgram();
    if (current == null) {
      throw new IllegalStateException("No program assembled");
    }
    if (current != program) {
      program = current;
      stepCount = 0;
      instructionsRemaining = instructionLimit;
      state = SimulatorState.IDLE;
    }
    if (state == SimulatorState.STOPPED || instructionLimitReached()) {
      throw new IllegalStateException("Program has stopped");
    }
    request = Request.NONE;
    state = SimulatorState.RUNNING;
    var completion = new CompletableFuture<StopEvent>();
    executor.execute(() -> execute(current, maximumSteps, points, shouldBreak, completion));
    return completion;
  }

  private void execute(
      MIPSprogram current,
      long maximumSteps,
      int[] points,
      IntPredicate shouldBreak,
      CompletableFuture<StopEvent> completion) {
    worker = Thread.currentThread();
    StopEvent.Reason reason;
    boolean acquired = false;
    try (var _ = session.claimExecution()) {
      acquired = true;
      if (current != session.getProgram()) {
        throw new IllegalStateException("Program was replaced");
      }
      long steps = 0;
      while (true) {
        if (request != Request.NONE) {
          reason = request == Request.STOP ? StopEvent.Reason.STOP_REQUEST : StopEvent.Reason.PAUSE;
          break;
        }
        if (shouldBreak != null) {
          boolean hit = shouldBreak.test(session.registerFile.getPC());
          if (request != Request.NONE) {
            continue;
          }
          if (hit) {
            reason = StopEvent.Reason.BREAKPOINT;
            break;
          }
        }
        var result = current.executeSessionInstruction();
        if (result.executed()) {
          steps++;
          stepCount++;
          if (instructionLimit > 0) {
            instructionsRemaining--;
          }
          var event = new StepEvent(
              result.pc(), result.instruction(), stepCount, result.transfer(), result.branchOutcome());
          notifyListeners(listener -> listener.onStep(event));
        }
        if (result.stopReason() != null) {
          reason = result.stopReason();
          break;
        }
        if (instructionLimitReached()) {
          reason = StopEvent.Reason.INSTRUCTION_LIMIT;
          break;
        }
        if (request != Request.NONE) {
          continue;
        }
        // The array-based API skips the initial PC so the desktop can resume from a breakpoint.
        if (points != null && Arrays.binarySearch(points, session.registerFile.getPC()) >= 0) {
          reason = StopEvent.Reason.BREAKPOINT;
          break;
        }
        if (maximumSteps > 0 && steps >= maximumSteps) {
          reason = StopEvent.Reason.STEP_LIMIT;
          break;
        }
        awaitNextInstruction();
      }
      if (reason != StopEvent.Reason.PAUSE
          && reason != StopEvent.Reason.BREAKPOINT
          && reason != StopEvent.Reason.STEP_LIMIT) {
        session.syscallEnvironment.close();
      }
    } catch (Exception error) {
      if (acquired) {
        session.syscallEnvironment.close();
      }
      reason = StopEvent.Reason.EXCEPTION;
      notifyListeners(listener -> listener.onError(new ErrorEvent(error.getMessage(), error)));
    }
    var event = new StopEvent(reason, current.getExitCode());
    synchronized (this) {
      state =
          switch (reason) {
            case PAUSE, BREAKPOINT, STEP_LIMIT, BACKSTEP -> SimulatorState.PAUSED;
            default -> SimulatorState.STOPPED;
          };
    }
    notifyListeners(listener -> listener.onStop(event));
    completion.complete(event);
  }

  @Override
  public synchronized void pause() {
    if (state == SimulatorState.RUNNING && request != Request.STOP) {
      request = Request.PAUSE;
    }
  }

  @Override
  public synchronized void stop() {
    if (closed) {
      return;
    }
    if (state == SimulatorState.RUNNING) {
      request = Request.STOP;
    } else {
      state = SimulatorState.RUNNING;
      executor.execute(
          () -> {
            worker = Thread.currentThread();
            StopEvent.Reason reason = StopEvent.Reason.STOP_REQUEST;
            try (var _ = session.claimExecution()) {
              session.syscallEnvironment.close();
            } catch (Exception error) {
              reason = StopEvent.Reason.EXCEPTION;
              notifyListeners(
                  listener -> listener.onError(new ErrorEvent(error.getMessage(), error)));
            }
            state = SimulatorState.STOPPED;
            var event = new StopEvent(reason, program == null ? 0 : program.getExitCode());
            notifyListeners(listener -> listener.onStop(event));
          });
    }
  }

  @Override
  public void backstep() {
    backstepAsync();
  }

  public CompletableFuture<Void> backstepAsync() {
    return backstepAsync(false);
  }

  /** Undoes the most recently executed source statement, or one instruction without source. */
  public CompletableFuture<Void> backstepSourceAsync() {
    return backstepAsync(true);
  }

  private synchronized CompletableFuture<Void> backstepAsync(boolean wholeSource) {
    if (closed || session.isClosing()) {
      throw new IllegalStateException("Simulator is closed");
    }
    if (state == SimulatorState.RUNNING) {
      throw new IllegalStateException("Simulator is running");
    }
    var current = session.getProgram();
    if (current == null) {
      return CompletableFuture.completedFuture(null);
    }
    if (program != current) {
      program = current;
      stepCount = 0;
      instructionsRemaining = instructionLimit;
    }
    var completion = new CompletableFuture<Void>();
    request = Request.NONE;
    state = SimulatorState.RUNNING;
    executor.execute(
        () -> {
          worker = Thread.currentThread();
          try (var _ = session.claimExecution()) {
            if (program != session.getProgram()) {
              throw new IllegalStateException("Program was replaced");
            }
            long previousStepCount = stepCount;
            undo(wholeSource);
            if (stepCount != previousStepCount) {
              notifyListeners(listener -> listener.onBackstep(stepCount));
            }
          } catch (Exception error) {
            state = SimulatorState.STOPPED;
            notifyListeners(
                listener -> listener.onError(new ErrorEvent(error.getMessage(), error)));
            notifyListeners(
                listener ->
                    listener.onStop(
                        new StopEvent(
                            StopEvent.Reason.EXCEPTION,
                            program == null ? 0 : program.getExitCode())));
            completion.completeExceptionally(error);
            return;
          }
          state = SimulatorState.PAUSED;
          notifyListeners(
              listener ->
                  listener.onStop(new StopEvent(StopEvent.Reason.BACKSTEP, program.getExitCode())));
          completion.complete(null);
        });
    return completion;
  }

  private void undo(boolean wholeSource) {
    var history = session.backStepper;
    var instruction = wholeSource ? history.previousInstruction() : null;
    while (history.canBackstep() && request == Request.NONE) {
      history.backStep();
      if (stepCount > 0) {
        stepCount--;
      }
      if (!wholeSource) {
        return;
      }
      var earlier = history.previousInstruction();
      if (instruction == null
          || instruction.getInstructionOrigin() == null
          || earlier == null
          || earlier.getInstructionOrigin() != instruction.getInstructionOrigin()
          || Integer.compareUnsigned(earlier.getAddress(), instruction.getAddress()) >= 0) {
        return;
      }
      // An earlier emitted instruction can belong to the same execution. A loop to the
      // same or a lower address begins another execution of the source statement.
      instruction = earlier;
    }
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
    if (closed) {
      return;
    }
    closed = true;
    request = Request.STOP;
    executor.execute(
        () -> {
          session.releaseDefaultResources();
          state = SimulatorState.STOPPED;
        });
    executor.shutdown();
  }
}
