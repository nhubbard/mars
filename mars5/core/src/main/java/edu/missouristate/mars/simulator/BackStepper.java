package edu.missouristate.mars.simulator;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.*;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Used to "step backward" through execution, undoing each instruction in its owning hardware. The
 * constructor binds every operation to explicit hardware. Framed session execution groups writes by
 * instruction invocation, including repeated visits to the same statement.
 *
 * @author Pete Sanderson
 * @version February 2006
 */
public class BackStepper {
  // The types of "undo" actions.  Under 1.5, these would be enumerated type.
  // These fit better in the BackStep class below but inner classes cannot have static members.
  private static final int MEMORY_RESTORE_RAW_WORD = 0;
  private static final int MEMORY_RESTORE_WORD = 1;
  private static final int MEMORY_RESTORE_HALF = 2;
  private static final int MEMORY_RESTORE_BYTE = 3;
  private static final int REGISTER_RESTORE = 4;
  private static final int PC_RESTORE = 5;
  private static final int COPROC0_REGISTER_RESTORE = 6;
  private static final int COPROC1_REGISTER_RESTORE = 7;
  private static final int COPROC1_CONDITION_CLEAR = 8;
  private static final int COPROC1_CONDITION_SET = 9;
  private static final int HEAP_RESTORE = 11;
  private static final int DO_NOTHING = 10; // instruction does not write anything.

  // Flag to mark BackStep object as prepresenting specific situation: user manipulates
  // memory/register value via GUI after assembling program but before running it.
  private static final int NOT_PC_VALUE = -1;

  private volatile boolean engaged;
  private final MemoryState memory;
  private final IntegerRegisterFile registerFile;
  private final Coprocessor0State coprocessor0;
  private final Coprocessor1State coprocessor1;
  private final DelayedBranchState delayedBranch;
  private long nextFrame;
  private long activeFrame;
  private int framePc;
  private ProgramStatement frameStatement;
  private DelayedBranchState.Snapshot frameBranch;
  private Runnable frameRestore;
  private boolean frameDiscarded;
  private boolean frameInDelaySlot;
  private final BackstepStack backSteps;
  private final CopyOnWriteArrayList<Supplier<Runnable>> checkpoints = new CopyOnWriteArrayList<>();

  // One can argue using java.util.Stack, given its clumsy implementation.
  // A homegrown linked implementation will be more streamlined, but
  // I anticipate that backstepping will only be used during timed
  // (currently max 30 instructions/second) or stepped execution, where
  // performance is not an issue.  Its Vector implementation may result
  // in quicker garbage collection than a pure linked list implementation.

