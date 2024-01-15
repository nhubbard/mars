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

import java.awt.Graphics
import java.awt.Polygon
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.*
import kotlin.contracts.contract

// Some simple aliases so I don't have to type `java.lang.` every time.
private typealias JFloat = java.lang.Float
private typealias JDouble = java.lang.Double
private typealias JLong = java.lang.Long

/** Convert a Boolean to an Int value. */
fun Boolean.toInt(): Int = if (this) 1 else 0

/** Sign-extend an integer value by [i] bits. */
fun Int.signExtend(i: Int = 16): Int = this shl i shr i

/**
 * Returns the float value corresponding to a given bit representation. The argument is considered to be a
 * representation of a floating-point value according to the IEEE 754 floating-point "single format" bit layout.
 *
 * If the argument is `0x7f800000`, the result is positive infinity.
 *
 * If the argument is `0xff800000`, the result is negative infinity.
 *
 * If the argument is any value in the range `0x7f800001` through `0x7fffffff` or in the range `0xff800001` through
 * `0xffffffff`, the result is a NaN. No IEEE 754 floating-point operation provided by Java can distinguish between two
 * NaN values of the same type with different bit patterns. Distinct values of NaN are only distinguishable by use of
 * the [Float.toRawIntBits] method.
 *
 * In all other cases, let `s`, `e`, and `m` be three values that can be computed from the argument:
 *
 * ```java
 * int s = ((bits >> 31) == 0) ? 1 : -1;
 * int e = ((bits >> 23) & 0xff);
 * int m = (e == 0) ?
 * (bits & 0x7fffff) << 1 :
 * (bits & 0x7fffff) | 0x800000;
 * ```
 *
 * Then the floating-point result equals the value of the mathematical expression $s \times m \times 2^{e-150}$.
 * Note that this method may not be able to return a float NaN with the exact same bit pattern as the int argument.
 * IEEE 754 distinguishes between two kinds of NaNs, quiet NaNs and signaling NaNs. The differences between the two
 * kinds of NaN are generally not visible in Java. Arithmetic operations on signaling NaNs turn them into quiet NaNs
 * with a different, but often similar, bit pattern. However, on some processors merely copying a signaling NaN also
 * performs that conversion. In particular, copying a signaling NaN to return it to the calling method may perform this
 * conversion. So [Int.bitsToFloat] may not be able to return a float with a signaling NaN bit pattern.
 * Consequently, for some int values, `start.bitsToFloat().toRawIntBits()` may not equal `start`. Moreover, which
 * particular bit patterns represent signaling NaNs is platform dependent; although all NaN bit patterns, whether quiet
 * or signaling, must be in the NaN range identified above.
 *
 * @return The float floating-point value with the same bit pattern.
 * @usesMathJax
 */
fun Int.bitsToFloat(): Float = JFloat.intBitsToFloat(this)

/**
 * Returns the double value corresponding to a given bit representation. The argument is considered to be a
 * representation of a floating-point value according to the IEEE 754 floating-point "double format" bit layout.
 *
 * If the argument is `0x7ff0000000000000L`, the result is positive infinity.
 *
 * If the argument is `0xfff0000000000000L`, the result is negative infinity.
 *
 * If the argument is any value in the range `0x7ff0000000000001L` through `0x7fffffffffffffffL` or in the range
 * `0xfff0000000000001L` through `0xffffffffffffffffL`, the result is a NaN. No IEEE 754 floating-point operation
 * provided by Java can distinguish between two NaN values of the same type with different bit patterns. Distinct values
 * of NaN are only distinguishable by use of the [Double.toRawLongBits] method.
 *
 * In all other cases, let `s`, `e`, and `m` be three values that can be computed from the argument:
 *
 * ```java
 * int s = ((bits >> 63) == 0) ? 1 : -1;
 * int e = (int)((bits >> 52) & 0x7ffL);
 * long m = (e == 0) ?
 *                 (bits & 0xfffffffffffffL) << 1 :
 *                 (bits & 0xfffffffffffffL) | 0x10000000000000L;
 * ```
 *
 * Then the floating-point result equals the value of the mathematical expression $s \times m \times 2^{e-1075}$.
 * Note that this method may not be able to return a double NaN with the exact same bit pattern as the long argument.
 * IEEE 754 distinguishes between two kinds of NaNs, quiet NaNs and signaling NaNs. The differences between the two
 * kinds of NaN are generally not visible in Java. Arithmetic operations on signaling NaNs turn them into quiet NaNs
 * with a different, but often similar, bit pattern. However, on some processors merely copying a signaling NaN also
 * performs that conversion. In particular, copying a signaling NaN to return it to the calling method may perform this
 * conversion. So longBitsToDouble may not be able to return a double with a signaling NaN bit pattern. Consequently,
 * for some long values, `start.bitsToDouble().toRawLongBits()` may not equal `start`. Moreover, which particular bit
 * patterns represent signaling NaNs is platform dependent; although all NaN bit patterns, whether quiet or signaling,
 * must be in the NaN range identified above.
 *
 * @return The double floating-point value with the same bit pattern.
 * @usesMathJax
 */
