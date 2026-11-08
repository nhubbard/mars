package edu.missouristate.mars.cli;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.simulator.ProgramArgumentList;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Standalone CLI; its runtime classpath contains no Venus or legacy compatibility module. */
public final class MarsCli {
  private MarsCli() {}

  public static void main(String[] arguments) {
    System.exit(execute(arguments, System.in, System.out, System.err));
  }

  /** Executes one independent invocation without exiting the host process. */
  public static int execute(
      String[] arguments, InputStream input, PrintStream output, PrintStream error) {
    try (var session =
        DaggerMarsComponent.builder().guiProxy(new HeadlessGuiProxy()).build().context()) {
      session
          .settings
          .setDelayedBranchingEnabled(false)
          .setStartAtMainEnabled(false)
          .setExtendedAssemblerEnabled(true)
          .setWarningsAreErrors(false)
          .setBareMachineEnabled(false);
      var files = new ArrayList<Path>();
      var programArguments = new ArrayList<String>();
      String layout = "Default";
      int maximumSteps = 0;
      boolean assembleOnly = false;
      for (int i = 0; i < arguments.length; i++) {
        String arg = arguments[i];
        switch (arg) {
          case "--help", "-h", "h" -> {
            output.print(usage());
            return 0;
          }
          case "--", "pa" -> {
            programArguments.addAll(Arrays.asList(arguments).subList(i + 1, arguments.length));
            i = arguments.length;
          }
          case "--assemble-only", "a" -> assembleOnly = true;
          case "--delayed-branching", "db" -> session.settings.setDelayedBranchingEnabled(true);
          case "--start-at-main", "sm" -> session.settings.setStartAtMainEnabled(true);
          case "--no-pseudo", "np" -> session.settings.setExtendedAssemblerEnabled(false);
          case "--warnings-as-errors", "we" -> session.settings.setWarningsAreErrors(true);
          case "nc" -> {}
          case "--memory", "mc" -> {
            if (++i >= arguments.length) {
              throw new IllegalArgumentException("Missing memory layout");
            }
            layout = arguments[i];
          }
          case "--max-steps" -> {
            if (++i >= arguments.length) {
              throw new IllegalArgumentException("Missing step limit");
            }
            maximumSteps = Integer.parseInt(arguments[i]);
            if (maximumSteps < 1) {
              throw new IllegalArgumentException("Step limit must be positive");
            }
          }
          default -> {
            if (arg.startsWith("-")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            }
            files.add(Path.of(arg));
          }
        }
      }
      if (files.isEmpty()) {
        error.print(usage());
        return 2;
      }
      session.setMemoryConfiguration(layout);
      var program = session.assemble(files);
      error.print(program.getAssemblyDiagnostics().generateWarningReport());
      if (assembleOnly) {
        return 0;
      }
      new ProgramArgumentList(programArguments).storeProgramArguments(session);
      try (var io = new SessionSyscallEnvironment(session.memory, input, output, error)) {
        program.setSyscallEnvironment(io);
        if (!program.simulate(maximumSteps)) {
          error.println("Execution reached the step limit.");
          return 3;
        }
        return program.getExitCode();
      }
    } catch (ProcessingException failure) {
      error.print(failure.errors().generateErrorAndWarningReport());
      return 1;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      error.println(failure.getMessage());
      return 2;
    }
  }

  private static String usage() {
    return """
    Usage: mars [options] source.s [more sources.s] [-- program arguments]
      --assemble-only       Assemble without running (a)
      --delayed-branching    Enable branch delay slots (db)
      --start-at-main        Start at the exported main label (sm)
      --no-pseudo            Accept basic instructions only (np)
      --warnings-as-errors   Fail assembly on warnings (we)
      --memory LAYOUT        Default, CompactDataAtZero, or CompactTextAtZero (mc)
      --max-steps COUNT      Bound execution; return code 3 if the limit is reached
      --help                 Show this help
    Exit codes: simulated exit code, 1 for assembly/runtime errors, 2 for invalid arguments.
    """;
  }
}
