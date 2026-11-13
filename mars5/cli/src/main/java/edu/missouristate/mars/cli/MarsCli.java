package edu.missouristate.mars.cli;

import edu.missouristate.mars.ApplicationInfo;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.dump.DumpFormat;
import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import edu.missouristate.mars.mips.hardware.MemoryAddressRange;
import edu.missouristate.mars.simulator.ProgramArgumentList;
import edu.missouristate.mars.util.FilenameFinder;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Standalone CLI; its runtime classpath contains no Venus or legacy compatibility module. */
public final class MarsCli {
  private MarsCli() {}

  @SuppressWarnings("UnnecessaryModifier") // Keep the public JVM entry point callable from existing launchers.
  public static void main(String[] arguments) {
    System.exit(execute(arguments, System.in, System.out, System.err));
  }

  /** Executes one independent invocation without exiting the host process. */
  public static int execute(
      String[] arguments, InputStream input, PrintStream output, PrintStream error) {
    int assemblyErrorExitCode = 1;
    int simulationErrorExitCode = 1;
    boolean simulating = false;
    try (var session =
        DaggerMarsComponent.builder().guiProxy(new HeadlessGuiProxy()).build().context()) {
      session
          .settings
          .setDelayedBranchingEnabled(false)
          .setSelfModifyingCodeEnabled(false)
          .setStartAtMainEnabled(false)
          .setExtendedAssemblerEnabled(true)
          .setWarningsAreErrors(false)
          .setBareMachineEnabled(false);
      var files = new ArrayList<Path>();
      var programArguments = new ArrayList<String>();
      var dumps = new ArrayList<CliMemoryDump>();
      var report = new CliMachineReport();
      PrintStream reportOutput = output;
      String layout = "Default";
      int maximumSteps = 0;
      boolean assembleOnly = false;
      boolean project = false;
      boolean tracePhases = false;
      for (int i = 0; i < arguments.length; i++) {
        String arg = arguments[i];
        switch (arg.toLowerCase(Locale.ROOT)) {
          case "--help", "-h", "h" -> {
            output.print(usage());
            return 0;
          }
          case "--", "pa" -> {
            programArguments.addAll(Arrays.asList(arguments).subList(i + 1, arguments.length));
            i = arguments.length;
          }
          case "--assemble-only", "a" -> assembleOnly = true;
          case "--trace-phases", "d" -> tracePhases = true;
          case "ad", "da" -> {
            assembleOnly = true;
            tracePhases = true;
          }
          case "--project", "p" -> project = true;
          case "--delayed-branching", "db" -> session.settings.setDelayedBranchingEnabled(true);
          case "--self-modifying-code", "smc" -> session.settings.setSelfModifyingCodeEnabled(true);
          case "--start-at-main", "sm" -> session.settings.setStartAtMainEnabled(true);
          case "--no-pseudo", "np", "ne" -> session.settings.setExtendedAssemblerEnabled(false);
          case "--warnings-as-errors", "we" -> session.settings.setWarningsAreErrors(true);
          case "--messages-to-stderr", "me" -> reportOutput = error;
          case "--brief", "b" -> report.brief = true;
          case "--instruction-count", "ic" -> report.countInstructions = true;
          case "hex" -> report.format = CliMachineReport.Format.HEX;
          case "dec" -> report.format = CliMachineReport.Format.DECIMAL;
          case "ascii" -> report.format = CliMachineReport.Format.ASCII;
          case "--register" -> report.requireRegister(requiredValue(arguments, ++i, "register name"), session);
          case "--memory-range" -> report.addRange(requiredValue(arguments, ++i, "memory range"));
          case "nc" -> {}
          case "--dump", "dump" -> dumps.add(CliMemoryDump.parse(
              requiredValue(arguments, ++i, "dump segment or address range"),
              requiredValue(arguments, ++i, "dump format"),
              requiredValue(arguments, ++i, "dump filename")));
          case "--memory", "mc" -> layout = requiredValue(arguments, ++i, "memory layout");
          case "--assembly-error-exit-code" ->
              assemblyErrorExitCode =
                  Integer.decode(requiredValue(arguments, ++i, "assembly error exit code"));
          case "--simulation-error-exit-code" ->
              simulationErrorExitCode =
                  Integer.decode(requiredValue(arguments, ++i, "simulation error exit code"));
          case "--max-steps" -> {
            maximumSteps = Integer.parseInt(requiredValue(arguments, ++i, "step limit"));
            if (maximumSteps < 1) {
              throw new IllegalArgumentException("Step limit must be positive");
            }
          }
          default -> {
            Integer number = decode(arg);
            Integer assemblyStatus = decodeSuffix(arg, "ae");
            Integer simulationStatus = decodeSuffix(arg, "se");
            if (assemblyStatus != null) {
              assemblyErrorExitCode = assemblyStatus;
            } else if (simulationStatus != null) {
              simulationErrorExitCode = simulationStatus;
            } else if (arg.startsWith("$")) {
              report.requireRegister(arg, session);
            } else if (!arg.startsWith("-") && Files.isRegularFile(Path.of(arg))) {
              files.add(Path.of(arg));
            } else if (number != null) {
              maximumSteps = number;
            } else if (MemoryAddressRange.hasAddressPrefix(arg)) {
              report.addRange(arg);
            } else if (arg.startsWith("-")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            } else if (!report.tryAddRegister(arg, session)) {
              files.add(Path.of(arg));
            }
          }
        }
      }
      if (files.isEmpty()) {
        error.print(usage());
        return 2;
      }
      session.setMemoryConfiguration(layout);
      var sources = project ? projectSources(files) : files;
      PrintStream trace = tracePhases ? reportOutput : null;
      var program = trace == null
          ? session.assemble(sources)
          : session.assemble(sources, phase -> trace.println(switch (phase) {
              case TOKENIZING -> "--------  TOKENIZING BEGINS  -----------";
              case ASSEMBLING -> "--------  ASSEMBLY BEGINS  -----------";
            }));
      var diagnostics = program.getAssemblyDiagnostics();
      if (diagnostics != null) {
        error.print(diagnostics.generateWarningReport());
      }
      try {
        int status = 0;
        if (!assembleOnly) {
          new ProgramArgumentList(programArguments).storeProgramArguments(session);
          try (var io = new SessionSyscallEnvironment(session.memory, input, output, error)) {
            program.setSyscallEnvironment(io);
            simulating = true;
            if (trace != null) {
              trace.println("--------  SIMULATION BEGINS  -----------");
            }
            boolean finished = report.countInstructions
                ? program.simulate(maximumSteps, report::recordInstructionCount)
                : program.simulate(maximumSteps);
            status = finished ? program.getExitCode() : 3;
            if (!finished) {
              if (Thread.currentThread().isInterrupted()) {
                error.println("Execution cancelled.");
                return 130;
              }
              error.println("Execution reached the step limit.");
            }
          }
        }
        if (trace != null) {
          trace.println("\n--------  ALL PROCESSING COMPLETE  -----------");
        }
        return status;
      } finally {
        if (simulating) {
          report.write(session, reportOutput);
        }
        for (var dump : dumps) {
          dump.write(session, error);
        }
      }
    } catch (ProcessingException failure) {
      var diagnostics = failure.errors();
      if (diagnostics != null) {
        error.print(diagnostics.generateErrorAndWarningReport());
      } else {
        error.println(Objects.requireNonNullElse(failure.getMessage(), "MARS processing failed."));
      }
      return simulating ? simulationErrorExitCode : assemblyErrorExitCode;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      error.println(failure.getMessage());
      return 2;
    }
  }

