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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.earth.run.controllers

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import edu.missouristate.mars.earth.run.MipsRunConfiguration

abstract class MipsSimulatorController(
    val isDebugger: Boolean,
    protected val cfg: MipsRunConfiguration,
    protected val console: ConsoleView,
    private val processHandler: ProcessHandler,
    protected val debugProcess: XDebugProcess?,
    protected val debugSession: XDebugSession?
) {
    protected var inputStream = MipsConsoleInputStream(cfg.project)
    protected val breakpoints = arrayListOf<XBreakpoint<*>>()

    constructor(cfg: MipsRunConfiguration, console: ConsoleView, processHandler: ProcessHandler) :
        this(false, cfg, console, processHandler, null, null)

    constructor(cfg: MipsRunConfiguration, console: ConsoleView, process: XDebugProcess, session: XDebugSession) :
        this(true, cfg, console, process.processHandler, process, session)

    abstract val isPaused: Boolean
    abstract val isFinished: Boolean

    abstract fun resume()
    abstract fun pause()

    open fun stop() {
        processHandler.destroyProcess()
    }

    abstract fun step()

    open fun addBreakpoint(breakpoint: XBreakpoint<*>) {
        breakpoints.add(breakpoint)
    }

    fun removeBreakpoint(breakpoint: XBreakpoint<*>) {
        breakpoints.remove(breakpoint)
    }

    fun println(message: String) {
        print("$message\n")
    }

    fun print(message: String) {
        print(message, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun printlnSystem(message: String) {
        printSystem("$message\n")
    }

    fun printSystem(message: String) {
        print(message, ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun printlnError(message: String) {
        printError("$message\n")
    }

    fun printError(message: String) {
        print(message, ConsoleViewContentType.ERROR_OUTPUT)
    }

    fun print(message: String, type: ConsoleViewContentType) {
        console.print(message, type)
    }
}