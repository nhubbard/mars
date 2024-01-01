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

@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package edu.missouristate.mars

import kotlin.contracts.contract

private typealias JFloat = java.lang.Float
private typealias JDouble = java.lang.Double
private typealias JLong = java.lang.Long

fun Boolean.toInt(): Int = if (this) 1 else 0

fun Int.signExtend(i: Int = 16): Int = this shl i shr i
fun Int.bitsToFloat(): Float = JFloat.intBitsToFloat(this)

fun Long.bitsToDouble(): Double = JDouble.longBitsToDouble(this)

fun Float.toIntBits(): Int = JFloat.floatToIntBits(this)
fun Float.toRawIntBits(): Int = JFloat.floatToRawIntBits(this)

fun Float.inIntRange(): Boolean = this in Int.MIN_VALUE.toFloat()..Int.MAX_VALUE.toFloat()

fun Double.toLongBits(): Long = JDouble.doubleToLongBits(this)
fun Double.toRawLongBits(): Long = JDouble.doubleToRawLongBits(this)

fun Double.inIntRange(): Boolean = this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()

fun String.decodeToLong(): Long = JLong.decode(this)

/**
 * Extension to use the Array<T>.isNullOrEmpty function with a primitive IntArray.
 */
fun IntArray?.isNullOrEmpty(): Boolean {
    contract {
        returns(false) implies (this@isNullOrEmpty != null)
    }
    return this == null || this.isEmpty()
}