package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MemoryStoreTraceTest {
  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun recordsOnlyExecutionByteStoresAndKeepsRepeatedValues(littleEndian: Boolean) {
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val memory = machine.memory
          memory.byteOrder = littleEndian
          val port = memory.memoryMapBaseAddress + 0x10
          val address = port.toUInt().toLong()
          MemoryStoreTrace(memory, address, 4).use { trace ->
            memory.setByte(port, 7)
            duringExecution<Unit, Exception> {
              memory.getByte(port)
              memory.setByte(port - 1, 5)
              memory.setByte(port + 4, 9)
            }
            trace.expectByteStores(address)
            trace.expectNone()
            duringExecution<Unit, Exception> {
              memory.setByte(port, -1)
              memory.setByte(port, -1)
              memory.setByte(port, 6)
            }
            trace.expectByteStores(address, 255, 255, 6)
            assertThrows<CaseFailure> { trace.expectByteStores(address, 255, 6) }
            assertThrows<CaseFailure> { trace.expectByteStores(address, 255, 255, 7) }
          }
        }
  }

  @Test
  fun rejectsWiderStoresThatOverlapEitherRegionBoundary() {
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val memory = machine.memory
          val port = memory.memoryMapBaseAddress + 0x10
          val address = port.toUInt().toLong()
          for (start in listOf(port, port + 2)) {
            MemoryStoreTrace(memory, address + 1, 2).use { trace ->
              duringExecution<Unit, Exception> { memory.set(start, 0x12345678, 4) }
              val error = assertThrows<CaseFailure> { trace.expectByteStores(address + 1, 0) }
              assertTrue(error.message!!.contains("4-byte store"))
              assertThrows<CaseFailure> { trace.expectNone() }
            }
          }
        }
  }

  @Test
  fun rejectsOtherPortsAndClosesIndependentSubscriptions() {
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val memory = machine.memory
          val port = memory.memoryMapBaseAddress + 0x10
          val address = port.toUInt().toLong()
          MemoryStoreTrace(memory, address, 4).use { prefix ->
            MemoryStoreTrace(memory, address, 4).use { complete ->
              duringExecution<Unit, Exception> { memory.setByte(port, 63) }
              prefix.close()
              prefix.close()
              duringExecution<Unit, Exception> { memory.setByte(port + 1, 6) }
              prefix.expectByteStores(address, 63)
              val error = assertThrows<CaseFailure> { complete.expectByteStores(address, 63, 6) }
              assertTrue(error.message!!.contains("Expected byte store 2"))
            }
          }
        }
  }

  @Test
  fun validatesArgumentsAndRejectsAnIncompleteOverflowedTrace() {
    MarsComponent.build {
          guiProxy(HeadlessGuiProxy())
        }
        .context()
        .use { machine ->
          val memory = machine.memory
          val port = memory.memoryMapBaseAddress + 0x10
          val address = port.toUInt().toLong()
          assertThrows<IllegalArgumentException> { MemoryStoreTrace(memory, -1, 1) }
          assertThrows<IllegalArgumentException> { MemoryStoreTrace(memory, address, 0) }
          assertThrows<IllegalArgumentException> {
            MemoryStoreTrace(memory, Long.MAX_VALUE, 2)
          }
          MemoryStoreTrace(memory, address, 1).use { trace ->
            assertThrows<IllegalArgumentException> { trace.expectByteStores(address + 1) }
            assertThrows<IllegalArgumentException> { trace.expectByteStores(address, 256) }
            val values = IntArray(65_536) { it and 255 }
            duringExecution<Unit, Exception> {
              values.forEach { memory.setByte(port, it) }
            }
            trace.expectByteStores(address, *values)
            duringExecution<Unit, Exception> { memory.setByte(port, 0) }
            val error = assertThrows<CaseFailure> { trace.expectByteStores(address, *values) }
            assertTrue(error.message!!.contains("exceeds 65536 writes"))
          }
        }
  }
}