  /**
   * Create a fresh BackStepper. It is enabled, which means all subsequent instruction executions
   * will have their "undo" action recorded here.
   */
  /** Creates an undo history bound to explicit hardware, without global simulation state. */
  public BackStepper(
      MemoryState memory,
      IntegerRegisterFile registerFile,
      Coprocessor0State coprocessor0,
      Coprocessor1State coprocessor1,
      DelayedBranchState delayedBranch,
      int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Backstep capacity must be positive");
    }
    this.memory = Objects.requireNonNull(memory);
    this.registerFile = Objects.requireNonNull(registerFile);
    this.coprocessor0 = Objects.requireNonNull(coprocessor0);
    this.coprocessor1 = Objects.requireNonNull(coprocessor1);
    this.delayedBranch = Objects.requireNonNull(delayedBranch);
    engaged = true;
    backSteps = new BackstepStack(capacity);
  }

  /**
   * Captures an attached device's undo action before each recorded instruction. The action runs
   * after the CPU and memory have been restored. Returning null skips that instruction.
   */
  public AutoCloseable addInstructionCheckpoint(Supplier<Runnable> capture) {
    checkpoints.add(Objects.requireNonNull(capture));
    return () -> checkpoints.remove(capture);
  }

  /** Starts one instruction's undo group before the program counter is incremented. */
  public boolean beginInstruction(int pc, ProgramStatement statement) {
    if (activeFrame != 0) {
      throw new IllegalStateException("An instruction is already executing");
    }
    if (!engaged) {
      return false;
    }
    Objects.requireNonNull(statement);
    Runnable restore = null;
    for (var checkpoint : checkpoints) {
      var next = checkpoint.get();
      if (next != null) {
        var previous = restore;
        restore = previous == null ? next : () -> {
          previous.run();
          next.run();
        };
      }
    }
    activeFrame = ++nextFrame;
    framePc = pc;
    frameStatement = statement;
    frameBranch = delayedBranch.snapshot();
    frameRestore = restore;
    frameInDelaySlot = delayedBranch.isTriggered();
    frameDiscarded = false;
    return true;
  }

  /** Completes a group, retaining a step even for an instruction with no writes. */
  public void endInstruction() {
    if (activeFrame == 0) {
      return;
    }
    try {
      if (engaged
          && !frameDiscarded
          && (backSteps.empty() || backSteps.peek().frame != activeFrame)) {
        backSteps.push(DO_NOTHING, framePc);
      }
    } finally {
      activeFrame = 0;
      frameStatement = null;
      frameBranch = null;
      frameRestore = null;
      frameDiscarded = false;
    }
  }

  /** Clears history after loading a program or resetting session state. */
  public void clear() {
    if (activeFrame != 0) {
      throw new IllegalStateException("Cannot clear history during an instruction");
    }
    backSteps.clear();
  }

  /**
   * Determine whether execution "undo" steps are currently being recorded.
   *
   * @return true if undo steps being recorded, false if not.
   */
  public boolean enabled() {
    return engaged;
  }

  /**
   * Set enable status.
   *
   * @param state If true, will begin (or continue) recoding "undo" steps. If false, will stop.
   */
  public void setEnabled(boolean state) {
    engaged = state;
  }

  /**
   * Test whether there are steps that can be undone.
   *
   * @return true if there are no steps to be undone, false otherwise.
   */
  public boolean empty() {
    return backSteps.empty();
  }

  /** Returns whether a stopped session can undo its most recent instruction. */
  public boolean canBackstep() {
    return engaged && !empty();
  }

  /** Upper bound on the number of undoable instructions. */
  public int capacity() {
    return backSteps.capacity;
  }

  /** The instruction that actually ran, even when its code has since been overwritten. */
  ProgramStatement previousInstruction() {
    var step = previousInstructionStep();
    return step == null ? null : step.ps;
  }

  /** The last recorded instruction of a stopped session, or null when history is unavailable. */
  public ExecutedInstruction previousExecution() {
    var step = previousInstructionStep();
    return step == null ? null : new ExecutedInstruction(step.pc, step.inDelaySlot);
  }

  public record ExecutedInstruction(int address, boolean delaySlot) {}

  /** The newest retained program write of a stopped session, or null if none is recorded. */
  public MemoryWrite previousMemoryWrite() {
    return engaged ? backSteps.previousMemoryWrite() : null;
  }

  public record MemoryWrite(int address, int length) {}

  private BackStep previousInstructionStep() {
    if (!canBackstep()) {
      return null;
    }
    var step = backSteps.peek();
    return step.frame == 0 ? null : step;
  }

  /**
   * Determine whether the next back-step action occurred as the result of an instruction that
   * executed in the "delay slot" of a delayed branch.
   *
   * @return true if next backstep is instruction that executed in delay slot, false otherwise.
   */
  // Added 25 June 2007
  public boolean inDelaySlot() {
    return !empty() && backSteps.peek().inDelaySlot;
  }

  /**
   * Carry out a "back step", which will undo the latest execution step. Does nothing if
   * backstepping not enabled or if there are no steps to undo.
   */

  // Note that there may be more than one "step" in an instruction execution; for
  // instance the multiply, divide, and double-precision floating point operations
  // all store their result in register pairs which results in two store operations.
  // Both must be undone transparently, so we need to detect that multiple steps happen
  // together and carry out all of them here.
  // Use a do-while loop based on the backstep's program statement reference.

  public void backStep() {
    if (!canBackstep()) {
      return;
    }
    if (activeFrame != 0) {
      throw new IllegalStateException("Cannot undo an executing instruction");
    }
    BackStep first = backSteps.peek();
    ProgramStatement statement = first.ps;
    long frame = first.frame;
    DelayedBranchState.Snapshot branch = first.branch;
    Runnable restore = null;
    engaged = false;
    try {
      do {
        BackStep step = backSteps.pop();
        if (step.restore != null) {
          restore = step.restore;
          step.restore = null;
        }
        if (step.pc != NOT_PC_VALUE) {
          registerFile.setProgramCounter(step.pc);
        }
        switch (step.action) {
          case MEMORY_RESTORE_RAW_WORD -> memory.setRawWord(step.param1, step.param2);
          case MEMORY_RESTORE_WORD -> memory.setWord(step.param1, step.param2);
          case MEMORY_RESTORE_HALF -> memory.setHalf(step.param1, step.param2);
          case MEMORY_RESTORE_BYTE -> memory.setByte(step.param1, step.param2);
          case REGISTER_RESTORE -> registerFile.updateRegister(step.param1, step.param2);
          case PC_RESTORE -> registerFile.setProgramCounter(step.param1);
          case COPROC0_REGISTER_RESTORE -> coprocessor0.updateRegister(step.param1, step.param2);
          case COPROC1_REGISTER_RESTORE -> coprocessor1.updateRegister(step.param1, step.param2);
          case COPROC1_CONDITION_CLEAR -> coprocessor1.clearConditionFlag(step.param1);
          case COPROC1_CONDITION_SET -> coprocessor1.setConditionFlag(step.param1);
          case HEAP_RESTORE -> memory.heapAddress = step.param1;
          case DO_NOTHING -> {}
          default -> throw new IllegalStateException("Unknown undo action: " + step.action);
        }
      } while (!backSteps.empty()
          && (frame != 0
              ? backSteps.peek().frame == frame
              : backSteps.peek().frame == 0 && statement == backSteps.peek().ps));
      if (branch != null) {
        delayedBranch.restore(branch);
      }
      if (restore != null) {
        restore.run();
      }
    } catch (AddressErrorException error) {
      throw new IllegalStateException("Cannot restore memory while back-stepping", error);
    } finally {
      engaged = true;
    }
  }

  /* Convenience method called below to get program counter value.  If it needs to be
   * be modified (e.g. to subtract 4) that can be done here in one place.
   */

  private int pc() {
    // PC incremented prior to instruction simulation, so need to adjust for that.
    return activeFrame == 0
        ? registerFile.getProgramCounter() - Instruction.INSTRUCTION_LENGTH
        : framePc;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a raw
   * memory word value (setRawWord).
   *
   * @param address The affected memory address.
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addMemoryRestoreRawWord(int address, int value) {
    backSteps.push(MEMORY_RESTORE_RAW_WORD, pc(), address, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a memory
   * word value.
   *
   * @param address The affected memory address.
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addMemoryRestoreWord(int address, int value) {
    backSteps.push(MEMORY_RESTORE_WORD, pc(), address, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a memory
   * half-word value.
   *
   * @param address The affected memory address.
   * @param value The "restore" value to be stored there, in low order half.
   * @return the argument value
   */
  public int addMemoryRestoreHalf(int address, int value) {
    backSteps.push(MEMORY_RESTORE_HALF, pc(), address, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a memory
   * byte value.
   *
   * @param address The affected memory address.
   * @param value The "restore" value to be stored there, in low order byte.
   * @return the argument value
   */
  public int addMemoryRestoreByte(int address, int value) {
    backSteps.push(MEMORY_RESTORE_BYTE, pc(), address, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a register
   * file register value.
   *
   * @param register The affected register number.
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addRegisterFileRestore(int register, int value) {
    backSteps.push(REGISTER_RESTORE, pc(), register, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore the program
   * counter.
   *
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addPCRestore(int value) {
    // adjust for value reflecting incremented PC.
    value = activeFrame == 0 ? value - Instruction.INSTRUCTION_LENGTH : framePc;
    // Use "value" insead of "pc()" for second arg because the current program counter
    // returns branch target address at this point.
    backSteps.push(PC_RESTORE, value, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a
   * coprocessor 0 register value.
   *
   * @param register The affected register number.
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addCoprocessor0Restore(int register, int value) {
    backSteps.push(COPROC0_REGISTER_RESTORE, pc(), register, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to restore a
   * coprocessor 1 register value.
   *
   * @param register The affected register number.
   * @param value The "restore" value to be stored there.
   * @return the argument value
   */
  public int addCoprocessor1Restore(int register, int value) {
    backSteps.push(COPROC1_REGISTER_RESTORE, pc(), register, value);
    return value;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to set the given
   * coprocessor 1 condition flag (to 1).
   *
   * @param flag The condition flag number.
   * @return the argument value
   */
  public int addConditionFlagSet(int flag) {
    backSteps.push(COPROC1_CONDITION_SET, pc(), flag);
    return flag;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to clear the given
   * coprocessor 1 condition flag (to 0).
   *
   * @param flag The condition flag number.
   * @return the argument value
   */
  public int addConditionFlagClear(int flag) {
    backSteps.push(COPROC1_CONDITION_CLEAR, pc(), flag);
    return flag;
  }

  /**
   * Add a new "back step" (the undo action) to the stack. The action here is to do nothing! This is
   * just a place holder so when user is backstepping through the program no instructions will be
   * skipped. Cosmetic. If the top of the stack has the same PC counter, the do-nothing action will
   * not be added.
   *
   * @return 0
   */
  public int addDoNothing(int pc) {
    if (backSteps.empty() || backSteps.peek().pc != pc) {
      backSteps.push(DO_NOTHING, pc);
    }
    return 0;
  }

  /** Records the previous heap position for a session's allocation syscall. */
  public void addHeapRestore(int address) {
    backSteps.push(HEAP_RESTORE, pc(), address);
  }

  // Represents a "back step" (undo action) on the stack.
  private class BackStep {
    private long frame;
    private DelayedBranchState.Snapshot branch;
    private Runnable restore;
    private int action; // what do do MEMORY_RESTORE_WORD, etc
    private int pc; // program counter value when original step occurred
    private ProgramStatement ps; // statement whose action is being "undone" here
    private int param1; // first parameter required by that action
    private int param2; // optional second parameter required by that action
    private boolean
        inDelaySlot; // true if instruction executed in "delay slot" (delayed branching enabled)

    // it is critical that BackStep object get its values by calling this method
    // rather than assigning to individual members, because of the technique used
    // to set its ps member (and possibly pc).
    private void assign(int act, int programCounter, int parm1, int parm2) {
      action = act;
      pc = programCounter;
      frame = activeFrame;
      branch = frameBranch;
      // The first action is popped last. Keep the checkpoint on that action without adding a slot.
      restore = frameRestore;
      frameRestore = null;
      if (frame != 0) {
        pc = framePc;
        ps = frameStatement;
      } else {
        try {
          ps = memory.getStatementNoNotify(programCounter);
        } catch (Exception error) {
          ps = null;
          pc = NOT_PC_VALUE;
        }
      }
      param1 = parm1;
      param2 = parm2;
      inDelaySlot =
          frame != 0 ? frameInDelaySlot : delayedBranch.isTriggered(); // ADDED 25 June 2007
    }
  }

  // *****************************************************************************
  // special purpose stack class for backstepping.  You've heard of circular queues
  // implemented with an array, right?  This is a circular stack!  When full, the
  // newly-pushed item overwrites the oldest item, with circular top!  All operations
  // are constant time.  It's synchronized too, to be safe (is used by both the
  // simulation thread and the GUI thread for the back-step button).
  // Upon construction, it is filled with newly-created empty BackStep objects which
  // will exist for the life of the stack.  Push does not create a BackStep object
  // but instead overwrites the contents of the existing one.  Thus during MIPS
  // program (simulated) execution, BackStep objects are never created or junked
  // regardless of how many steps are executed.  This will speed things up a bit
  // and make life easier for the garbage collector.

  private class BackstepStack {
    private final int capacity;
    private int size;
    private int top;
    private final BackStep[] stack;

    // Stack is created upon successful assembly or reset.  The one-time overhead of
    // creating all the BackStep objects will not be noticed by the user, and enhances
    // runtime performance by not having to create or recycle them during MIPS
    // program execution.
    private BackstepStack(int capacity) {
      this.capacity = capacity;
      this.size = 0;
      this.top = -1;
      this.stack = new BackStep[capacity];
      for (int i = 0; i < capacity; i++) {
        this.stack[i] = new BackStep();
      }
    }

    private synchronized void clear() {
      size = 0;
      top = -1;
      for (BackStep step : stack) {
        step.ps = null;
        step.branch = null;
        step.restore = null;
        step.frame = 0;
      }
    }

    private synchronized boolean empty() {
      return size == 0;
    }

    private synchronized MemoryWrite previousMemoryWrite() {
      int index = top;
      for (int remaining = size; remaining > 0; remaining--) {
        BackStep step = stack[index];
        if (step.frame != 0) {
          int length = switch (step.action) {
            case MEMORY_RESTORE_RAW_WORD, MEMORY_RESTORE_WORD -> Integer.BYTES;
            case MEMORY_RESTORE_HALF -> Short.BYTES;
            case MEMORY_RESTORE_BYTE -> Byte.BYTES;
            default -> 0;
          };
          if (length > 0) {
            return new MemoryWrite(step.param1, length);
          }
        }
        index = (index + capacity - 1) % capacity;
      }
      return null;
    }

    private synchronized void push(int act, int programCounter, int parm1, int parm2) {
      if (!engaged || (activeFrame != 0 && frameDiscarded)) {
        return;
      }
      if (size == capacity && activeFrame != 0) {
        long oldestFrame = stack[(top - size + 1 + capacity) % capacity].frame;
        if (oldestFrame == activeFrame) {
          // A single instruction exceeds the capacity. Keep no partial undo for that instruction.
          clear();
          frameDiscarded = true;
          return;
        }
        do {
          stack[(top - size + 1 + capacity) % capacity].restore = null;
          size--;
        } while (size > 0
            && oldestFrame != 0
            && stack[(top - size + 1 + capacity) % capacity].frame == oldestFrame);
      }
      if (size == 0) {
        top = 0;
        size++;
      } else if (size < capacity) {
        top = (top + 1) % capacity;
        size++;
      } else { // size == capacity.  The top moves up one, replacing oldest entry (goodbye!)
        top = (top + 1) % capacity;
      }
      // We'll re-use existing objects rather than create/discard each time.
      // Must use assign() method rather than series of assignment statements!
      stack[top].assign(act, programCounter, parm1, parm2);
    }

    private synchronized void push(int act, int programCounter, int parm1) {
      push(act, programCounter, parm1, 0);
    }

    private synchronized void push(int act, int programCounter) {
      push(act, programCounter, 0, 0);
    }

    // NO PROTECTION.  This class is used only within this file so there is no excuse
    // for trying to pop from empty stack.
    private synchronized BackStep pop() {
      BackStep bs;
      bs = stack[top];
      if (size == 1) {
        top = -1;
      } else {
        top = (top + capacity - 1) % capacity;
      }
      size--;
      return bs;
    }

    // NO PROTECTION.  This class is used only within this file so there is no excuse
    // for trying to peek from empty stack.
    private synchronized BackStep peek() {
      return stack[top];
    }
  }
}
