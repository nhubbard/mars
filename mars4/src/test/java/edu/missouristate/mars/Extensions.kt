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

package edu.missouristate.mars

import edu.missouristate.mars.util.TapStream
import org.junit.jupiter.params.provider.Arguments
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.stream.Stream

// One argument
fun <A> oneArgumentOf(vararg args: A): Stream<Arguments> =
    args.map { Arguments.of(it) }.stream()

// Two arguments
fun <A, B> twoArgumentsOf(vararg args: Pair<A, B>): Stream<Arguments> =
    args.map { Arguments.of(it.first, it.second) }.stream()

// Three arguments
infix fun <A, B, C> A.tri(other: Pair<B, C>): Triple<A, B, C> =
    Triple(this, other.first, other.second)

infix fun <A, B, C> Pair<A, B>.tri(third: C): Triple<A, B, C> =
    Triple(first, second, third)

fun <A, B, C> threeArgumentsOf(vararg args: Triple<A, B, C>): Stream<Arguments> =
    args.map { Arguments.of(it.first, it.second, it.third) }.stream()

// Helper function to create programs from an assembly file
fun createProgram(vararg paths: String, ignoreErrors: Boolean = false): Pair<MIPSProgram, ErrorList> {
    val inputFile = Paths.get(paths.first()).toFile().absolutePath
    val otherFiles = paths.drop(1).map { Paths.get(it).toFile().absolutePath }.toTypedArray()
    Globals.initialize(false)
    val program = MIPSProgram()
    program.prepareFilesForAssembly(arrayListOf(inputFile, *otherFiles), inputFile, "", ignoreErrors)
    program.tokenize(ignoreErrors)
    val errors = program.assemble(arrayListOf(program), true, false, ignoreErrors)
    program.simulate(-1)
    return program to errors
}

private fun wrap(outputStream: OutputStream): PrintStream =
    PrintStream(outputStream, true, Charset.defaultCharset().name())

private fun executeWithSystemOutReplacement(replacementForOut: OutputStream, statement: () -> Unit) {
    val originalStream = System.out
    try {
        System.setOut(wrap(replacementForOut))
        statement.invoke()
    } finally {
        System.setOut(originalStream)
    }
}

// Tap system out (stolen from stefanbirkner/systemlambda on GitHub, thanks!)
fun tapSystemOut(block: () -> Unit): String {
    val tapStream = TapStream()
    executeWithSystemOutReplacement(tapStream, block)
    return tapStream.getTextThatWasWritten()
}

// Mutate settings temporarily
fun Settings.withMutatedBoolean(setting: Int, newValue: Boolean, block: () -> Unit) {
    val oldValue = getBooleanSetting(setting)
    setBooleanSettingNonPersistent(setting, newValue)
    block()
    setBooleanSettingNonPersistent(setting, oldValue)
}