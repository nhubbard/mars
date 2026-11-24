package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.devices.DataCacheSimulator.Options
import edu.missouristate.mars.devices.DataCacheSimulator.Placement
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DataCacheCheckTest {
  @Test
  fun `independent caches can start at a breakpoint reset cold and close a frozen prefix`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("cache.asm").also {
          it.writeText(
              $$"""
                  .data
                  .align 4
              values: .word 1,2,3,4,5,6,7,8
                  .text
                  la $t0, values
              begin:
                  lw $t1, 0($t0)
                  sw $t1, 4($t0)
                  lw $t2, 16($t0)
              finish:
                  lw $t3, 0($t0)
              again:
                  lw $t4, 4($t0)
                  li $v0, 10
                  syscall
              """
                  .trimIndent(),
          )
        }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
        case.runUntil("begin")
        val direct = case.connectDataCache(Options(1, 4))
        val full = case.connectDataCache(Options(2, 4, Placement.FULL, 2))
        case.runUntil("finish")
        direct.expectCounts(3, 1)
        full.expectCounts(3, 1)
        assertEquals(2L, direct.snapshot().misses)
        case.runUntil("again")
        direct.expectCounts(4, 1)
        full.expectCounts(4, 2)
        val prefix = direct.snapshot()
        direct.close()
        direct.close()
        assertThrows<IllegalStateException> { direct.reset() }
        full.reset()
        case.readWord("values")
        case.readBytes("values", 4)
        case.setWord("values", 9)
        full.expectCounts(0, 0)
        case.runToExit()
        full.expectCounts(1, 0)
        direct.expectCounts(4, 1)
        assertEquals(4L, prefix.accesses)
        assertThrows<IllegalArgumentException> { full.expectCounts(-1, 0) }
        assertThrows<IllegalArgumentException> { full.expectCounts(1, -1) }
        assertThrows<IllegalArgumentException> { full.expectCounts(1, 2) }
        assertThrows<CaseFailure> { full.expectCounts(1, 1) }
        assertThrows<CaseFailure> { case.connectDataCache() }
      }
    }
  }

  @Test
  fun `case failure detaches observation and the next session starts cold`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("cleanup.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 0
                  .text
                  la $t0, value
                  lw $t1, 0($t0)
              mid:
                  nop
                  lw $t2, 0($t0)
                  nop
              """
                  .trimIndent(),
          )
        }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      val case = AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream())
      val cache = case.connectDataCache()
      assertThrows<CaseFailure> {
        case.use {
          it.runUntil("mid")
          cache.expectCounts(0, 0)
        }
      }
      cache.expectCounts(1, 0)
      machine.simulator().runAsync(2, null).join()
      cache.expectCounts(1, 0)
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { fresh ->
        val next = fresh.assemble(listOf(source))
        AssemblyCase(fresh, next.symbolMap(), ByteArrayOutputStream()).use { independent ->
          val cold = independent.connectDataCache()
          cold.expectCounts(0, 0)
          independent.runUntil("mid")
          cold.expectCounts(1, 0)
          cache.expectCounts(1, 0)
        }
      }
    }
  }

  @Test
  fun `complete access logs are immutable and overflow is explicit`(@TempDir directory: Path) {
    val source =
        directory.resolve("log.asm").also {
          it.writeText(
              $$"""
                  .data
              value: .word 7
                  .text
                  la $t0, value
                  lw $t1, 0($t0)
                  sb $zero, 1($t0)
                  lw $t1, 0($t0)
              ready:
                  lw $t2, 0($t0)
                  li $v0, 10
                  syscall
              """
                  .trimIndent(),
          )
        }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
        val ordinary = case.connectDataCache()
        assertThrows<IllegalStateException> { ordinary.recordedAccesses() }
        assertThrows<IllegalArgumentException> {
          case.connectDataCache(logAccesses = true, logLimit = 0)
        }
        val complete = case.connectDataCache(logAccesses = true)
        val small = case.connectDataCache(logAccesses = true, logLimit = 2)
        case.runUntil("ready")
        val events = complete.recordedAccesses()
        assertEquals(listOf(4, 1, 4), events.map { it.length })
        val address = case.readDataAddress("value")
        assertEquals(listOf(address, address + 1, address), events.map { it.address })
        assertEquals(listOf(false, true, false), events.map { it.write })
        assertThrows<UnsupportedOperationException> { (events as MutableList).clear() }
        assertThrows<CaseFailure> { small.recordedAccesses() }
        complete.close()
        small.reset()
        assertEquals(emptyList<Any>(), small.recordedAccesses())
        case.runToExit()
        assertEquals(1, small.recordedAccesses().size)
        assertEquals(events, complete.recordedAccesses())
      }
    }
  }

  @Test
  fun `wide addresses preserve tags and backstep keeps forward observations`(
      @TempDir directory: Path,
  ) {
    val source =
        directory.resolve("wide.asm").also {
          it.writeText(
              $$"""
                  .data 0x100000000
              value: .word 17
                  .text
                  dla $t0, value
                  lw $t1, 0($t0)
              ready:
                  nop
              """
                  .trimIndent(),
          )
        }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
        val cache = case.connectDataCache(Options(1, 1))
        case.runUntil("ready")
        cache.expectCounts(1, 0)
        assertEquals(0x40000000L, cache.snapshot().blocks.single().tag)
        machine.simulator().backstepAsync().join()
        cache.expectCounts(1, 0)
        case.step()
        cache.expectCounts(2, 1)
      }
    }
  }
}
