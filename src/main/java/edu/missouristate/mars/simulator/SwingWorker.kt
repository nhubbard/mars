package edu.missouristate.mars.simulator

import javax.swing.SwingUtilities

/**
 * This is the 3rd version of SwingWorker (also known as
 * SwingWorker 3), an abstract class that you subclass to
 * perform GUI-related work in a dedicated thread.  For
 * instructions on and examples of using this class, see:
 *
 * [...](http://java.sun.com/docs/books/tutorial/uiswing/misc/threads.html)
 *
 * Note that the API changed slightly in the 3rd version:
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
     * Class to maintain reference to current worker thread
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