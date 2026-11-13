package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.StopEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreSessionIntegrationTest {
  @TempDir Path directory;

  static Stream<Path> corpus() throws Exception {
    var resources = Objects.requireNonNull(CoreSessionIntegrationTest.class.getResource("/testPrograms"), "testPrograms");
    try (var paths = Files.list(Path.of(resources.toURI()))) {
      return paths.filter(path -> path.toString().endsWith(".asm")).sorted().toList().stream();
    }
  }

  @ParameterizedTest
  @MethodSource("corpus")
  void assemblesExistingCorpusWithoutCompatibilityClasses(Path source) throws Exception {
    assertThatThrownBy(() -> Class.forName("edu.missouristate.mars.Globals"))
        .isInstanceOf(ClassNotFoundException.class);
    try (var session = TestSessions.create()) {
      assertThat(session.assemble(List.of(source)).instructionCount()).isPositive();
    }
  }

  @Test
  void independentComponentsAssembleAndRunConcurrently() throws Exception {
    try (var first = TestSessions.create();
        var second = TestSessions.create()) {
      var a = directory.resolve("a.s");
      var b = directory.resolve("b.s");
      String template =
          """
          .data
          value: .word 0
          .text
          li $t0,%d
          la $t1,value
          sw $t0,0($t1)
          li $v0,10
          syscall
          """;
      Files.writeString(a, template.formatted(42));
      Files.writeString(b, template.formatted(99));
      first.assemble(List.of(a));
      second.assemble(List.of(b));
      var left = first.simulator().runAsync(0, null);
      var right = second.simulator().runAsync(0, null);
      assertThat(left.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.NORMAL_EXIT);
      assertThat(right.get(5, TimeUnit.SECONDS).reason()).isEqualTo(StopEvent.Reason.NORMAL_EXIT);
      assertThat(first.registerFile.getValue(8)).isEqualTo(42);
      assertThat(second.registerFile.getValue(8)).isEqualTo(99);
      assertThat(first.memory.getWord(first.memory.dataBaseAddress)).isEqualTo(42);
      assertThat(second.memory.getWord(second.memory.dataBaseAddress)).isEqualTo(99);
      assertThat(first.registerFile).isNotSameAs(second.registerFile);
      assertThat(first.memory).isNotSameAs(second.memory);
    }
  }
}