  private static List<Path> projectSources(List<Path> files) {
    Path lead = files.getFirst().toAbsolutePath().normalize();
    Path directory = lead.getParent();
    if (directory == null) {
      throw new IllegalArgumentException("Project mode needs a lead source file");
    }
    var sources = new LinkedHashSet<Path>();
    sources.add(lead);
    for (String file :
        FilenameFinder.getFilenameList(directory.toString(), ApplicationInfo.fileExtensions)) {
      sources.add(Path.of(file).normalize());
    }
    for (Path file : files) {
      sources.add(file.toAbsolutePath().normalize());
    }
    return List.copyOf(sources);
  }

  private static String requiredValue(String[] arguments, int index, String description) {
    if (index == arguments.length) {
      throw new IllegalArgumentException("Missing " + description);
    }
    return arguments[index];
  }

  private static Integer decodeSuffix(String text, String prefix) {
    return text.regionMatches(true, 0, prefix, 0, prefix.length())
        ? decode(text.substring(prefix.length()))
        : null;
  }

  private static Integer decode(String text) {
    try {
      return Integer.decode(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String usage() {
    return """
    Usage: mars [options] source.s [more sources.s] [-- program arguments]
      --assemble-only       Assemble without running (a)
      --trace-phases        Show tokenization, assembly, and simulation phases (d, ad, da)
      --project             Assemble sources beside the first file and any additional files (p)
      --delayed-branching    Enable branch delay slots (db)
      --self-modifying-code  Allow writes to text and execution from data (smc)
      --start-at-main        Start at the exported main label (sm)
      --no-pseudo            Accept basic instructions only (np)
      --warnings-as-errors   Fail assembly on warnings (we)
      --memory LAYOUT        Default, CompactDataAtZero, or CompactTextAtZero (mc)
      --dump REGION FORMAT FILE   Export .text, .data, or an inclusive word range (dump)
                             Formats: %s
      --register NAME       Report a register after execution ($NAME or bare mnemonic)
      --memory-range M-N    Report an inclusive word range after execution (M-N)
      hex | dec | ascii     Select the register and memory display format (default: hex)
      --brief               Omit register names and memory row addresses (b)
      --instruction-count   Report the number of completed instructions (ic)
      --messages-to-stderr  Send reports and phase traces to stderr (me)
      nc                    Keep banner output suppressed (the CLI default)
      --max-steps COUNT      Bound execution and return code 3 at the limit
      COUNT                  Positional step limit (zero or negative means unlimited)
      --assembly-error-exit-code CODE     Override the assembly error status (aeCODE)
      --simulation-error-exit-code CODE   Override the simulation error status (seCODE)
      --help                 Show this help
    Exit codes: simulated exit code, 1 for assembly/runtime errors, 2 for invalid arguments,
                3 for a step limit, or 130 for interrupted execution.
    """.formatted(
        String.join(", ", DumpFormatLoader.FORMATS.stream().map(DumpFormat::getCommandDescriptor).toList()));
  }
}
