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

/**
 * Cross-checks the extracted pipeline against every expansion produced by the previous pipeline.
 */
class PseudoExpansionTest {
  @TempDir Path directory;

  record Vector(String example, List<Integer> words) {}

  static Stream<Arguments> configurations() throws Exception {
    var groups = new LinkedHashMap<String, List<Vector>>();
    try (var input =
        PseudoExpansionTest.class.getResourceAsStream("/pseudo-expansion-vectors.tsv")) {
      new String(input.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.startsWith("#") && !line.isBlank())
          .forEach(
              line -> {
                String[] fields = line.split("\t", -1);
                var words =
                    fields[3].isBlank()
                        ? List.<Integer>of()
                        : Arrays.stream(fields[3].split(","))
                            .map(word -> (int) Long.parseLong(word, 16))
                            .toList();
                groups
                    .computeIfAbsent(fields[0] + " " + fields[1], key -> new ArrayList<>())
                    .add(new Vector(fields[2], words));
              });
    }
    return groups.entrySet().stream()
        .map(
            entry -> {
              String[] key = entry.getKey().split(" ");
              return Arguments.of(key[0], Boolean.parseBoolean(key[1]), entry.getValue());
            });
  }

  @ParameterizedTest(name = "{0}, delayed branching={1}")
  @MethodSource("configurations")
  void everyPseudoFormKeepsItsExactExpansion(String layout, boolean delayed, List<Vector> vectors)
      throws Exception {
    try (var session = TestSessions.create()) {
      session.setMemoryConfiguration(layout);
      session.settings.setDelayedBranchingEnabled(delayed);
      assertThat(vectors).hasSize(388);
      var source = directory.resolve("pseudo.s");
      for (var vector : vectors) {
        Files.writeString(source, ".text\n" + vector.example() + "\nlabel:\ntarget: nop\n");
        var program = session.assemble(List.of(source));
        var machine = program.getMachineList();
        var words =
            machine.subList(0, machine.size() - 1).stream()
                .map(ProgramStatement::getBinaryStatement)
                .toList();
        assertThat(words).as(vector.example()).containsExactlyElementsOf(vector.words());
      }
    }
  }
}
