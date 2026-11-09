package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryDumpTest {
  @TempDir Path directory;

  @Test
  void intelHexUsesExtendedLinearAddressRecordsPastTheFirst64KiB() throws Exception {
    try (var session = TestSessions.create()) {
      int start = session.memory.dataBaseAddress;
      for (int offset = 0; offset <= 0x10000; offset += Integer.BYTES) {
        session.memory.setWord(start + offset, 0x41424344);
      }
      var output = directory.resolve("data.hex");
      DumpFormatLoader.findByCommandDescriptor("HEX")
          .dumpMemoryRange(output.toFile(), start, start + 0x10000, session);
      var lines = Files.readAllLines(output);
      assertThat(lines).hasSize(16387);
      assertThat(lines.getFirst()).isEqualTo(":0400000041424344F2");
      assertThat(lines.subList(16383, lines.size()))
          .containsExactly(":04FFFC0041424344F7", ":020000040001F9",
              ":0400000041424344F2", ":00000001FF");
    }
  }

  @Test
  void segmentWindowWritesAPartialDataRowAndTextSource() throws Exception {
    var source = directory.resolve("program.asm");
    Files.writeString(source, ".data\n.word 1, 2, 3, 4, 5, 6, 7, 8, 9\n.text\nmain: addiu $t0, $zero, 7\n");
    try (var session = TestSessions.create()) {
      session.assemble(List.of(source));
      var format = DumpFormatLoader.findByCommandDescriptor("SegmentWindow");
      var output = directory.resolve("segment.txt");
      int data = session.memory.dataBaseAddress;
      format.dumpMemoryRange(output.toFile(), data, data + 8 * Integer.BYTES, session);
      assertThat(Files.readAllLines(output)).containsExactly(
          "0x10010000    0x00000001 0x00000002 0x00000003 0x00000004 0x00000005 0x00000006 0x00000007 0x00000008 ",
          "0x10010020    0x00000009 ");
      int text = session.memory.textBaseAddress;
      format.dumpMemoryRange(output.toFile(), text, text + Integer.BYTES, session);
      var lines = Files.readAllLines(output);
      assertThat(lines).hasSize(3);
      assertThat(lines.getLast()).startsWith("0x00400000  0x24080007  ")
          .endsWith("main: addiu $t0, $zero, 7");
    }
  }
}
