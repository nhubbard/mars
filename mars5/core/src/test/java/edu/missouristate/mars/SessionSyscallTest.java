package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.instructions.syscalls.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSyscallTest {
  @TempDir Path directory;

  @Test
  void allRandomSyscallsUseSessionStreamsAcrossInstructionContexts() throws Exception {
    try (var first = session();
        var second = session()) {
      var a = new InstructionContext(first, first.syscallEnvironment);
      var b = new InstructionContext(second, second.syscallEnvironment);
      seed(a, 123);
      seed(b, 987);
      Random expectedA = new Random(123), expectedB = new Random(987);
      var legacy = new edu.missouristate.mars.mips.instructions.syscalls.RandomStreams();
      legacy.setSeed(7, 42);
      assertThat(a.randomStreams()).isNotSameAs(b.randomStreams()).isNotSameAs(legacy);
      for (int i = 0; i < 3; i++) {
        assertRandomValues(a, expectedA);
        seed(b, 987 + i);
        expectedB.setSeed(987 + i);
        assertRandomValues(b, expectedB);
        a = new InstructionContext(first, first.syscallEnvironment);
      }
      assertThat(legacy.get(7).nextInt()).isEqualTo(new Random(42).nextInt());
      // Different stream identifiers in one session also retain independent positions.
      assertThat(first.randomStreams.get(8)).isNotSameAs(first.randomStreams.get(7));
    }
  }

  @Test
  void fileDescriptorsArePrivateAndResettingOneSessionKeepsTheOtherOpen() throws Exception {
    var input = directory.resolve("input");
    Files.writeString(input, "abcdef");
    try (var first = session();
        var second = session()) {
      var a = first.syscallEnvironment;
      var b = second.syscallEnvironment;
      assertThat(a.openFile(input.toString(), 0)).isEqualTo(3);
      assertThat(b.openFile(input.toString(), 0)).isEqualTo(3);
      var bytes = new byte[3];
      assertThat(a.readFromFile(3, bytes, 3)).isEqualTo(3);
      assertThat(new String(bytes)).isEqualTo("abc");
      assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(3);
      assertThat(new String(bytes)).isEqualTo("abc");
      var source = directory.resolve("exit.s");
      Files.writeString(source, ".text\nli $v0, 10\nsyscall\n");
      first.assemble(List.of(source));
      assertThat(a.readFromFile(3, bytes, 3)).isEqualTo(-1);
      assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(3);
      assertThat(new String(bytes)).isEqualTo("def");
      assertThat(b.readFromFile(3, bytes, 3)).isZero();
      second.close();
      assertThat(b.readFromFile(3, bytes, 3)).isEqualTo(-1);
      assertThat(first.getProgram().simulate(20)).isTrue();
    }
  }

  @Test
  void fileModesCapacityAndDescriptorReuseFollowMarsConventions() throws Exception {
    try (var session = session()) {
      var io = session.syscallEnvironment;
      var path = directory.resolve("output").toString();
      var bytes = new byte[] {65, 0, 66};
      assertThat(io.openFile(path, 2)).isEqualTo(-1);
      assertThat(io.openFile(directory.resolve("missing").toString(), 0)).isEqualTo(-1);
      assertThat(io.openFile(path, 1)).isEqualTo(3);
      assertThat(io.openFile(path, 1)).isEqualTo(-1);
      assertThat(io.readFromFile(3, bytes, 3)).isEqualTo(-1);
      assertThat(io.writeToFile(3, bytes, 3)).isEqualTo(3);
      io.closeFile(3);
      assertThat(io.openFile(path, 9)).isEqualTo(3);
      assertThat(io.writeToFile(3, bytes, 2)).isEqualTo(2);
      io.closeFile(3);
      assertThat(Files.readAllBytes(Path.of(path))).containsExactly(65, 0, 66, 65, 0);
      assertThat(io.openFile(path, 0)).isEqualTo(3);
      assertThat(io.writeToFile(3, bytes, 1)).isEqualTo(-1);
      assertThat(io.readFromFile(-1, bytes, 1)).isEqualTo(-1);
      assertThat(io.readFromFile(3, bytes, 4)).isEqualTo(-1);
      assertThat(io.writeToFile(99, bytes, 1)).isEqualTo(-1);
      assertThat(io.writeToFile(1, bytes, -1)).isEqualTo(-1);
      for (int fd = 4; fd < 32; fd++) {
        assertThat(io.openFile(directory.resolve("file" + fd).toString(), 1)).isEqualTo(fd);
      }
      assertThat(io.openFile(directory.resolve("overflow").toString(), 1)).isEqualTo(-1);
      io.closeFile(15);
      assertThat(io.openFile(directory.resolve("reused").toString(), 1)).isEqualTo(15);
    }
  }

  @Test
  void consoleInputDialogsAndRawDescriptorsShareOnlyTheirSuppliedStreams() throws Exception {
    try (var session = session()) {
      var output = new ByteArrayOutputStream();
      var error = new ByteArrayOutputStream();
      var input =
          new ByteArrayInputStream(
              "42\nraw\n3.5\n2.25\nZ\nabcdef\nanswer\n".getBytes(StandardCharsets.UTF_8));
      try (var io =
          new SessionSyscallEnvironment(
              session.memory, input, new PrintStream(output), new PrintStream(error))) {
        assertThat(io.readInt()).isEqualTo(42);
        var bytes = new byte[4];
        assertThat(io.readFromFile(0, bytes, 4)).isEqualTo(4);
        assertThat(new String(bytes)).isEqualTo("raw\n");
        assertThat(io.readFloat()).isEqualTo(3.5f);
        assertThat(io.readDouble()).isEqualTo(2.25);
        assertThat(io.readChar()).isEqualTo('Z');
        assertThat(io.readString(3)).isEqualTo("abc");
        assertThat(io.showInputDialog("prompt")).isEqualTo("answer");
        assertThat(io.readString(20)).isNull();
        assertThatThrownBy(io::readInt).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(io::readChar).isInstanceOf(IndexOutOfBoundsException.class);
        io.printString("value=");
        io.printInt(42);
        io.printChar(' ');
        io.printFloat(1.5f);
        io.printDouble(2.5);
        io.writeToFile(1, new byte[] {33}, 1);
        io.writeToFile(2, new byte[] {63}, 1);
        io.showMessageDialog("message", "title", 1);
        io.closeFile(0);
        io.closeFile(1);
        io.closeFile(2);
        io.close();
        io.printString("open");
        assertThat(output.toString()).isEqualTo("value=42 1.52.5!open");
        assertThat(error.toString()).contains("prompt: ?[title] message");
        int heap = session.memory.heapAddress;
        assertThat(io.sbrk(4)).isEqualTo(heap);
        assertThat(session.memory.heapAddress).isEqualTo(heap + 4);
      }
    }
  }

  @Test
  void assembledFileSyscallsWorkWithDefaultEnvironmentAndNoGlobals() throws Exception {
    try (var session = session()) {
      var output = directory.resolve("mips-output");
      var source = directory.resolve("files.s");
      Files.writeString(
          source,
          """
          .data
          path: .asciiz "%s"
          bytes: .byte 65, 0, 66
          buffer: .space 3
          .text
          la $a0, path
          li $a1, 1
          li $v0, 13
          syscall
          move $s0, $v0
          move $a0, $s0
          la $a1, bytes
          li $a2, 3
          li $v0, 15
          syscall
          move $s1, $v0
          li $v0, 16
          syscall
          la $a0, path
          li $a1, 0
          li $v0, 13
          syscall
          move $a0, $v0
          la $a1, buffer
          li $a2, 3
          li $v0, 14
          syscall
          move $s2, $v0
          lbu $s3, 2($a1)
          li $v0, 16
          syscall
          li $v0, 10
          syscall
          """
              .formatted(output));
      assertThat(session.assemble(List.of(source)).simulate(200)).isTrue();
      assertThat(Files.readAllBytes(output)).containsExactly(65, 0, 66);
      assertThat(session.registerFile.getValue(16)).isEqualTo(3);
      assertThat(session.registerFile.getValue(17)).isEqualTo(3);
      assertThat(session.registerFile.getValue(18)).isEqualTo(3);
      assertThat(session.registerFile.getValue(19)).isEqualTo(66);
    }
  }

  @Test
  void assembledConsoleSyscallsUseSuppliedStreamsAndHandleEndOfInput() throws Exception {
    try (var session = session()) {
      var output = new ByteArrayOutputStream();
      var source = directory.resolve("console.s");
      Files.writeString(
          source,
          """
          .data
          buffer: .space 8
          .text
          li $v0, 5
          syscall
          move $a0, $v0
          li $v0, 1
          syscall
          la $a0, buffer
          li $a1, 8
          li $v0, 8
          syscall
          li $v0, 4
          syscall
          li $v0, 8
          syscall
          lbu $s0, 0($a0)
          lbu $s1, 1($a0)
          li $v0, 10
          syscall
          """);
      var program = session.assemble(List.of(source));
      try (var io =
          new SessionSyscallEnvironment(
              session.memory,
              new ByteArrayInputStream("42\nhello\n".getBytes(StandardCharsets.UTF_8)),
              new PrintStream(output),
              new PrintStream(new ByteArrayOutputStream()))) {
        program.setSyscallEnvironment(io);
        assertThat(program.simulate(100)).isTrue();
        assertThat(output.toString()).isEqualTo("42hello\n");
        assertThat(session.registerFile.getValue(16)).isEqualTo('\n');
        assertThat(session.registerFile.getValue(17)).isZero();
        // Session resets release default resources, but supplied environments remain caller-owned.
        var path = directory.resolve("custom-output").toString();
        assertThat(io.openFile(path, 1)).isEqualTo(3);
        session.setMemoryConfiguration("Default");
        assertThat(io.writeToFile(3, new byte[] {65}, 1)).isEqualTo(1);
      }
    }
  }

  private static void seed(InstructionContext context, int seed) {
    context.registerFile.updateRegister(4, 7);
    context.registerFile.updateRegister(5, seed);
    new SyscallRandSeed().simulate(null, context);
  }

  private static void assertRandomValues(InstructionContext context, Random expected)
      throws Exception {
    context.registerFile.updateRegister(4, 7);
    new SyscallRandInt().simulate(null, context);
    assertThat(context.registerFile.getValue(4)).isEqualTo(expected.nextInt());
    context.registerFile.updateRegister(4, 7);
    context.registerFile.updateRegister(5, 1000);
    new SyscallRandIntRange().simulate(null, context);
    assertThat(context.registerFile.getValue(4)).isEqualTo(expected.nextInt(1000));
    context.registerFile.updateRegister(4, 7);
    new SyscallRandFloat().simulate(null, context);
    assertThat(context.coprocessor1.getFloatFromRegister(0)).isEqualTo(expected.nextFloat());
    new SyscallRandDouble().simulate(null, context);
    assertThat(context.coprocessor1.getDoubleFromRegisterPair(0)).isEqualTo(expected.nextDouble());
  }

  static MarsContext session() {
    var settings = new Settings();
    settings
        .getSimulationSettings()
        .setMemoryConfiguration("Default")
        .setDelayedBranchingEnabled(false)
        .setStartAtMainEnabled(false)
        .setBareMachineEnabled(false)
        .setExtendedAssemblerEnabled(true)
        .setWarningsAreErrors(false);
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings))
        .guiProxy(
            new MarsGuiProxy() {
              public boolean isGuiMode() {
                return false;
              }

              public void postMessage(String message) {}

              public void postRunMessage(String message) {}

              public String readInput(int maxLength) {
                return "";
              }

              public String readInputWithPrompt(String prompt) {
                return "";
              }

              public void clearRunConsole() {}
            })
        .build()
        .context();
  }
}
