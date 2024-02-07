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

package edu.missouristate.mars.simulator

import javax.swing.SwingUtilities

/**
 * This is the third version of SwingWorker (also known as
 * SwingWorker 3), an abstract class that you subclass to
 * perform GUI-related work in a dedicated thread.  For
 * instructions on and examples of using this class, see:
 *
 * [...](http://java.sun.com/docs/books/tutorial/uiswing/misc/threads.html)
 *
 * Note that the API changed slightly in the third version:
 * You must now invoke start() on the SwingWorker after
 * creating it.
 *
 * I'm fine with using the automatically converted version
 *
 * ------------------------------------------------------------
 *
 * Start a thread that will call the `construct` method and then exit.
 *
 * @param useSwing Set true if MARS is running from GUI, false otherwise.
 */
abstract class SwingWorker(useSwing: Boolean) {
    /**
     * Get the value produced by the worker thread, or null if it
     * hasn't been constructed yet.
     */
    /**
     * Set the value produced by worker thread
     */
    @get:Synchronized
    @set:Synchronized
    protected var value: Any? = null // see getValue(), setValue()
        /**
         * Set the value produced by worker thread
         */
        private set

    /**
     * Class to maintain reference to the current worker thread
     * under separate synchronization control.
     */
    private class ThreadVar(private var thread: Thread?) {
        @Synchronized
        fun get(): Thread? {
            return thread
        }

        @Synchronized
        fun clear() {
            thread = null
        }
    }

    private lateinit var threadVar: ThreadVar

    /**
     * Compute the value to be returned by the `get` method.
     */
    abstract fun construct(): Any

    /**
     * Called on the event dispatching thread (not on the worker thread)
     * after the `construct` method has returned.
     */
    open fun finished() {}

    /**
     * A new method that interrupts the worker thread.  Call this method
     * to force the worker to stop what it's doing.
     */
    fun interrupt() {
        val t = threadVar.get()
        t?.interrupt()
        threadVar.clear()
    }

    /**
     * Return the value created by the `construct` method.
     * Returns null if either the constructing thread or the current
     * thread was interrupted before a value was produced.
     *
     * @return the value created by the `construct` method
     */
    fun get(): Any? {
        while (true) {
            val t = threadVar.get() ?: return value
            try {
                t.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // propagate
                return null
            }
        }
    }


    init {
        val doFinished = Runnable { this.finished() }

        val doConstruct = Runnable {
            try {
                value = construct()
            } finally {
                threadVar.clear()
            }
            if (useSwing) SwingUtilities.invokeLater(doFinished)
            else doFinished.run()
        }

        // Thread that represents executing MIPS program...
        val t = Thread(doConstruct, "MIPS")

        threadVar = ThreadVar(t)
    }

    /**
     * Start the worker thread.
     */
    fun start() {
        val t = threadVar.get()
        t?.start()
    }
}