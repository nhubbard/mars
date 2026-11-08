package edu.missouristate.mars.plugin.runtime;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.MemoryConfiguration;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.plugin.VenusMarsGuiProxy;
import edu.missouristate.mars.plugin.venus.editors.jeditsyntax.SyntaxUtilities;
import javax.inject.Inject;
import javax.inject.Singleton;

/** One desktop session, owned by its Dagger component and independent of other frontends. */
@Singleton
public final class DesktopSession implements AutoCloseable {
  private final MarsContext context;
  private final Settings settings;
  private final VenusMarsGuiProxy gui;
  private final DesktopSimulation simulator;
  private MIPSprogram program;
  private SyscallEnvironment environment;
  private boolean debug;
  private int exitCode;
  private boolean runSpeedPanelExists;
  public final MemoryState memory;
  public final InstructionSet instructionSet;
  public final SymbolTable symbolTable;
  public final Object memoryAndRegistersLock = new Object();

  @Inject
  public DesktopSession(
      MarsContext context, Settings settings, VenusMarsGuiProxy gui, DesktopSimulation simulator) {
    this.context = context;
    this.settings = settings;
    this.gui = gui;
    this.simulator = simulator;
    memory = context.memory;
    instructionSet = context.instructionSet;
    symbolTable = context.symbolTable;
    environment = context.syscallEnvironment;
    var styles = SyntaxUtilities.getDefaultSyntaxStyles();
    var colors = new String[styles.length];
    var bold = new boolean[styles.length];
    var italic = new boolean[styles.length];
    for (int i = 0; i < styles.length; i++) {
      colors[i] = styles[i].getColorAsHexString();
      bold[i] = styles[i].isBold();
      italic[i] = styles[i].isItalic();
    }
    settings.initializeSyntaxStyleDefaults(colors, bold, italic);
  }

  public MarsContext context() {
    return context;
  }

  public Settings getSettings() {
    return settings;
  }

  public DesktopSimulation simulator() {
    return simulator;
  }

  public InstructionContext instructionContext() {
    return new InstructionContext(context, environment);
  }

  public MIPSprogram createProgram() {
    try (var lease = context.claimStoppedSession()) {
      context.syscallEnvironment.close();
      var result = new MIPSprogram(context);
      result.setSyscallEnvironment(environment);
      return result;
    }
  }

  public MIPSprogram getProgram() {
    return program;
  }

  public void setProgram(MIPSprogram program) {
    this.program = program;
  }

  public boolean debug() {
    return debug;
  }

  public void setDebug(boolean value) {
    debug = value;
  }

  public int exitCode() {
    return exitCode;
  }

  public void setExitCode(int code) {
    exitCode = code;
  }

  public boolean runSpeedPanelExists() {
    return runSpeedPanelExists;
  }

  public void setRunSpeedPanelExists(boolean value) {
    runSpeedPanelExists = value;
  }

  public edu.missouristate.mars.plugin.venus.VenusUI window() {
    return gui.frontend() instanceof edu.missouristate.mars.plugin.venus.VenusUI window
        ? window
        : null;
  }

  public MarsGuiProxy getGui() {
    return gui;
  }

  public void setGui(MarsGuiProxy delegate) {
    gui.attach(delegate);
  }

  public SyscallEnvironment syscallEnvironment() {
    return environment;
  }

  public void setSyscallEnvironment(SyscallEnvironment environment) {
    this.environment = environment;
    if (program != null) program.setSyscallEnvironment(environment);
  }

  public boolean setMemoryConfiguration(MemoryConfiguration configuration) {
    if (configuration == null
        || memory
            .getConfiguration()
            .configurationIdentifier()
            .equals(configuration.configurationIdentifier())) return false;
    context.setMemoryConfiguration(configuration.configurationIdentifier());
    return true;
  }

  public void resetRegisters() {
    context.registerFile.resetRegisters();
    initializeProgramCounter(settings.getBooleanSetting(Settings.START_AT_MAIN));
  }

  public void initializeProgramCounter(boolean startAtMain) {
    int address = symbolTable.getAddress(SymbolTable.getStartLabel());
    context.registerFile.initializeProgramCounter(
        startAtMain
                && address != SymbolTable.NOT_FOUND
                && (memory.inTextSegment(address) || memory.inKernelTextSegment(address))
            ? address
            : context.registerFile.getInitialProgramCounter());
  }

  @Override
  public void close() {
    context.close();
  }
}
