package edu.missouristate.mars.smoke;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.api.StopEvent;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.mips.instructions.InstructionDefaults;
import edu.missouristate.mars.simulator.Exceptions;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InstructionLab {
    private static final int STEP_LIMIT = 96;
    private static final Set<String> MEMORY = Set.of(
        "lb", "lbu", "lh", "lhu", "lw", "ll", "lwl", "lwr", "sb", "sh", "sw", "sc", "swl", "swr",
        "lwc1", "ldc1", "swc1", "sdc1", "l.s", "l.d", "s.s", "s.d", "ulw", "ulh", "ulhu", "ld",
        "usw", "ush", "sd");
    private static final Set<String> DOUBLE_MEMORY = Set.of("ldc1", "sdc1", "l.d", "s.d");
    private static final Set<String> TAKEN_TRAPS = Set.of("tne", "tnei", "tge", "tgeu", "tgei", "tltiu");
    private static final MarsGuiProxy HEADLESS = new MarsGuiProxy() {
        public boolean isGuiMode() { return false; }
        public void postMessage(String text) { System.err.print(text); }
        public void postRunMessage(String text) { System.out.print(text); }
        public String readInput(int length) { throw new UnsupportedOperationException("Use the console input stream"); }
        public String readInputWithPrompt(String prompt) { throw new UnsupportedOperationException("Use the console input stream"); }
        public void clearRunConsole() {}
    };

    private InstructionLab() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: InstructionLab catalog <output-directory> | run <file.asm>");
        }
        var path = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "catalog" -> exerciseCatalog(path);
            case "run" -> runWorkbench(path, System.in, System.out, false);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void exerciseCatalog(Path output) throws Exception {
        var sources = Files.createDirectories(output.resolve("sources"));
        try (var generated = Files.list(sources)) {
            for (var path : generated.filter(path -> path.getFileName().toString().matches("\\d{3,}-[a-z0-9.]+\\.asm")).toList()) {
                Files.delete(path);
            }
        }
        var catalog = InstructionDefaults.get().getInstructionList();
        if (catalog.isEmpty()) {
            throw new IllegalStateException("MARS returned an empty instruction catalog");
        }
        var lines = new ArrayList<String>();
        lines.add("file\tkind\tcatalog form\tassembled form\tmemory layout\tresult\treached probe words\temitted probe words");
        var errors = new ArrayList<String>();
        int number = 0;
        int basics = 0;
        for (var instruction : catalog) {
            boolean basic = instruction instanceof BasicInstruction;
            if (basic) {
                basics++;
            }
            var probe = Probe.forInstruction(instruction);
            var name = "%03d-%s.asm".formatted(++number, instruction.getName());
            var source = sources.resolve(name);
            Files.writeString(source, probe.source(instruction.getExampleFormat()));
            try {
                var result = execute(source, instruction, probe);
                lines.add(String.join("\t", name, basic ? "basic" : "pseudo", instruction.getExampleFormat(),
                    probe.assembly(), probe.layout(), result.outcome(), result.executed(), result.emitted()));
            } catch (Exception failure) {
                errors.add(name + " (" + probe.assembly() + "): " + failure.getMessage());
            }
        }
        Files.write(output.resolve("coverage.tsv"), lines);
        Files.write(output.resolve("failures.txt"), errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.size() + " of " + number + " probes failed. See " + output.resolve("failures.txt"));
        }
        System.out.printf("Executed %d basic and %d pseudo-instruction catalog forms.%n", basics, number - basics);
        System.out.println("Sources and coverage report: " + output);
    }

    private static Result execute(Path path, Instruction instruction, Probe probe) throws Exception {
        try (var session = session(probe.layout())) {
            MIPSprogram program;
            try {
                program = session.assemble(List.of(path));
            } catch (ProcessingException failure) {
                throw new IllegalStateException(failure.errors().generateErrorAndWarningReport(), failure);
            }
            var symbols = program.getLocalSymbolTable();
            int start = symbols.getAddress("probe");
            int end = symbols.getAddress("probe_end");
            var words = program.getMachineList().stream()
                .filter(statement -> statement.getAddress() >= start && statement.getAddress() < end)
                .toList();
            boolean matches = instruction instanceof BasicInstruction
                ? words.size() == 1 && words.getFirst().getInstruction().getClass() == instruction.getClass()
                : !words.isEmpty() && words.stream()
                    .allMatch(word -> program.sourceMap().pseudoAddresses().contains(word.getAddress()));
            if (!matches) {
                throw new IllegalStateException("The emitted probe did not select the catalog instruction");
            }
            var seen = new ArrayList<String>();
            for (int step = 0; step < STEP_LIMIT; step++) {
                int pc = session.registerFile.getPC();
                MIPSprogram.ExecutionStep event;
                try {
                    event = program.executeSessionInstruction();
                } catch (ProcessingException failure) {
                    int cause = (session.coprocessor0.getValue(Coprocessor0State.CAUSE) >>> 2) & 31;
                    if (pc < start || pc >= end || probe.expectedException() == 0 || cause != probe.expectedException()) {
                        throw new IllegalStateException(failure.errors().generateErrorAndWarningReport(), failure);
                    }
                    int faultingWord = words.stream().filter(word -> word.getAddress() == pc)
                        .findFirst().orElseThrow().getBinaryStatement();
                    seen.add(hex(faultingWord));
                    return result("exception " + cause, seen, words);
                }
                if (event.executed() && pc >= start && pc < end) {
                    seen.add(hex(event.instruction()));
                }
                if (event.stopReason() != null) {
                    if (event.stopReason() != StopEvent.Reason.NORMAL_EXIT || program.getExitCode() != 0 ||
                        seen.isEmpty() || probe.expectedException() != 0) {
                        throw new IllegalStateException("Unexpected stop " + event.stopReason() + " with " + seen.size() + " probe steps");
                    }
                    return result("exit 0", seen, words);
                }
            }
            throw new IllegalStateException("Probe exceeded " + STEP_LIMIT + " instructions");
        }
    }

    private static Result result(String outcome, List<String> seen, List<ProgramStatement> words) {
        return new Result(outcome, String.join(" ", seen),
            String.join(" ", words.stream().map(word -> hex(word.getBinaryStatement())).toList()));
    }

    private static String hex(int word) {
        return String.format(Locale.ROOT, "%08x", word);
    }

    static void runWorkbench(Path path, InputStream input, PrintStream output, boolean delayedBranches) throws Exception {
        try (var session = session("Default")) {
            session.settings.setDelayedBranchingEnabled(delayedBranches);
            try (var console = new SessionSyscallEnvironment(session.memory, input, output, output)) {
                var program = session.assemble(List.of(path));
                program.setSyscallEnvironment(console);
                if (!program.simulate(1_000_000) || program.getExitCode() != 0) {
                    throw new IllegalStateException("Workbench did not exit successfully");
                }
            } catch (ProcessingException failure) {
                throw new IllegalStateException(failure.errors().generateErrorAndWarningReport(), failure);
            }
        }
    }

    private static MarsContext session(String layout) {
        var settings = new Settings();
        settings.getSimulationSettings().setMemoryConfiguration(layout)
            .setDelayedBranchingEnabled(false).setBareMachineEnabled(false)
            .setStartAtMainEnabled(false).setExtendedAssemblerEnabled(true)
            .setSelfModifyingCodeEnabled(false).setWarningsAreErrors(false);
        return DaggerMarsComponent.builder().coreModule(new CoreModule(settings, 32)).guiProxy(HEADLESS).build().context();
    }

    private record Result(String outcome, String executed, String emitted) {}

    private record Probe(String assembly, String layout, String seed, boolean branch, int expectedException) {
        static Probe forInstruction(Instruction instruction) {
            var name = instruction.getName();
            var assembly = instruction.getExampleFormat().replace("label+100000", "label + 100000");
            boolean memory = MEMORY.contains(name);
            boolean branch = name.startsWith("b") && !name.equals("break");
            var layout = memory && assembly.endsWith(",100") ? "CompactDataAtZero" : "Default";
            if (memory && assembly.endsWith(",100000")) {
                assembly = assembly.replace(",100000", ",0x10010000");
            }
            if (DOUBLE_MEMORY.contains(name) && assembly.endsWith(",-100")) {
                assembly = assembly.replace(",-100", ",-104");
            }
            String seed = "";
            if (memory && assembly.endsWith("($t2)")) {
                seed = assembly.contains("label") ? "move $t2,$zero" :
                    assembly.endsWith("-100($t2)") ? "la $t2,buffer\naddiu $t2,$t2,100" : "la $t2,buffer";
            }
            if (name.equals("jr") || name.equals("jalr")) {
                seed = "la $t1,target\nla $t2,target";
            }
            if (name.equals("eret")) {
                seed = "la $t0,target\nmtc0 $t0,$14";
            }
            if (name.startsWith("movz")) {
                seed = "move $t3,$zero";
            }
            if (name.startsWith("movt")) {
                seed = "c.eq.s $f1,$f1\nc.eq.s 1,$f1,$f1";
            }
            boolean overflowProbe = name.equals("mulou") && assembly.endsWith(",-100");
            int exception = name.equals("break") || overflowProbe ? Exceptions.BREAKPOINT_EXCEPTION :
                TAKEN_TRAPS.contains(name) ? Exceptions.TRAP_EXCEPTION : 0;
            return new Probe(assembly, layout, seed, branch, exception);
        }

        String source(String original) {
            return """
                # Catalog form: %s
                # Memory configuration: %s. Delayed branching: off.
                .data
                .align 3
                buffer: .space 128
                %s
                single_value: .float 2.5
                .align 3
                double_value: .double 4.0
                second_double: .double 2.0
                third_double: .double 3.0
                .text
                .globl main
                main:
                    li $t1,12
                    li $t2,3
                    li $t3,2
                    li $v0,10
                    lwc1 $f1,single_value
                    lwc1 $f3,single_value
                    ldc1 $f2,double_value
                    ldc1 $f4,second_double
                    ldc1 $f6,third_double
                %s
                probe:
                    %s
                probe_end:
                    nop
                %s
                target:
                    li $v0,10
                    syscall
                """.formatted(original, layout, branch ? "" : "label: .word 0x01020304, 0x05060708",
                    seed, assembly, branch ? "label:" : "");
        }
    }
}
