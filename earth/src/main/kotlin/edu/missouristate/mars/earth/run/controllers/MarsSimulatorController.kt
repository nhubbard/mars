/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package edu.missouristate.mars.earth.run.controllers

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import edu.missouristate.mars.earth.run.MipsRunConfiguration
import edu.missouristate.mars.earth.run.debugger.MipsSuspendContext
import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.simulator.Simulator
import edu.missouristate.mars.simulator.SimulatorNotice
import org.apache.commons.lang.NotImplementedException
import java.util.*
import java.util.logging.Logger

class MarsSimulatorController : MipsSimulatorController, Observer {
    companion object {
        @JvmStatic private val LOG = Logger.getLogger("MIPS")
    }

    private var paused: Boolean = false
    private var finished: Boolean = false

    private lateinit var lineToPC: HashMap<Int, Int>
    private lateinit var pcToLine: HashMap<Int, Int>
    private lateinit var lineToBreakpoint: HashMap<Int, XBreakpoint<*>>

    constructor(
        cfg: MipsRunConfiguration,
        console: ConsoleView,
        processHandler: ProcessHandler
    ) : super(cfg, console, processHandler) {
        initialize()
    }

    constructor(
        cfg: MipsRunConfiguration,
        console: ConsoleView,
        process: XDebugProcess,
        session: XDebugSession
    ) : super(cfg, console, process, session) {
        initialize()
    }

    private fun initialize() {
        Simulator.getInstance().addObserver(this)

        lineToPC = hashMapOf()
        pcToLine = hashMapOf()
        lineToBreakpoint = hashMapOf()

        paused = false
        finished = false

        try {
            assemble()
            if (isDebugger) updateLineToPC()
        } catch (e: ProcessingException) {
            printlnError("Failed to assemble file: ${cfg.mainFile}")
            e.printStackTrace()
        }
    }

    override val isPaused: Boolean = paused
    override val isFinished: Boolean = finished

    override fun resume() {
        LOG.info("controller.resume(debug=$isDebugger)")
        paused = false
        try {
            finished = Globals.program.simulateFromPC(getMarsBreakpoints(), cfg.maxSteps, null)
        } catch (e: ProcessingException) {
            printlnError("Failed to simulate file: ${cfg.mainFile}")
            e.printStackTrace()
        } finally {
            if (finished) stop()
            else breakpointReached()
        }
    }

    private fun breakpointReached() {
        LOG.info("controller.breakpointReached()")
        val statements = Globals.program.parsedList as? ArrayList<ProgramStatement> ?: return
        // PC is after the last instruction, stop execution
        if (RegisterFile.getProgramCounter() > statements.last().address)
            stop()
        findBreakpointByPC(RegisterFile.getProgramCounter())?.let {
            val suspendContext = MipsSuspendContext(debugSession!!.project, this, it, RegisterFile.getProgramCounter())
            debugSession.breakpointReached(it, "Breakpoint PC=${RegisterFile.getProgramCounter()}", suspendContext)
        }
    }

    private fun updateLineToPC() {
        val statements = Globals.program.parsedList
        for (statement in statements) {
            val s = statement as ProgramStatement
            lineToPC[s.sourceLine - 1] = s.address
            pcToLine[s.address] = s.sourceLine - 1
        }
    }

    private fun findBreakpointByPC(programCounter: Int): XBreakpoint<*>? =
        lineToBreakpoint[pcToLine[programCounter]]

    override fun stop() {
        super.stop()
        LOG.info("controller.stop()")
    }

    override fun pause() {
        LOG.info("controller.pause()")
        paused = true
    }

    override fun step() {
        LOG.info("controller.step()")
        stop()
        throw NotImplementedException("Stepping is not implemented yet!")
    }

    override fun addBreakpoint(breakpoint: XBreakpoint<*>) {
        super.addBreakpoint(breakpoint)
        lineToBreakpoint[breakpoint.sourcePosition!!.line] = breakpoint
    }

    private fun getMarsBreakpoints(): IntArray {
        val statements = Globals.program.parsedList
        val breaks = IntArray(breakpoints.size)
        var breakIndex = 0
        for (b in breakpoints) {
            val line = b.sourcePosition!!.line
            for (statement in statements) {
                val s = statement as ProgramStatement
                if (line == s.sourceLine - 1)
                    breaks[breakIndex++] = s.address
            }
        }
        return breaks
    }

    private fun assemble() {
        RegisterFile.resetRegisters()
        RegisterFile.initializeProgramCounter(cfg.isStartMain)
        val programs = arrayListOf<MIPSProgram>()
        Globals.program.readSource(cfg.mainFile)
        Globals.program.tokenize()
        val warnings = Globals.program.assemble(programs, cfg.isAllowExtendedInstructions)
        // if (warnings.warningsOccurred()) printMarsErrorList(warnings)
    }

    private fun printMarsErrorList(errors: ErrorList) {
        for (m in errors.errorMessages) {
            val msg = m as ErrorMessage
            printMarsErrorMessage(msg)
        }
    }

    private fun printMarsErrorMessage(error: ErrorMessage?) {
        if (error == null) return
        val project = cfg.project
        val file = LocalFileSystem.getInstance().findFileByPath(error.filename)
        val line = error.line - 1
        val column = error.position - 1

        if (file == null) return
        printError("Error: ")
        printError("(")
        console.printHyperlink(
            "${file.presentableName}: ${error.line}",
            OpenFileHyperlinkInfo(project, file, line, column)
        )
        printlnError("): ${error.message}")
    }

    override fun update(observable: Observable, o: Any?) {
        LOG.info("obs=$observable, o=$o")
        if (o !is SimulatorNotice) return
        if (o.action == SimulatorNotice.SIMULATOR_STOP) stop()
        else if (o.action == SimulatorNotice.SIMULATOR_START) resume()
    }
}