fun Long.bitsToDouble(): Double = JDouble.longBitsToDouble(this)

/**
 * Returns a representation of the specified floating-point value according to the IEEE 754 floating-point
 * "single format" bit layout.
 *
 * Bit 31 (the bit that is selected by the mask `0x80000000`) represents the sign of the floating-point number.
 * Bits 30-23 (the bits that are selected by the mask `0x7f800000`) represent the exponent.
 * Bits 22-0 (the bits that are selected by the mask `0x007fffff`) represent the significand (sometimes called the
 * mantissa) of the floating-point number.
 *
 * If the argument is positive infinity, the result is `0x7f800000`.
 * If the argument is negative infinity, the result is `0xff800000`.
 * If the argument is NaN, the result is `0x7fc00000`.
 *
 * In all cases, the result is an integer that, when given to the [Int.bitsToFloat] method, will produce a
 * floating-point value the same as the argument to [Float.toIntBits] (except all NaN values are collapsed to a single
 * "canonical" NaN value).
 *
 * @return The bits that represent the floating-point number.
 */
fun Float.toIntBits(): Int = JFloat.floatToIntBits(this)

/**
 * Returns a representation of the specified floating-point value according to the IEEE 754 floating-point
 * "single format" bit layout, preserving Not-a-Number (NaN) values.
 *
 * Bit 31 (the bit that is selected by the mask `0x80000000`) represents the sign of the floating-point number.
 * Bits 30-23 (the bits that are selected by the mask `0x7f800000`) represent the exponent.
 * Bits 22-0 (the bits that are selected by the mask `0x007fffff`) represent the significand (sometimes called the
 * mantissa) of the floating-point number.
 *
 * If the argument is positive infinity, the result is `0x7f800000`.
 * If the argument is negative infinity, the result is `0xff800000`.
 * If the argument is NaN, the result is the integer representing the actual NaN value.
 *
 * Unlike the [Float.toIntBits] method, [Float.toRawIntBits] does not collapse all the bit patterns encoding a NaN to a
 * single "canonical" NaN value.
 *
 * In all cases, the result is an integer that, when given to the [Int.bitsToFloat] method,
 * will produce a floating-point value the same as the argument to [Float.toRawIntBits].
 *
 * @return The bits that represent the floating-point number.
 */
fun Float.toRawIntBits(): Int = JFloat.floatToRawIntBits(this)

/** @return `true` if the [Float] value is representable by an [Int]. */
fun Float.inIntRange(): Boolean = this in Int.MIN_VALUE.toFloat()..Int.MAX_VALUE.toFloat()

/**
 * Returns a representation of the specified floating-point value according to the IEEE 754 floating-point
 * "double format" bit layout.
 *
 * Bit 63 (the bit that is selected by the mask `0x8000000000000000L`) represents the sign of the floating-point number.
 * Bits 62-52 (the bits that are selected by the mask `0x7ff0000000000000L`) represent the exponent.
 * Bits 51-0 (the bits that are selected by the mask `0x000fffffffffffffL`) represent the significand (sometimes called
 * the mantissa) of the floating-point number.
 *
 * If the argument is positive infinity, the result is `0x7ff0000000000000L`.
 * If the argument is negative infinity, the result is `0xfff0000000000000L`.
 * If the argument is NaN, the result is `0x7ff8000000000000L`.
 *
 * In all cases, the result is a long integer that, when given to the [Long.bitsToDouble] method, will produce a
 * floating-point value the same as the argument to [Double.toLongBits] (except all NaN values are collapsed to a single
 * "canonical" NaN value).
 *
 * @return The bits that represent the floating-point number.
 */
