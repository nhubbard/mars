package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.api.EventEmitter
import java.util.function.Consumer

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

/** A machine register whose reads and writes can be observed. */
open class Register(open val name: String, open val number: Int, initial: Int) {
    private var initialValue = initial
    @Volatile private var currentValue = initial
    private val events = EventEmitter<RegisterAccessNotice>()

    open fun addObserver(listener: Consumer<RegisterAccessNotice>) {
        events.subscribe(listener)
    }

    open fun deleteObserver(listener: Consumer<RegisterAccessNotice>) {
        events.unsubscribe(listener)
    }

    @get:Synchronized
    open val value: Int
        get() {
            notifyListeners(AccessNotice.READ)
            return currentValue
        }

    @get:Synchronized
    open val valueNoNotify: Int get() = currentValue

    open val resetValue: Int get() = initialValue

    @Synchronized
    open fun setValue(value: Int): Int {
        val previous = currentValue
        currentValue = value
        notifyListeners(AccessNotice.WRITE)
        return previous
    }

    @Synchronized
    open fun resetValue() {
        currentValue = initialValue
    }

    @Synchronized
    open fun changeResetValue(value: Int) {
        initialValue = value
    }

    private fun notifyListeners(type: Int) {
        events.emit(RegisterAccessNotice(type, name))
    }
}
