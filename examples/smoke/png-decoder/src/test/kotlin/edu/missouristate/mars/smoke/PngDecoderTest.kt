package edu.missouristate.mars.smoke

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.simulator.ProgramArgumentList
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class PngDecoderTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun drawsEveryPixelOfTheFullSizeImage() {
    val input: Path = SOURCES.resolve("torture.png")
    val expected = ImageIO.read(input.toFile())
    Assertions.assertEquals(DISPLAY_STRIDE, expected.getWidth())
    Assertions.assertEquals(DISPLAY_STRIDE, expected.getHeight())
    val scalar = run(input, expected, SUCCESS, scalar = true)
    val msa = run(input, expected, SUCCESS)
    println("Full PNG guest instructions: scalar=$scalar, MSA=$msa")
    Assertions.assertTrue(
        msa < scalar,
        "Expected fewer guest instructions with MSA ($msa vs $scalar)",
    )
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun handlesUnalignedRowsAndVectorTails(littleEndian: Boolean) {
    for (width in listOf(1, 5, 6, 15, 16, 17, 31, 32, 33, 511)) {
      val filters = intArrayOf(2, 2, 0, 1, 3, 4)
      val stride = 1 + width * 3
      val scanlines =
          ByteArray(stride * filters.size) { offset ->
            if (offset % stride == 0) filters[offset / stride].toByte()
            else (173 + offset * 53 + offset / stride * 97).toByte()
          }
      val input =
          Files.write(
              temporary.resolve("rows-$width.png"),
              png(width, filters.size, 2, storedZlib(scanlines)),
          )
      val expected = ImageIO.read(input.toFile())
      run(input, expected, SUCCESS, littleEndian = littleEndian, scalar = true)
      run(input, expected, SUCCESS, littleEndian = littleEndian)
    }
  }

  @Test
  fun decodesFixedAndStoredBlocksWithAllRowFilters() {
    val filtered: ByteArray = filteredScanlines()
    val fixed: ByteArray = png(2, 5, 2, fixedZlib(filtered))
    val stored: ByteArray = png(2, 5, 2, storedZlib(filtered))
    for (fixture in listOf(fixed, stored)) {
      val path = Files.write(temporary.resolve("blocks.png"), fixture)
      run(path, ImageIO.read(path.toFile()), SUCCESS)
    }
  }

  @Test
  fun rejectsTruncationBadChecksumsUnknownFiltersAndUnsupportedFormats() {
    val filtered: ByteArray = filteredScanlines()
    val zlib: ByteArray = storedZlib(filtered)
    val valid: ByteArray = png(2, 5, 2, zlib)
    val truncated = valid.clone()
    truncated[11] = -1
    val crc = valid.clone()
    crc[crc.size - 1] = (crc[crc.size - 1].toInt() xor 1).toByte()
    val adler = zlib.clone()
    adler[adler.size - 1] = (adler[adler.size - 1].toInt() xor 1).toByte()
    filtered[0] = 5
    for (invalid in
        listOf(
            truncated,
            crc,
            png(2, 5, 2, adler),
            png(2, 5, 2, storedZlib(filtered)),
            png(1, 5, 2, zlib),
        )) {
      val path = Files.write(temporary.resolve("invalid.png"), invalid)
      run(path, null, INVALID)
    }
    val rgba: Path = Files.write(temporary.resolve("rgba.png"), png(2, 5, 6, zlib))
    run(rgba, null, "Expected a non-interlaced 8-bit RGB PNG")
    run(temporary.resolve("missing.png"), null, "Cannot read PNG")
  }

  companion object {
    private val SOURCES: Path = Path.of("src/main/mips").toAbsolutePath()
    private const val DISPLAY_STRIDE = 512
    private const val FRAME_GUARD = 0x55AA_33CC
    private const val SUCCESS = "PNG displayed."
    private const val INVALID = "Invalid PNG or corrupt compressed image."
    private val HEADLESS: MarsGuiProxy =
        object : MarsGuiProxy {
          override val isGuiMode = false

          override fun postMessage(message: String) {}

          override fun postRunMessage(message: String) {}

          override fun readInput(maxLength: Int): String {
            throw UnsupportedOperationException("Use the console input stream")
          }

          override fun readInputWithPrompt(prompt: String): String {
            throw UnsupportedOperationException("Use the console input stream")
          }

          override fun clearRunConsole() {}
        }

    private fun run(
        input: Path,
        expected: BufferedImage?,
        message: String,
        littleEndian: Boolean = true,
        scalar: Boolean = false,
    ): Long {
      val settings = Settings()
      settings.simulationSettings
          .setMemoryConfiguration("Default")
          .setExtendedAssemblerEnabled(true)
      val output = ByteArrayOutputStream()
      try {
        MarsComponent.build {
              coreModule(CoreModule(settings, 32))
              guiProxy(HEADLESS)
            }
            .context()
            .use { session ->
              session.selectTarget(MarsTarget.MARS64)
              session.memory.byteOrder = littleEndian
              PrintStream(output, true, StandardCharsets.UTF_8).use { console ->
                SessionSyscallEnvironment(
                        session.memory,
                        ByteArrayInputStream(byteArrayOf()),
                        console,
                        console,
                        input.parent,
                    )
                    .use { syscalls ->
                      val program: MIPSprogram =
                          session.assemble(listOf(SOURCES.resolve("viewer.asm")))
                      val arguments =
                          when {
                            scalar -> listOf(input.fileName.toString(), "--scalar")
                            input.fileName.toString() != "torture.png" ->
                                listOf(input.fileName.toString())
                            else -> emptyList()
                          }
                      ProgramArgumentList(arguments).storeProgramArguments(session)
                      program.setSyscallEnvironment(syscalls)
                      val base = session.memory.heapBaseAddress
                      val guards = buildList {
                        if (expected != null && expected.width < DISPLAY_STRIDE) {
                          for (row in 0..<expected.height) {
                            add(base + 4 * (row * DISPLAY_STRIDE + expected.width))
                          }
                        }
                        if (expected != null && expected.height < DISPLAY_STRIDE) {
                          add(base + 4 * expected.height * DISPLAY_STRIDE)
                        }
                      }
                      for (address in guards) session.memory.setWord(address, FRAME_GUARD)
                      var instructions = 0L
                      Assertions.assertTrue(
                          program.simulate(250_000_000) { instructions = it },
                          "The decoder must exit within the test budget",
                      )
                      Assertions.assertEquals(
                          if (expected == null) 1 else 0,
                          program.exitCode,
                          output.toString(StandardCharsets.UTF_8),
                      )
                      Assertions.assertTrue(
                          output.toString(StandardCharsets.UTF_8).contains(message),
                          output.toString(StandardCharsets.UTF_8),
                      )
                      if (expected == null) {
                        Assertions.assertEquals(
                            0,
                            session.memory.getWordNoNotify(base),
                        )
                        Assertions.assertEquals(
                            0,
                            session.memory.getWordNoNotify(
                                base + 4 * (DISPLAY_STRIDE * DISPLAY_STRIDE - 1),
                            ),
                        )
                        return instructions
                      }
                      for (row in 0..<expected.getHeight()) {
                        for (column in 0..<expected.getWidth()) {
                          val address: Int = base + 4 * (row * DISPLAY_STRIDE + column)
                          val rgb = expected.getRGB(column, row) and 0xffffff
                          val observed: Int = session.memory.getWordNoNotify(address)
                          if (rgb != observed) {
                            Assertions.assertEquals(
                                rgb,
                                observed,
                                "Pixel at (" + column + ", " + row + ")",
                            )
                          }
                        }
                      }
                      for (address in guards) {
                        Assertions.assertEquals(
                            FRAME_GUARD,
                            session.memory.getWordNoNotify(address),
                            "Guard at 0x${address.toString(16)}",
                        )
                      }
                      return instructions
                    }
              }
            }
      } catch (failure: ProcessingException) {
        throw AssertionError(failure.errors()!!.generateErrorAndWarningReport(), failure)
      }
    }

    private fun filteredScanlines(): ByteArray {
      return byteArrayOf(
          0,
          11,
          22,
          33,
          44,
          55,
          66,
          1,
          3,
          9,
          7,
          -4,
          100,
          2,
          2,
          0,
          6,
          80,
          -41,
          17,
          20,
          3,
          5,
          -5,
          99,
          1,
          7,
          31,
          4,
          2,
          10,
          6,
          17,
          -18,
          24,
      )
    }

    private fun storedZlib(bytes: ByteArray): ByteArray {
      val output = ByteArrayOutputStream()
      val deflater = Deflater(Deflater.NO_COMPRESSION)
      try {
        DeflaterOutputStream(output, deflater).use { stream ->
          stream.write(bytes)
        }
      } finally {
        deflater.end()
      }
      Assertions.assertEquals(0, (output.toByteArray()[2].toInt() ushr 1) and 3)
      return output.toByteArray()
    }

    private fun fixedZlib(bytes: ByteArray): ByteArray {
      val output = ByteArrayOutputStream()
      output.writeBytes(byteArrayOf(0x78, 0x01))
      var pending = 3 // BFINAL=1, BTYPE=01.
      var bits = 3
      for (value in bytes) {
        val symbol = value.toUByte().toInt()
        val code = if (symbol < 144) 0x30 + symbol else 0x190 + symbol - 144
        val length = if (symbol < 144) 8 else 9
        pending = pending or ((Integer.reverse(code) ushr (32 - length)) shl bits)
        bits += length
        while (bits >= 8) {
          output.write(pending)
          pending = pending ushr 8
          bits -= 8
        }
      }
      // EOB is seven zero bits. At most two bytes finish and pad the block.
      output.write(pending)
      if (bits > 1) output.write(0)
      val adler = Adler32()
      adler.update(bytes)
      DataOutputStream(output).writeInt(adler.getValue().toInt())
      return output.toByteArray()
    }

    private fun png(width: Int, height: Int, type: Int, zlib: ByteArray): ByteArray {
      val output = ByteArrayOutputStream()
      val data = DataOutputStream(output)
      data.writeLong(-0x76afb1b8f2f5e5f6L)
      val header = ByteArrayOutputStream()
      val fields = DataOutputStream(header)
      fields.writeInt(width)
      fields.writeInt(height)
      fields.write(byteArrayOf(8, type.toByte(), 0, 0, 0))
      chunk(data, "IHDR", header.toByteArray())
      chunk(data, "IDAT", zlib)
      chunk(data, "IEND", ByteArray(0))
      return output.toByteArray()
    }

    private fun chunk(output: DataOutputStream, name: String, contents: ByteArray) {
      val type: ByteArray = name.toByteArray(StandardCharsets.US_ASCII)
      val crc = CRC32()
      crc.update(type)
      crc.update(contents)
      output.writeInt(contents.size)
      output.write(type)
      output.write(contents)
      output.writeInt(crc.getValue().toInt())
    }
  }
}
