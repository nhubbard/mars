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

package edu.missouristate.mars.assembler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestUserKernelAddressSpace {
    private lateinit var instance: Assembler.UserKernelAddressSpace

    @BeforeEach
    fun reset() {
        instance = Assembler.UserKernelAddressSpace(0x1000, 0x10000)
    }

    @Test
    fun testConstructor() {
        assertEquals(0x1000, instance.address[Assembler.UserKernelAddressSpace.AddressSpace.USER])
        assertEquals(0x10000, instance.address[Assembler.UserKernelAddressSpace.AddressSpace.KERNEL])
        assertEquals(0, instance.currentAddressSpace)
    }

    @Test
    fun testGet() {
        assertEquals(0x1000, instance.get())
        instance.currentAddressSpace = Assembler.UserKernelAddressSpace.AddressSpace.KERNEL
        assertEquals(0x10000, instance.get())
    }

    @Test
    fun testSet() {
        instance.set(0x1100)
        assertEquals(0x1100, instance.get())
        instance.currentAddressSpace = Assembler.UserKernelAddressSpace.AddressSpace.KERNEL
        instance.set(0x11000)
        assertEquals(0x11000, instance.get())
    }

    @Test
    fun testIncrement() {
        instance.increment(0x1000)
        assertEquals(0x1000 + 0x1000, instance.get())

        instance.currentAddressSpace = Assembler.UserKernelAddressSpace.AddressSpace.KERNEL
        instance.increment(0x1000)
        assertEquals(0x10000 + 0x1000, instance.get())
    }

    @Test
    fun testSetAddressSpace() {
        instance.currentAddressSpace = Assembler.UserKernelAddressSpace.AddressSpace.USER
        assertEquals(0, instance.currentAddressSpace)
        instance.currentAddressSpace = Assembler.UserKernelAddressSpace.AddressSpace.KERNEL
        assertEquals(1, instance.currentAddressSpace)
    }
}