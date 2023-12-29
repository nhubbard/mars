/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
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

fun Boolean.toInt(): Int = if (this) 1 else 0

fun Int.signExtend(i: Int = 16): Int = this shl i shr i
fun Int.bitsToFloat(): Float = java.lang.Float.intBitsToFloat(this)

fun Long.bitsToDouble(): Double = java.lang.Double.longBitsToDouble(this)

fun Float.toIntBits(): Int = java.lang.Float.floatToIntBits(this)
fun Float.toRawIntBits(): Int = java.lang.Float.floatToRawIntBits(this)

fun Float.inIntRange(): Boolean = this in Int.MIN_VALUE.toFloat()..Int.MAX_VALUE.toFloat()

fun Double.toLongBits(): Long = java.lang.Double.doubleToLongBits(this)
fun Double.toRawLongBits(): Long = java.lang.Double.doubleToRawLongBits(this)

fun String.decodeToLong(): Long = java.lang.Long.decode(this)