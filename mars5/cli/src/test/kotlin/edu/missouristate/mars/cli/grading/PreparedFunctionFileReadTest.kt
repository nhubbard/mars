package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionFileReadTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private val close = "move \$a0,\$t5\nli \$v0,16\nsyscall"
  private val reader =
      $$"""
      move $t7,$a1
      move $t8,$a2
      move $t9,$zero
      move $a1,$zero
      li $v0,13
      syscall
      bltzc $v0,open_failed
      nop
      move $t5,$v0
      next_read:
      move $a0,$t5
      move $a1,$t7
      move $a2,$t8
      li $v0,14
      syscall
      bltzc $v0,read_failed
      nop
      beqzc $v0,done
      nop
      daddu $t9,$t9,$v0
      bc next_read
      nop
      done:
      move $a0,$t5
      li $v0,16
      syscall
      move $v0,$t9
      move $v1,$zero
      jrc $ra
      nop
      read_failed:
      move $a0,$t5
      li $v0,16
      syscall
      move $v0,$zero
      li $v1,2
      jrc $ra
      nop
      open_failed:
      move $v0,$zero
      li $v1,1
      jrc $ra
      nop
      """
          .trimIndent()

  private fun image(body: String = reader, path: String = "file.bin"): AssemblyImage =
      session().use { machine ->
        val source = directory.resolve("read.asm")
        val text =
            ".data\npath: .asciiz \"$path\"\nother: .asciiz \"other.bin\"\nbuffer: .space 32\n.text\n" +
                "main: li \$v0,10\nsyscall\nreturned: bc main\nnop\nfunction:\n$body\n" +
                "publish: move \$v0,\$zero\nmove \$v1,\$zero\njrc \$ra\nnop\n" +
                "late_close:\n$close\nmove \$v0,\$zero\njrc \$ra\nnop\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(source), mapOf(source to text)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun fixture() =
      FunctionFileReadCheck(
          "file.bin",
          "000180ff0203040506",
          7,
          maxReadSize = 3,
          firstDescriptor = 19,
      )

  private fun call(
      definition: FunctionFileReadCheck = fixture(),
      request: Long = 7,
  ): FunctionCallCheck {
    val length = definition.bytes.length / 2
    val error = definition.failAfterBytes?.let { it <= length } == true
    return FunctionCallCheck(
        "function",
        "returned",
        mapOf("a2" to request),
        mapOf(
            "v0" to if (definition.missing || error) 0L else length.toLong(),
            "v1" to if (definition.missing) 1L else if (error) 2L else 0L,
        ),
        argumentAddresses =
            mapOf("a0" to FunctionDataAddress("path"), "a1" to FunctionDataAddress("buffer")),
        readFile = true,
    )
  }

  private fun contract(definition: FunctionFileReadCheck = fixture(), request: Long = 7) =
      FunctionCheckCase("read fixture", listOf(call(definition, request)), fileRead = definition)

  private fun evaluate(body: String = reader, case: FunctionCheckCase = contract()) =
      PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

  @Test
  fun `normal empty short reads and smaller equivalent requests consume the full input`() {
    for (bytes in listOf("", "00", "000180ff0203040506")) for (request in listOf(1L, 7L)) {
      val result = evaluate(case = contract(fixture().copy(bytes = bytes), request))
      assertTrue(result.passed, result.failure)
    }
    val result =
        evaluate(case = contract(fixture().copy(maxReadSize = ConsoleMemoryCheck.MAX_BYTES)))
    assertTrue(result.passed, result.failure)
  }

  @Test
  fun `read failure at zero one and EOF is distinct from EOF and closes the acquired descriptor`() {
    for (position in listOf(0, 1, 9, 10)) {
      val result = evaluate(case = contract(fixture().copy(failAfterBytes = position)))
      assertTrue(result.passed, result.failure)
    }
    val emptyError = evaluate(case = contract(fixture().copy(bytes = "", failAfterBytes = 0)))
    assertTrue(emptyError.passed, emptyError.failure)
    val missing = evaluate(case = contract(fixture().copy(bytes = "", missing = true)))
    assertTrue(missing.passed, missing.failure)
    val swallowed =
        evaluate(
            reader.replace("li \$v1,2", "li \$v1,0"),
            contract(fixture().copy(failAfterBytes = 1)),
        )
    assertFalse(swallowed.passed)
    val wrongDescriptor = reader.replace("move \$a0,\$t5\nmove \$a1", "li \$a0,3\nmove \$a1")
    val coincidentError = evaluate(wrongDescriptor, contract(fixture().copy(failAfterBytes = 0)))
    assertFalse(coincidentError.passed)
    assertTrue(
        coincidentError.failure!!.contains(
            "positive bounded read requests on the acquired descriptor",
        ),
        coincidentError.failure,
    )
  }

  @Test
  fun `wrong paths descriptors lifecycle ordering and incomplete consumption are rejected`() {
    val faults =
        mapOf(
            "no open" to reader.replace("li \$v0,13\nsyscall", "li \$v0,-1"),
            "write open" to reader.replace("move \$a1,\$zero", "li \$a1,1"),
            "double open" to
                reader.replace("move \$t5,\$v0", "move \$t5,\$v0\nli \$v0,13\nsyscall"),
            "leaked descriptor" to reader.replace(close, "nop"),
            "double close" to reader.replace(close, "$close\n$close"),
            "assumed descriptor three" to
                reader.replace("move \$a0,\$t5\nmove \$a1", "li \$a0,3\nmove \$a1"),
            "wrong close" to reader.replace(close, "li \$a0,3\nli \$v0,16\nsyscall"),
            "short read treated as EOF" to reader.replace("bc next_read", "bc done"),
            "extra read after EOF" to reader.replace("done:\n", "done:\nli \$v0,14\nsyscall\n"),
            "read after close" to reader.replace(close, "$close\nli \$v0,14\nsyscall"),
            "read before open" to "li \$a0,19\nli \$v0,14\nsyscall\ndla \$a0,path\n$reader",
        )
    for ((name, body) in faults) assertFalse(evaluate(body).passed, name)
    assertFalse(
        evaluate(
                case =
                    contract()
                        .copy(
                            calls =
                                listOf(
                                    call()
                                        .copy(
                                            argumentAddresses =
                                                mapOf(
                                                    "a0" to FunctionDataAddress("other"),
                                                    "a1" to FunctionDataAddress("buffer"),
                                                ),
                                        ),
                                ),
                        ),
            )
            .passed,
    )
    assertFalse(evaluate(case = contract(request = 0)).passed)
    assertFalse(evaluate(case = contract(request = 8)).passed)
    val missing = fixture().copy(bytes = "", missing = true)
    val extraClose =
        reader.replace("open_failed:\n", "open_failed:\nli \$a0,19\nli \$v0,16\nsyscall\n")
    assertFalse(evaluate(extraClose, contract(missing)).passed)
  }

  @Test
  fun `fixture reads are per invocation and publication cannot hide a callee leak`() {
    val first = call()
    val publish = FunctionCallCheck("publish", "returned", emptyMap(), mapOf("v0" to 0L))
    val both = evaluate(case = contract().copy(calls = listOf(first, first)))
    assertTrue(both.passed, both.failure)
    val noIo = evaluate(case = contract().copy(calls = listOf(first, publish)))
    assertTrue(noIo.passed, noIo.failure)
    val undeclared =
        evaluate(case = contract().copy(calls = listOf(first.copy(readFile = false), first)))
    assertFalse(undeclared.passed)
    assertTrue(undeclared.failure!!.contains("must perform no file operations"), undeclared.failure)
    val laterClose = publish.copy(entry = "late_close")
    val leak =
        evaluate(reader.replace(close, "nop"), contract().copy(calls = listOf(first, laterClose)))
    assertFalse(leak.passed)
    assertTrue(
        leak.failure!!.contains("Call 1") && leak.failure.contains("all fixture files closed"),
        leak.failure,
    )
    val noRead = first.copy(entry = "publish", expectedRegisters = mapOf("v0" to 0L))
    assertFalse(evaluate(case = contract().copy(calls = listOf(noRead))).passed)
  }

  @Test
  fun `fixture paths never access host contents and concurrent cases have independent cursors`() {
    val host = directory.resolve("file.bin")
    Files.writeString(host, "host contents remain untouched")
    val definition = fixture().copy(path = host.toString())
    val hostResult =
        PreparedFunctionChecks.evaluate(
                image(path = host.toString()),
                listOf(contract(definition)),
                ::session,
            )
            .single()
    assertTrue(hostResult.passed, hostResult.failure)
    assertEquals("host contents remain untouched", Files.readString(host))
    val image = image()
    Executors.newFixedThreadPool(2).use { pool ->
      val cases = listOf(fixture().copy(bytes = "00"), fixture())
      val results = cases.map { check ->
        pool.submit<Boolean> {
          PreparedFunctionChecks.evaluate(
                  image,
                  listOf(contract(check).copy(calls = listOf(call(check), call(check)))),
                  ::session,
              )
              .single()
              .passed
        }
      }
      assertTrue(results.all { it.get() })
    }
  }

  @Test
  fun `invalid fixture declarations and mutated calls fail before a session is created`() {
    val changes =
        listOf<() -> Any>(
            { fixture().copy(path = "") },
            { fixture().copy(path = " ") },
            { fixture().copy(path = "a\u0000b") },
            { fixture().copy(path = "\ud800") },
            { fixture().copy(path = "x".repeat(4097)) },
            { fixture().copy(bytes = "0") },
            { fixture().copy(bytes = "xz") },
            { fixture().copy(bytes = "00".repeat(ConsoleMemoryCheck.MAX_BYTES + 1)) },
            { fixture().copy(maxRequest = 0) },
            { fixture().copy(maxRequest = ConsoleMemoryCheck.MAX_BYTES + 1) },
            { fixture().copy(maxReadSize = 0) },
            { fixture().copy(maxReadSize = ConsoleMemoryCheck.MAX_BYTES + 1) },
            { fixture().copy(failAfterBytes = -1) },
            { fixture().copy(failAfterBytes = ConsoleMemoryCheck.MAX_BYTES + 1) },
            { fixture().copy(firstDescriptor = 2) },
            { fixture().copy(firstDescriptor = 32) },
            { fixture().copy(missing = true) },
            { fixture().copy(bytes = "", missing = true, failAfterBytes = 0) },
            { contract().copy(fileRead = null) },
            { contract().copy(calls = listOf(call().copy(readFile = false))) },
        )
    for (change in changes) assertThrows<IllegalArgumentException> { change() }
    val full = fixture().copy(bytes = "a5".repeat(ConsoleMemoryCheck.MAX_BYTES))
    contract(full)
    assertThrows<IllegalArgumentException> {
      contract(full).copy(initialMemory = listOf(FunctionMemoryCheck("buffer", "00")))
    }
    val calls = mutableListOf(call())
    val case = contract().copy(calls = calls)
    calls[0] = call().copy(readFile = false)
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(image(), listOf(case), { error("Validate before creating") })
    }
  }

  @Test
  fun `cancellation propagates and returned or failing sessions are disposed`() {
    val image = image()
    var owned: MarsContext? = null
    assertThrows<CancellationException> {
      PreparedFunctionChecks.evaluate(
          image,
          listOf(contract().copy(calls = listOf(call(), call()))),
          {
            session().also { owned = it }
          },
          {
            if (owned?.registerFile?.getLongValueNoNotify(2) == 9L)
                throw CancellationException("After the first complete read")
          },
      )
    }
    assertThrows<IllegalStateException> { owned!!.claimStoppedSession() }
    for (body in listOf(reader, reader.replace(close, "nop"))) {
      lateinit var machine: MarsContext
      PreparedFunctionChecks.evaluate(
          image(body),
          listOf(contract()),
          { session().also { machine = it } },
      )
      assertThrows<IllegalStateException> { machine.claimStoppedSession() }
    }
    assertTrue(
        PreparedFunctionChecks.evaluate(image, listOf(contract()), ::session).single().passed,
    )
  }

  @Test
  fun `different files missing paths and changed request bounds share one execution session`() {
    for (failure in listOf<Int?>(null, 3)) {
      val definition =
          fixture()
              .copy(
                  bytes = "01",
                  maxReadSize = 2,
                  failAfterBytes = failure,
                  additionalFiles = mapOf("other.bin" to "0203040506"),
              )
      val other =
          call(definition, 3)
              .copy(
                  argumentAddresses =
                      mapOf(
                          "a0" to FunctionDataAddress("other"),
                          "a1" to FunctionDataAddress("buffer"),
                      ),
                  expectedRegisters =
                      mapOf(
                          "v0" to if (failure == null) 5L else 0L,
                          "v1" to if (failure == null) 0L else 2L,
                      ),
                  fileReadInvocation = FunctionFileReadInvocation("other.bin", 3),
              )
      val missing =
          call(definition, 1)
              .copy(
                  setupMemory = listOf(FunctionMemoryCheck("path", "6d697373696e6700")),
                  expectedRegisters = mapOf("v0" to 0L, "v1" to 1L),
                  fileReadInvocation = FunctionFileReadInvocation("missing", 1),
              )
      val calls = listOf(call(definition), other, other, missing)
      var created = 0
      val result =
          PreparedFunctionChecks.evaluate(
                  image(),
                  listOf(contract(definition).copy(calls = calls)),
                  {
                    created++
                    session()
                  },
              )
              .single()
      assertTrue(result.passed, result.failure)
      assertEquals(1, created, "File changes must not create new machines")
      val wrongPath = other.copy(fileReadInvocation = FunctionFileReadInvocation("file.bin", 3))
      val wrongBound = other.copy(fileReadInvocation = FunctionFileReadInvocation("other.bin", 2))
      for (bad in listOf(wrongPath, wrongBound)) {
        assertFalse(evaluate(case = contract(definition).copy(calls = listOf(bad))).passed)
      }
    }
  }

  @Test
  fun `additional file data is bounded validated and copied before session creation`() {
    val invalid =
        listOf<() -> Any>(
            { fixture().copy(additionalFiles = mapOf("file.bin" to "00")) },
            { fixture().copy(additionalFiles = mapOf("" to "00")) },
            { fixture().copy(additionalFiles = mapOf("other.bin" to "0")) },
            { fixture().copy(additionalFiles = (0..63).associate { "file-$it" to "" }) },
            {
              fixture()
                  .copy(
                      additionalFiles =
                          mapOf("other.bin" to "00".repeat(ConsoleMemoryCheck.MAX_BYTES)),
                  )
            },
            { FunctionFileReadInvocation("", 1) },
            { FunctionFileReadInvocation("other.bin", 0) },
            { FunctionFileReadInvocation("other.bin", ConsoleMemoryCheck.MAX_BYTES + 1) },
            {
              call()
                  .copy(
                      readFile = false,
                      fileReadInvocation = FunctionFileReadInvocation("other.bin", 1),
                  )
            },
        )
    for (change in invalid) assertThrows<IllegalArgumentException> { change() }
    val extra = mutableMapOf("other.bin" to "ff")
    val definition = fixture().copy(additionalFiles = extra)
    val other =
        call(definition)
            .copy(
                expectedRegisters = mapOf("v0" to 1L, "v1" to 0L),
                argumentAddresses =
                    mapOf(
                        "a0" to FunctionDataAddress("other"),
                        "a1" to FunctionDataAddress("buffer"),
                    ),
                fileReadInvocation = FunctionFileReadInvocation("other.bin", 7),
            )
    val case = contract(definition).copy(calls = listOf(other))
    val result =
        PreparedFunctionChecks.evaluate(
                image(),
                listOf(case),
                {
                  extra["other.bin"] = "0001"
                  session()
                },
            )
            .single()
    assertTrue(result.passed, result.failure)
    extra["other.bin"] = "not hex"
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(),
          listOf(case),
          { error("Validate before creating a machine") },
      )
    }
  }
}
