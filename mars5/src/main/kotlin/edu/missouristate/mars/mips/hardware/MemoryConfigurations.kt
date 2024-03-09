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

package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.CoreSpec
import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.hardware.RegisterFile.programCounter

/**
 * Models the collection of MIPS memory configurations.
 * The default configuration is based on SPIM. Starting with MARS 3.7,
 * the configuration can be changed.
 */
object MemoryConfigurations {
    internal var configurations: ArrayList<MemoryConfiguration>? = null
    internal var defaultConfiguration: MemoryConfiguration? = null
    internal var currentConfiguration: MemoryConfiguration? = null

    // Be careful, these arrays are parallel and position-sensitive.
    // The getters in this and in MemoryConfiguration depend on this
    // sequence.  Should be refactored...  The order comes from the
    // original listed order in Memory.java, where most of these were
    // "final" until Mars 3.7 and changeable memory configurations.
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
        "memory map limit address"
    )

    // The default configuration comes from SPIM
    private val defaultConfigurationItemValues = intArrayOf(
        0x00400000,  // .text Base Address
        0x10000000,  // Data Segment base address
        0x10000000,  // .extern Base Address
        0x10008000,  // Global Pointer $gp)
        0x10010000,  // .data base Address
        0x10040000,  // heap base address
        0x7fffeffc,  // stack pointer $sp (from SPIM not MIPS)
        0x7ffffffc,  // stack base address
        0x7fffffff,  // highest address in user space
        -0x80000000,  // lowest address in kernel space
        -0x80000000,  // .ktext base address
        -0x7ffffe80,  // exception handler address
        -0x70000000,  // .kdata base address
        -0x10000,  // MMIO base address
        -0x1,  // highest address in kernel (and memory)
        0x7fffffff,  // data segment limit address
        0x0ffffffc,  // text limit address
        -0x10001,  // kernel data segment limit address
        -0x70000004,  // kernel text limit address
        0x10040000,  // stack limit address
        -0x1 // memory map limit address
    )

    // Compact allows 16 bit addressing, data segment starts at 0
    private val dataBasedCompactConfigurationItemValues = intArrayOf(
        0x00003000,  // .text Base Address
        0x00000000,  // Data Segment base address
        0x00001000,  // .extern Base Address
        0x00001800,  // Global Pointer $gp)
        0x00000000,  // .data base Address
        0x00002000,  // heap base address
        0x00002ffc,  // stack pointer $sp
        0x00002ffc,  // stack base address
        0x00003fff,  // highest address in user space
        0x00004000,  // lowest address in kernel space
        0x00004000,  // .ktext base address
        0x00004180,  // exception handler address
        0x00005000,  // .kdata base address
        0x00007f00,  // MMIO base address
        0x00007fff,  // highest address in kernel (and memory)
        0x00002fff,  // data segment limit address
        0x00003ffc,  // text limit address
        0x00007eff,  // kernel data segment limit address
        0x00004ffc,  // kernel text limit address
        0x00002000,  // stack limit address
        0x00007fff // memory map limit address
    )

    // Compact allows 16-bit addressing, text segment starts at 0
    private val textBasedCompactConfigurationItemValues = intArrayOf(
        0x00000000,  // .text Base Address
        0x00001000,  // Data Segment base address
        0x00001000,  // .extern Base Address
        0x00001800,  // Global Pointer $gp)
        0x00002000,  // .data base Address
        0x00003000,  // heap base address
        0x00003ffc,  // stack pointer $sp
        0x00003ffc,  // stack base address
        0x00003fff,  // highest address in user space
        0x00004000,  // lowest address in kernel space
        0x00004000,  // .ktext base address
        0x00004180,  // exception handler address
        0x00005000,  // .kdata base address
        0x00007f00,  // MMIO base address
        0x00007fff,  // highest address in kernel (and memory)
        0x00003fff,  // data segment limit address
        0x00000ffc,  // text limit address
        0x00007eff,  // kernel data segment limit address
        0x00004ffc,  // kernel text limit address
        0x00003000,  // stack limit address
        0x00007fff // memory map limit address
    )

    @JvmStatic
    fun buildConfigurationCollection() {
        if (configurations == null) {
            configurations = arrayListOf(
                MemoryConfiguration("Default", "Default", configurationItemNames, defaultConfigurationItemValues),
                MemoryConfiguration("CompactDataAtZero", "Compact, Data at Address 0", configurationItemNames, dataBasedCompactConfigurationItemValues),
                MemoryConfiguration("CompactTextAtZero", "Compact, Text at Address 0", configurationItemNames, textBasedCompactConfigurationItemValues)
            )
            defaultConfiguration = configurations!!.first()
            currentConfiguration = defaultConfiguration
            // Get current config from settings
            setCurrentConfiguration(getConfigurationByName(Globals.config[CoreSpec.memoryConfiguration]))
        }
    }

    @JvmStatic
    val configurationsIterator: Iterator<MemoryConfiguration> get() {
        if (configurations == null) buildConfigurationCollection()
        return configurations!!.iterator()
    }

    fun getConfigurationByName(name: String): MemoryConfiguration? {
        val configurationsIterator = configurationsIterator
        while (configurationsIterator.hasNext()) {
            val config = configurationsIterator.next()
            if (name == config.configurationIdentifier) return config
        }
        return null
    }

    fun getDefaultConfiguration(): MemoryConfiguration {
        if (defaultConfiguration == null) buildConfigurationCollection()
        return defaultConfiguration!!
    }

    @JvmStatic
    fun getCurrentConfiguration(): MemoryConfiguration {
        if (currentConfiguration == null) buildConfigurationCollection()
        return currentConfiguration!!
    }

    @JvmStatic
    fun setCurrentConfiguration(config: MemoryConfiguration?): Boolean {
        if (config == null) return false
        if (config != currentConfiguration) {
            currentConfiguration = config
            Globals.memory.clear()
            RegisterFile.getUserRegister("\$gp")!!.setResetValue(config.globalPointer)
            RegisterFile.getUserRegister("\$sp")!!.setResetValue(config.stackPointer)
            programCounter.setResetValue(config.textBaseAddress)
            RegisterFile.initializeProgramCounter(config.textBaseAddress)
            RegisterFile.resetRegisters()
            return true
        } else return false
    }

    @JvmStatic val defaultTextBaseAddress: Int get() = defaultConfigurationItemValues[0]
    @JvmStatic val defaultDataSegmentBaseAddress: Int get() = defaultConfigurationItemValues[1]
    @JvmStatic val defaultExternBaseAddress: Int get() = defaultConfigurationItemValues[2]
    @JvmStatic val defaultGlobalPointer: Int get() = defaultConfigurationItemValues[3]
    @JvmStatic val defaultDataBaseAddress: Int get() = defaultConfigurationItemValues[4]
    @JvmStatic val defaultHeapBaseAddress: Int get() = defaultConfigurationItemValues[5]
    @JvmStatic val defaultStackPointer: Int get() = defaultConfigurationItemValues[6]
    @JvmStatic val defaultStackBaseAddress: Int get() = defaultConfigurationItemValues[7]
    @JvmStatic val defaultUserHighAddress: Int get() = defaultConfigurationItemValues[8]
    @JvmStatic val defaultKernelBaseAddress: Int get() = defaultConfigurationItemValues[9]
    @JvmStatic val defaultKernelTextBaseAddress: Int get() = defaultConfigurationItemValues[10]
    @JvmStatic val defaultExceptionHandlerAddress: Int get() = defaultConfigurationItemValues[11]
    @JvmStatic val defaultKernelDataBaseAddress: Int get() = defaultConfigurationItemValues[12]
    @JvmStatic val defaultMemoryMapBaseAddress: Int get() = defaultConfigurationItemValues[13]
    @JvmStatic val defaultKernelHighAddress: Int get() = defaultConfigurationItemValues[14]
    @JvmStatic val defaultDataSegmentLimitAddress: Int get() = defaultConfigurationItemValues[15]
    @JvmStatic val defaultTextLimitAddress: Int get() = defaultConfigurationItemValues[16]
    @JvmStatic val defaultKernelDataSegmentLimitAddress: Int get() = defaultConfigurationItemValues[17]
    @JvmStatic val defaultKernelTextLimitAddress: Int get() = defaultConfigurationItemValues[18]
    @JvmStatic val defaultStackLimitAddress: Int get() = defaultConfigurationItemValues[19]
    @JvmStatic val memoryMapLimitAddress: Int get() = defaultConfigurationItemValues[20]
}