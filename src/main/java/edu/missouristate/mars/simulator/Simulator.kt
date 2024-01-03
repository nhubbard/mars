/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("DEPRECATION", "KotlinConstantConditions")

package edu.missouristate.mars.simulator

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.SystemIO
import edu.missouristate.mars.venus.actions.RunGoAction
import edu.missouristate.mars.venus.actions.RunStepAction
import edu.missouristate.mars.venus.panes.RunSpeedPanel
import java.util.*
import javax.swing.AbstractAction
import javax.swing.SwingUtilities
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock

/**
 * Simulate the execution of an assembled MIPS program.
 */
class Simulator private constructor() : Observable() {
    private var simulatorThread: SimThread? = null
    private val stopListeners: ArrayList<StopListener> = ArrayList(1)

    companion object {
        @JvmStatic
        private var simulator: Simulator? = null
        @JvmStatic
        private var interactiveGUIUpdater: Runnable? = null

        const val NO_DEVICE = 0

        @Volatile
        @JvmStatic
        var externalInterruptingDevice: Exceptions = Exceptions.fromInt(NO_DEVICE)

        /**
         * Get the instance of the KSimulator object.
         */
        @JvmStatic
        fun getInstance(): Simulator {
            // Do NOT change this to create the Simulator at load time (in the declaration above)!
            // Its constructor looks for the GUI, which at load time is not created yet,
            // and incorrectly leaves interactiveGUIUpdater null!  This causes runtime
            // exceptions while running in timed mode.
            if (simulator == null) simulator = Simulator()
            return simulator!!
        }

        /**
         * Determine whether the next instruction to be executed is in a
         * "delay slot". This means delayed branching is enabled, the branch
         * condition has evaluated true, and the next instruction executed will
         * be the one following the branch. It is said to occupy the "delay slot."
         * Normally programmers put a nop instruction here, but it can be anything.
         *
         * @return true if the next instruction is in delay slot, false otherwise.
         */
        @JvmStatic
        val inDelaySlot: Boolean get() = DelayedBranch.isTriggered
    }

    enum class TerminationReason(val rawValue: Int) {
        UNKNOWN(0),
        BREAKPOINT(1),
        EXCEPTION(2),
        MAX_STEPS(3),
        NORMAL_TERMINATION(4),
        CLIFF_TERMINATION(5),
        PAUSE_OR_STOP(6)
    }

    init {
        if (Globals.gui != null) interactiveGUIUpdater = UpdateGUI()
    }

    /**
     * Simulate execution of given MIPS program.  It must have already been assembled.
     *
     * @param p           The MIPSProgram to be simulated.
     * @param pc          address of first instruction to simulate; this goes into program counter
     * @param maxSteps    maximum number of steps to perform before returning false (0 or fewer means no max)
     * @param breakPoints array of breakpoint program counter values, use null if none
     * @param actor       the GUI component responsible for this call, usually GO or STEP.  null if none.
     * @return true if execution completed, false otherwise
     * @throws ProcessingException Throws exception if run-time exception occurs.
     */
    @Throws(ProcessingException::class)
    fun simulate(p: MIPSProgram, pc: Int, maxSteps: Int, breakPoints: IntArray?, actor: AbstractAction?): Boolean {
        simulatorThread = SimThread(p, pc, maxSteps, breakPoints, actor)
        simulatorThread!!.start()
        // This condition should only be true if run from command-line instead of GUI.
        // If so, stick around until the execution thread is finished.
        if (actor == null) {
            simulatorThread!!.get()
            val pe = simulatorThread!!.pe
            val done = simulatorThread!!.done
            if (done) SystemIO.resetFiles()
            simulatorThread = null
            if (pe != null) throw pe
            return done
        }
        return true
    }

    /**
     * Set the volatile stop boolean variable checked by the execution
     * thread at the end of each MIPS instruction execution.  If variable
     * is found to be true, the execution thread will depart
     * gracefully so the main thread handling the GUI can take over.
     * This is used by both STOP and PAUSE features.
     */
    fun stopExecution(actor: AbstractAction?) {
        simulatorThread?.let {
            it.stopper = actor
            for (l in stopListeners) l.stopped(this)
            simulatorThread = null
        }
    }

    fun addStopListener(l: StopListener) { stopListeners.add(l) }
    fun removeStopListener(l: StopListener) { stopListeners.remove(l) }

