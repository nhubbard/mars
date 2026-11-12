package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Modified 2009-10-19 by Max Hailperin <max@gustavus.edu>
to use a specific method for synchronously generating
a tone, rather than using an asyncronous method followed
by a Thread.sleep, because sleeping isn't a reliable
synchronization method -- depending on thread scheduling,
the actual tone generation could have been delayed, in
which case the tone might still be playing when the
sleep ended.

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

open class SyscallMidiOutSync : AbstractSyscall(33, "MidiOutSync") {
    override fun simulate(statement: ProgramStatement?, context: InstructionContext) {
        context.playMidiSyscall(synchronous = true)
    }
}