fun Double.toLongBits(): Long = JDouble.doubleToLongBits(this)

/**
 * Returns a representation of the specified floating-point value according to the IEEE 754 floating-point
 * "double format" bit layout, preserving Not-a-Number (NaN) values.
 *
 * Bit 63 (the bit that is selected by the mask `0x8000000000000000L`) represents the sign of the floating-point number.
 * Bits 62-52 (the bits that are selected by the mask `0x7ff0000000000000L`) represent the exponent.
 * Bits 51-0 (the bits that are selected by the mask `0x000fffffffffffffL`) represent the significand (sometimes called
 * the mantissa) of the floating-point number.
 *
 * If the argument is positive infinity, the result is `0x7ff0000000000000L`.
 * If the argument is negative infinity, the result is `0xfff0000000000000L`.
 * If the argument is NaN, the result is the long integer representing the actual NaN value.
 *
 * Unlike the [Double.toLongBits] method, [Double.toRawLongBits] does not collapse all the bit patterns encoding a NaN
 * to a single "canonical" NaN value.
 *
 * In all cases, the result is a long integer that, when given to the [Long.bitsToDouble] method, will produce a
 * floating-point value the same as the argument to [Double.toRawLongBits].
 *
 * @return The bits that represent the floating-point number.
 */
fun Double.toRawLongBits(): Long = JDouble.doubleToRawLongBits(this)

/** @return `true` if the [Double] value is representable by an [Int]. */
fun Double.inIntRange(): Boolean = this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()

/**
 * Decodes a [String] into a [Long]. Accepts decimal, hexadecimal, and octal numbers given by the following grammar:
 *
 * *DecodableString:*
 *
 *  *Sign_opt DecimalNumeral*
 *
 *  *Sign_opt 0x HexDigits*
 *
 *  *Sign_opt 0X HexDigits*
 *
 *  *Sign_opt # HexDigits*
 *
 *  *Sign_opt 0 OctalDigits*
 *
 * *Sign: -, +*
 *
 * *DecimalNumeral*, *HexDigits*, and *OctalDigits* are as defined in
 * [Section 3.10.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.10.1) of
 * *The Java Language Specification*, except that underscores are not accepted between digits.
 *
 * The sequence of characters following an optional sign and/or radix specifier ("`0x`", "`0X`", "`#`", or leading zero)
 * is parsed as by the [String.toLong] method with the indicated radix (10, 16, or 8). This sequence of characters must
 * represent a positive value or a [NumberFormatException] will be thrown. The result is negated if the first character
 * of the specified [String] is the minus sign. No whitespace characters are permitted in the [String].
 *
 * @return A [Long] object holding the [Long] value represented by [this]
 * @throws NumberFormatException if the [String] does not contain a parsable [Long].
 * @see java.lang.Long.parseLong
 */
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

/**
 * Helps out with addWindowListener.
 */
fun Window.addWindowClosingListener(block: (WindowEvent) -> Unit) {
    addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            block(e)
        }
    })
}

/** Create a [Vector] in the same way that Kotlin creates collections. */
fun <T> vectorOf(): Vector<T> = Vector()

/** Create a [Vector] in the same way that Kotlin creates collections, including initial values. */
fun <T> vectorOf(vararg elements: T): Vector<T> =
    if (elements.isEmpty()) Vector() else Vector<T>().apply {
        for (element in elements) add(element)
    }

/** Fill a [Polygon] with a receiver function. */
private fun Graphics.fillPolygon(block: Polygon.() -> Unit) {
    fillPolygon(Polygon().apply(block))
}

/** Fill a [Polygon] using any number of points. */
fun Graphics.fillPolygon(vararg points: Pair<Int, Int>) {
    fillPolygon {
        for (point in points) addPoint(point.first, point.second)
    }
}

/** Convert a [Double] to radians using Java's Math library. */
fun Double.toRadians() = Math.toRadians(this)

/** Check if a character is a space character. */
fun Char.isSpaceChar() = Character.isSpaceChar(this)