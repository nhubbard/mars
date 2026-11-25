package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RegisterSnapshotTest {
  @Test
  fun `snapshots retain every bit and accept the normal register names`(@TempDir directory: Path) {
    val source = directory.resolve("registers.asm").also { it.writeText(".text\nmain: nop\n") }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
        assertEquals(0L, case.readRegister("zero"))
        for (number in 1..31) {
          val value =
              when (number % 3) {
                0 -> Long.MIN_VALUE
                1 -> Long.MAX_VALUE
                else -> 0x1234567800000000L + number
              }
          case.setRegister(number.toString(), value)
          assertEquals(value, case.readRegister(number.toString()))
          assertEquals(value, case.readRegister("$$number"))
        }
        assertEquals(case.readRegister("4"), case.readRegister("a0"))
        assertEquals(case.readRegister("4"), case.readRegister($$"$a0"))
        for (name in listOf("absent", "32", "-1", "v32", "", " ", "$")) {
          assertThrows<CaseFailure> { case.readRegister(name) }
        }
      }
    }
  }

  @Test
  fun `snapshots do not change stopped execution or its undo boundary`(@TempDir directory: Path) {
    val source =
        directory.resolve("undo.asm").also {
          it.writeText(
              $$"""
                  .text
              main:
                  daddiu $v1, $zero, -1
                  dsll $v1, $v1, 32
                  ori $v1, $v1, 0x1234
              ready:
                  nop
              """
                  .trimIndent(),
          )
        }
    DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
      val program = machine.assemble(listOf(source))
      AssemblyCase(machine, program.symbolMap(), ByteArrayOutputStream()).use { case ->
        val trace = case.traceExecution()
        case.runUntil("ready")
        val pc = machine.registerFile.programCounterBits
        val snapshot = case.readRegister("v1")
        assertEquals((-1L shl 32) or 0x1234, snapshot)
        repeat(3) { assertEquals(snapshot, case.readRegister("v1")) }
        assertEquals(pc, machine.registerFile.programCounterBits)
        assertEquals(3, trace.snapshot().instructions)
        assertTrue(machine.backStepper.canBackstep())
        machine.simulator().backstepAsync().join()
        assertEquals(-1L shl 32, case.readRegister("v1"))
        assertEquals((-1L shl 32) or 0x1234, snapshot)
        case.step()
        assertEquals(snapshot, case.readRegister("v1"))
      }
    }
  }
}
