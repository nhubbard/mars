package edu.missouristate.mars.mips.hardware

import java.util.Collections

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

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

/** The built-in memory layouts and their reset addresses. */
open class MemoryConfigurations {
    companion object {
        private val configurationItemNames = arrayOf(
            ".text base address",
            "data segment base address",
            ".extern base address",
            "global pointer \$gp",
            ".data base address",
            "heap base address",
            "stack pointer \$sp",
            "stack base address",
            "user space high address",
            "kernel space base address",
            ".ktext base address",
            "exception handler address",
            ".kdata base address",
            "MMIO base address",
            "kernel space high address",
            "data segment limit address",
            "text limit address",
            "kernel data segment limit address",
            "kernel text limit address",
            "stack limit address",
            "memory map limit address",
        )

        private val defaultConfigurationItemValues = intArrayOf(
            0x00400000, // .text Base Address
            0x10000000, // Data Segment base address
            0x10000000, // .extern Base Address
            0x10008000, // Global Pointer $gp)
            0x10010000, // .data base Address
            0x10040000, // heap base address
            0x7fffeffc, // stack pointer $sp (from SPIM not MIPS)
            0x7ffffffc, // stack base address
            0x7fffffff, // highest address in user space
            0x80000000.toInt(), // lowest address in kernel space
            0x80000000.toInt(), // .ktext base address
            0x80000180.toInt(), // exception handler address
            0x90000000.toInt(), // .kdata base address
            0xffff0000.toInt(), // MMIO base address
            0xffffffff.toInt(), // highest address in kernel (and memory)
            0x7fffffff, // data segment limit address
            0x0ffffffc, // text limit address
            0xfffeffff.toInt(), // kernel data segment limit address
            0x8ffffffc.toInt(), // kernel text limit address
            0x10040000, // stack limit address
            0xffffffff.toInt(), // memory map limit address
        )

        private val dataBasedCompactConfigurationItemValues = intArrayOf(
            0x00003000, // .text Base Address
            0x00000000, // Data Segment base address
            0x00001000, // .extern Base Address
            0x00001800, // Global Pointer $gp)
            0x00000000, // .data base Address
            0x00002000, // heap base address
            0x00002ffc, // stack pointer $sp
            0x00002ffc, // stack base address
            0x00003fff, // highest address in user space
            0x00004000, // lowest address in kernel space
            0x00004000, // .ktext base address
            0x00004180, // exception handler address
            0x00005000, // .kdata base address
            0x00007f00, // MMIO base address
            0x00007fff, // highest address in kernel (and memory)
            0x00002fff, // data segment limit address
            0x00003ffc, // text limit address
            0x00007eff, // kernel data segment limit address
            0x00004ffc, // kernel text limit address
            0x00002000, // stack limit address
            0x00007fff, // memory map limit address
        )

        private val textBasedCompactConfigurationItemValues = intArrayOf(
            0x00000000, // .text Base Address
            0x00001000, // Data Segment base address
            0x00001000, // .extern Base Address
            0x00001800, // Global Pointer $gp)
            0x00002000, // .data base Address
            0x00003000, // heap base address
            0x00003ffc, // stack pointer $sp
            0x00003ffc, // stack base address
            0x00003fff, // highest address in user space
            0x00004000, // lowest address in kernel space
            0x00004000, // .ktext base address
            0x00004180, // exception handler address
            0x00005000, // .kdata base address
            0x00007f00, // MMIO base address
            0x00007fff, // highest address in kernel (and memory)
            0x00003fff, // data segment limit address
            0x00000ffc, // text limit address
            0x00007eff, // kernel data segment limit address
            0x00004ffc, // kernel text limit address
            0x00003000, // stack limit address
            0x00007fff, // memory map limit address
        )

        private class Layout(val id: String, private val name: String, private val addresses: IntArray) {
            fun snapshot() = MemoryConfiguration(id, name, configurationItemNames, addresses)
        }

        private val layouts = listOf(
            Layout("Default", "Default", defaultConfigurationItemValues),
            Layout("CompactDataAtZero", "Compact, Data at Address 0", dataBasedCompactConfigurationItemValues),
            Layout("CompactTextAtZero", "Compact, Text at Address 0", textBasedCompactConfigurationItemValues),
        )
        private val configurations by lazy { Collections.unmodifiableList(layouts.map(Layout::snapshot)) }

        /** Unknown identifiers select the standard layout. Each call returns an independent snapshot. */
        @JvmStatic
        fun createConfiguration(identifier: String?): MemoryConfiguration =
            (layouts.firstOrNull { it.id == identifier } ?: layouts.first()).snapshot()

        @JvmStatic
        fun getConfigurationByName(name: String): MemoryConfiguration? =
            configurations.firstOrNull { it.configurationIdentifier() == name }

        @JvmStatic
        fun getDefaultConfiguration(): MemoryConfiguration = configurations.first()

        @JvmStatic
        fun getDefaultTextBaseAddress(): Int = getDefaultConfiguration().textBaseAddress

        @JvmStatic
        fun getDefaultGlobalPointer(): Int = getDefaultConfiguration().globalPointer

        @JvmStatic
        fun getDefaultStackPointer(): Int = getDefaultConfiguration().stackPointer
    }
}
