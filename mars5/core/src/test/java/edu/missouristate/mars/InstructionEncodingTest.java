package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InstructionEncodingTest {
  @TempDir Path directory;

  static Stream<Arguments> vectors() throws Exception {
    try (var input =
        Objects.requireNonNull(
            InstructionEncodingTest.class.getResourceAsStream("/basic-encoding-vectors.tsv"), "basic-encoding-vectors.tsv")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.startsWith("#") && !line.isBlank())
              .map(
                  line -> {
                    String[] fields = line.split("\t");
                    return Arguments.of(fields[0], fields[1], (int) Long.parseLong(fields[2], 16));
                  })
              .toList()
              .stream();
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("vectors")
  void basicInstructionEncodingsMatchIndependentFrozenVectors(
      String type, String source, int expected) throws Exception {
    try (var session = TestSessions.create()) {
      var file = directory.resolve("instruction.s");
      Files.writeString(file, ".text\n" + source + "\nlabel:\ntarget: nop\n");
      var program = session.assemble(List.of(file), null, false, false);
      var statement = program.getMachineList().getFirst();
      assertThat(statement.getInstruction().getClass().getSimpleName()).isEqualTo(type);
      assertThat(statement.getBinaryStatement()).isEqualTo(expected);
      var decoded = new ProgramStatement(expected, statement.getAddress(), session.instructionSet);
      assertThat(decoded.getInstruction().getName())
          .isEqualTo(statement.getInstruction().getName());
    }
  }
}
