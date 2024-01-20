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

package edu.missouristate.mars.earth.run.debugger

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import edu.missouristate.mars.earth.run.MipsRunConfiguration
import edu.missouristate.mars.earth.run.controllers.MipsSimulatorController
import java.util.logging.Logger

class MipsDebugProcess(
    session: XDebugSession,
    private val env: ExecutionEnvironment
) : XDebugProcess(session) {
    companion object {
        @JvmStatic private val LOG = Logger.getLogger("MIPS")
    }

    var controller: MipsSimulatorController? = null
    private var lineBreakpointHandler = MipsLineBreakpointHandler(this)
    private var state = getRunConfig().getState(env.executor, env) as MipsDebugConsoleState

    override fun sessionInitialized() {
        session.resume()
    }

    fun getRunConfig(): MipsRunConfiguration = env.runProfile as MipsRunConfiguration

    override fun startStepOver(context: XSuspendContext?) {
        controller?.step()
    }

    override fun getBreakpointHandlers(): Array<out XBreakpointHandler<*>> =
        arrayOf(lineBreakpointHandler)

    override fun createConsole(): ExecutionConsole = state.console

    override fun resume(context: XSuspendContext?) {
        LOG.info("MipsDebugProcess.resume()")
        controller?.resume()
    }

    override fun stop() {
        LOG.info("MipsDebugProcess.stop()")
        controller?.stop()
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider =
        MipsDebuggerEditorsProvider()

    fun addBreakpoint(breakpoint: XBreakpoint<*>) {
        LOG.info("MipsDebugProcess.addBreakpoint()")
        controller?.addBreakpoint(breakpoint)
    }

    fun removeBreakpoint(breakpoint: XBreakpoint<*>, temporary: Boolean) {
        LOG.info("MipsDebugProcess.removeBreakpoint()")
        controller?.removeBreakpoint(breakpoint)
    }
}