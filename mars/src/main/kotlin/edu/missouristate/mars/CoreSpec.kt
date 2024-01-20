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

package edu.missouristate.mars

import com.uchuhimo.konf.ConfigSpec

object CoreSpec : ConfigSpec() {
    // These settings came from CoreSettings/Settings.properties.
    val enableExtendedAssembler by optional(
        true,
        description = "Flag to determine whether the program being assembled is limited to basic MIPS instructions and formats."
    )
    val enableBareMachine by optional(
        false,
        description = "Flag to determine whether to force bare machine mode (register numbers only)."
    )
    val displayAddressesInHex by optional(
        true,
        description = "Flag to determine whether to display addresses in hexadecimal format."
    )
    val displayValuesInHex by optional(
        true,
        description = "Flag to determine whether to display values in hexadecimal format."
    )
    val enableExceptionHandler by optional(
        false,
        description = "Flag to determine whether to enable the exception handler."
    )
    val enableDelayedBranching by optional(
        false,
        description = "Flag to determine whether to enable delayed branching simulation."
    )
    val upgradeWarningsAsErrors by optional(
        false,
        description = "Flag to determine whether warnings should be upgraded to errors."
    )
    val enableProgramArguments by optional(
        false,
        description = "Flag to determine whether provided arguments are passed to the program."
    )
    val startAtMain by optional(
        false,
        description = "Flag to determine whether the program should be executed from the 'main' label."
    )
    val showSyscallInput by optional(
        false,
        description = "Flag to determine whether syscall input should be prompted to the user."
    )
    val enableSelfModifyingCode by optional(
        false,
        description = "Flag to determine whether code written to the .text segment by the running program is executable."
    )
    val exceptionHandlerFile by optional(
        "",
        description = "The name of the file that contains the exception handler to use."
    )
    val memoryConfiguration by optional(
        "",
        description = "A string representing the current memory configuration."
    )

    // These settings came from Config.properties.
    val messageLimit by optional(
        1000000,
        description = "The maximum length of scrolled text in the Message and Run I/O tabs, in bytes."
    )
    val errorLimit by optional(
        200,
        description = "The maximum number of errors that can be recorded in one assemble operation."
    )
    val backstepLimit by optional(
        2000,
        description = "The maximum number of backstep operations that can be taken. An instruction may produce more than one at a time."
    )
    val fileExtensions by optional(
        arrayOf("asm", "s"),
        description = "Acceptable file extensions for MIPS assembly files. Separate with spaces."
    )
    val asciiTable by optional(
        arrayOf(
            '\u0000', '.', '.', '.', '.', '.', '.', '.', '\b', '\t',
            '\n', '\u000b', '\u000c', '\r', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', ' ', '!', '"', '#', '$', '%', '&', '\'',
            '(', ')', '*', '+', ',', '-', '.', '/', '0', '1',
            '2', '3', '4', '5', '6', '7', '8', '9', ':', ';',
            '<', '=', '>', '?', '@', 'A', 'B', 'C', 'D', 'E',
            'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
            'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y',
            'Z', '[', '\\', ']', '^', '_', '`', 'a', 'b', 'c',
            'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
            'x', 'y', 'z', '{', '|', '}', '~', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.', '.', '.', '.', '.',
            '.', '.', '.', '.', '.', '.'
        ),
        description = "The set of ASCII strings to use for ASCII display or printing data segment contents."
    )
}