/*
 * Copyright (c) 2003-2025, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2025-present, Nicholas Hubbard
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

package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Settings
import edu.missouristate.mars.mips.hardware.Memory

open class BaseDumpFormatTest {
    internal val step = Memory.WORD_LENGTH_BYTES
    internal val dataBase = Memory.dataSegmentBaseAddress
    internal val dataLimit = (Memory.dataSegmentLimitAddress / 4) - Memory.WORD_LENGTH_BYTES
    internal val textBase = Memory.textBaseAddress
    internal val textLimit = (Memory.textLimitAddress / 4) - Memory.WORD_LENGTH_BYTES
    internal val kernelTextBase = Memory.kernelTextBaseAddress
    internal val kernelTextLimit = Memory.kernelTextLimitAddress - Memory.WORD_LENGTH_BYTES
    
    private var originalHexAddresses: Boolean = false
    private var originalHexValues: Boolean = false
    private var originalSelfModifyingCode: Boolean = false

    fun setUpBase() {
        // Initialize with default settings
        Globals.initialize(false)
        
        // Store original settings
        originalHexAddresses = Globals.getSettings().getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX)
        originalHexValues = Globals.getSettings().getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX)
        originalSelfModifyingCode = Globals.getSettings().getBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED)
        
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, true)
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, true)
        
        // Set up memory
        for (address in dataBase..<dataLimit step step)
            Globals.memory.setWord(address, address - Memory.dataSegmentBaseAddress)
        
        Globals.getSettings().setBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED, true)
        for (address in textBase..<textLimit step step)
            Globals.memory.setRawWord(address, address - Memory.textBaseAddress)
        Globals.getSettings().setBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED, false)
    }

    fun tearDownBase() {
        // Restore original settings
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX, originalHexAddresses)
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, originalHexValues)
        Globals.getSettings().setBooleanSetting(Settings.SELF_MODIFYING_CODE_ENABLED, originalSelfModifyingCode)
        
        Globals.resetInitialized()
    }
}