    /**
     * The SimThread object will call this method when it enters and returns from
     * its construct() method.  These signal start and stop, respectively, of
     * simulation execution.  The observer can then adjust its own state depending
     * on the execution state.  Note that "stop" and "done" are not the same thing.
     * "stop" just means it is leaving execution state; this could be triggered
     * by Stop button, by Pause button, by Step button, by runtime exception, by
     * instruction count limit, by breakpoint, or by end of simulation (truly done).
     */
    private fun notifyObserversOfExecutionStart(maxSteps: Int, programCounter: Int) {
        setChanged()
        notifyObservers(SimulatorNotice(
            SimulatorNotice.SIMULATOR_START,
            maxSteps,
            RunSpeedPanel.getInstance().runSpeed,
            programCounter
        ))
    }

    private fun notifyObserversOfExecutionStop(maxSteps: Int, programCounter: Int) {
        setChanged()
        notifyObservers(SimulatorNotice(
            SimulatorNotice.SIMULATOR_STOP,
            maxSteps,
            RunSpeedPanel.getInstance().runSpeed,
            programCounter
        ))
    }

    interface StopListener {
        fun stopped(s: Simulator)
    }

    class SimThread(
        private val p: MIPSProgram,
        private val pc: Int,
        private val maxSteps: Int,
        private var breakPoints: IntArray?,
        private val starter: AbstractAction?
    ): SwingWorker(Globals.gui != null) {
        var done: Boolean = false
        var pe: ProcessingException? = null
        @Volatile
        var stop = false
        @Volatile
        var stopper: AbstractAction? = null
        private lateinit var constructReturnReason: TerminationReason

        /**
         * Sets to "true" the volatile boolean variable that is tested after each
         * MIPS instruction is executed. After calling this method, the next test
         * will yield "true" and "construct" will return.
         *
         * @param actor the Swing component responsible for this call.
         */
        private fun setStop(actor: AbstractAction) {
            stop = true
            stopper = actor
        }

        /**
         * This is comparable to the Runnable "run" method (it is called by
         * SwingWorker's "run" method).  It simulates the program
         * execution in the background.
         */
        override fun construct(): Any {
            // The next two statements are necessary for the GUI to be consistently updated
            // before the simulation gets underway. Without them, this happens only intermittently,
            // with the consequence being that some simulations are interruptable using PAUSE/STOP and others
            // are not (because one or the other or both is not yet enabled).
            Thread.currentThread().priority = Thread.NORM_PRIORITY - 1
            // Let the main thread run a bit to finish updating the GUI
            Thread.yield()
            if (breakPoints.isNullOrEmpty()) {
                breakPoints = null
            } else {
                // Must be pre-sorted for binary search
                breakPoints!!.sort()
            }
            getInstance().notifyObserversOfExecutionStart(maxSteps, pc)
            RegisterFile.initializeProgramCounter(pc)
            var statement: ProgramStatement?
            try {
                statement = Globals.memory.getStatement(RegisterFile.programCounter.getValue())
            } catch (e: AddressErrorException) {
                val el = ErrorList()
                el.add(ErrorMessage(
                    null,
                    0,
                    0,
                    "Invalid program counter value: ${Binary.intToHexString(RegisterFile.programCounter.getValue())}"
                ))
                this.pe = ProcessingException(el, e)
                // The next statement is a hack. The previous statement sets EPC register to ProgramCounter-4
                // because it assumes the bad address comes from an operand so the ProgramCounter has already been
                // incremented. In this case, the bad address is the instruction fetch itself so Program Counter has
                // not yet been incremented. We'll set the EPC directly here.
                Coprocessor0.updateRegister(Coprocessor0.EPC, RegisterFile.programCounter.getValue())
                this.constructReturnReason = TerminationReason.EXCEPTION
                this.done = true
                SystemIO.resetFiles()
                getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                return done
            }
            var steps = 0
            // A couple statements below were added for the purpose of assuring that when
            // "back stepping" is enabled, every instruction will have at least one entry
            // on the back-stepping stack. Most instructions will because they write either
            // to a register or memory. But "nop" and branches not taken do not. When the
            // user is stepping backward through the program, the stack is popped, and if
            // an instruction has no entry, it will be skipped over in the process. This has
            // no effect on the correctness of the mechanism, but the visual jerkiness when
            // instruction highlighting skips such instructions is disruptive. The current solution
            // is to add a "do nothing" stack entry for instructions that do not write anything.
            // To keep this invisible to the "simulate()" method writer, we
            // will push such an entry onto the stack here if there is not an entry for this
            // instruction by the time it has completed simulating. This is done by the IF statement
            // just after the call to the simulate method itself. The BackStepper method does
            // the aforementioned check and decides whether to push or not. The result
            // is a smoother interaction experience. But it comes at the cost of slowing
            // simulation speed for flat-out runs, for every MIPS instruction executed even
            // though very few will require the "do nothing" stack entry. For stepped or
            // timed execution, the slower execution speed is not noticeable.
            //
            // To avoid this cost, I tried a different technique: back-fill with "do nothing" entries
            // during the backstepping itself when this situation is recognized. The problem
            // was in recognizing all possible situations in which the stack contained such
            // a "gap". It became a morass of special cases, and it seemed every unique test
            // case revealed another one. In addition, when a program
            // begins with one or more such instructions ("nop" and branches not taken),
            // the backstep button is not enabled until a "real" instruction is executed.
            // This is noticeable in stepped mode.
            var pc = 0
            while (statement != null) {
                pc = RegisterFile.programCounter.getValue()
                RegisterFile.incrementPC()
                // Perform the MIPS instruction in synchronized block. If external threads agree
                // to access MIPS memory and registers only through synchronized blocks on the same
                // lock variable, then full (albeit heavy-handed) protection of MIPS memory and
                // registers is assured. Not as critical for reading from those resources.
                Globals.memoryAndRegistersLock.withLock {
                    try {
                        if (externalInterruptingDevice != Exceptions.fromInt(NO_DEVICE)) {
                            val deviceInterruptCode = externalInterruptingDevice
                            externalInterruptingDevice = Exceptions.fromInt(NO_DEVICE)
                            throw ProcessingException(statement, "External interrupt!", deviceInterruptCode)
                        }
                        val instruction = statement!!.getInstruction() as? BasicInstruction
                            ?: throw ProcessingException(
                                statement,
                                "Undefined instruction: ${Binary.intToHexString(statement!!.getBinaryStatement())}",
                                Exceptions.RESERVED_INSTRUCTION_EXCEPTION
                            )
                        // Simulate the actual instruction
                        instruction.simulationCode.simulate(statement!!)
                        if (Globals.settings.getBackSteppingEnabled())
                            Globals.program.getBackStepper()!!.addDoNothing(pc)
                    } catch (e: ProcessingException) {
                        if (pe?.errors() == null) {
                            constructReturnReason = TerminationReason.NORMAL_TERMINATION
                            done = true
                            SystemIO.resetFiles()
                            getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                            return done
                        } else {
                            // See if an exception handler is present. Assume this is the case
                            // if and only if memory location Memory.exceptionHandlerAddress
                            // (e.g., 0x80000180) contains an instruction. If so, then set the
                            // program counter there and continue. Otherwise, terminate the
                            // MIPS program with the appropriate error message.
                            val exceptionHandler = try {
                                Globals.memory.getStatement(Memory.exceptionHandlerAddress)
                            } catch (ignored: AddressErrorException) { null }
                            if (exceptionHandler != null) {
                                RegisterFile.setProgramCounter(Memory.exceptionHandlerAddress)
                            } else {
                                constructReturnReason = TerminationReason.EXCEPTION
                                pe = e
                                done = true
                                SystemIO.resetFiles()
                                getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                                return@construct done
                            }
                        }
                    }
                    // For some reason, Kotlin insists I have this here, even though the "withLock" block doesn't
                    // have an assigment statement attached to it.
                    // Weird.
                    Unit
                }

                // Handle delayed branching if it occurs.
                if (DelayedBranch.isTriggered) {
                    RegisterFile.setProgramCounter(DelayedBranch.branchTargetAddress)
                    DelayedBranch.clear()
                } else if (DelayedBranch.isRegistered) {
                    DelayedBranch.trigger()
                }

                // Volatile variable initialized false but can be set true by the main thread.
                // Used to stop or pause a running MIPS program.  See stopSimulation() above.
                if (stop) {
                    constructReturnReason = TerminationReason.PAUSE_OR_STOP
                    done = false
                    getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                    return done
                }

                // Return if we've reached a breakpoint.
                if (breakPoints != null &&
                    Arrays.binarySearch(breakPoints!!, RegisterFile.programCounter.getValue()) >= 0) {
                    constructReturnReason = TerminationReason.BREAKPOINT
                    done = false
                    getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                    return done
                }

                // Check the number of MIPS instructions executed. Return if the limit is reached (-1 is no limit).
                if (maxSteps > 0) {
                    steps++
                    if (steps >= maxSteps) {
                        constructReturnReason = TerminationReason.MAX_STEPS
                        done = false
                        getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                        return done
                    }
                }

                // Schedule GUI update only if (a) there is a GUI, (b) the user chose to Run, not Step, and (c) the
                // simulation is running slowly enough for the GUI to keep up.
                if (interactiveGUIUpdater != null && maxSteps != 1 &&
                    RunSpeedPanel.getInstance().runSpeed < RunSpeedPanel.UNLIMITED_SPEED)
                    SwingUtilities.invokeLater(interactiveGUIUpdater)

                if (Globals.gui != null || Globals.runSpeedPanelExists) {
                    if (maxSteps != 1 && RunSpeedPanel.getInstance().runSpeed < RunSpeedPanel.UNLIMITED_SPEED) {
                        try {
                            Thread.sleep((1000 / RunSpeedPanel.getInstance().runSpeed).toLong())
                        } catch (ignored: InterruptedException) {}
                    }
                }

                // Get next instruction in preparation for the next iteration.
                try {
                    statement = Globals.memory.getStatement(RegisterFile.programCounter.getValue())
                } catch (e: AddressErrorException) {
                    val el = ErrorList()
                    el.add(ErrorMessage(
                        null,
                        0,
                        0,
                        "Invalid program counter value: ${Binary.intToHexString(RegisterFile.programCounter.getValue())}"
                    ))
                    pe = ProcessingException(el, e)
                    Coprocessor0.updateRegister(Coprocessor0.EPC, RegisterFile.programCounter.getValue())
                    constructReturnReason = TerminationReason.EXCEPTION
                    done = true
                    SystemIO.resetFiles()
                    getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
                    return done
                }
            }
            // This "if" statement is needed for correct program termination if delayed branching is on, and the last
            // statement in the program is a branch/jump. The program will terminate rather than branch, because that's
            // what MARS does when execution drops out of the bottom of the program.
            if (DelayedBranch.isTriggered || DelayedBranch.isRegistered) DelayedBranch.clear()

            // If we got here, it was due to a null statement, and the program counter "fell off the end" of the
            // program. This assumes the "while" loop contains no "break" statements.
            constructReturnReason = TerminationReason.CLIFF_TERMINATION
            done = true
            SystemIO.resetFiles()
            getInstance().notifyObserversOfExecutionStop(maxSteps, pc)
            return done
        }

        /**
         * This method is invoked by the SwingWorker when the "construct" method returns.
         * It will update the GUI appropriately. According to Sun's documentation, it
         * is run in the main thread, so it should work OK with Swing components (which are
         * not thread-safe).
         *
         * Its action depends on what caused the return from construct() and what
         * action led to the call of construct() in the first place.
         */
        override fun finished() {
            // If running from the command-line, there is no GUI to update.
            if (Globals.gui == null) return
            requireNotNull(starter) { "Param `starter` cannot be null if the GUI is not null!" }
            val starterName = starter.getValue(AbstractAction.NAME) as String
            if (starterName == "Step") {
                (starter as RunStepAction).stepped(done, constructReturnReason, pe)
            } else if (starterName == "Go") {
                starter as RunGoAction
                if (done) {
                    starter.stopped(pe, constructReturnReason)
                } else if (constructReturnReason == TerminationReason.BREAKPOINT) {
                    starter.paused(done, constructReturnReason, pe)
                } else {
                    val stopperName = stopper?.getValue(AbstractAction.NAME)
                    if (stopperName == "Pause") {
                        starter.paused(done, constructReturnReason, pe)
                    } else if (stopperName == "Stop") {
                        starter.stopped(pe, constructReturnReason)
                    }
                }
            }
        }
    }

    private class UpdateGUI : Runnable {
        override fun run() {
            val g = Globals.gui
            requireNotNull(g) { "GUI cannot be null for this action!" }
            g.mainPane.executePane.let {
                if (g.registersPane.selectedComponent == it.registersWindow) {
                    it.registersWindow.updateRegisters()
                } else {
                    it.coprocessor1Window.updateRegisters()
                }
                it.dataSegmentWindow.updateValues()
                it.textSegmentWindow.codeHighlighting = true
                it.textSegmentWindow.highlightStepAtPC()
            }
        }
    }
}