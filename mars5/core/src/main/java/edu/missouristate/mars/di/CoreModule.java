package edu.missouristate.mars.di;

import dagger.Module;
import dagger.Provides;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsRegisterFile;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.simulator.DelayedBranchState;
import java.util.Objects;
import javax.inject.Singleton;

/**
 * Dagger module for mars-core. Provides the headless-safe simulation settings and binds the {@link
 * MarsSettings} interface to the concrete {@link SimulationSettings} implementation.
 *
 * <p>{@link edu.missouristate.mars.api.MarsGuiProxy} is intentionally absent — callers supply it
 * via {@link MarsComponent.Builder#guiProxy} so the same component graph works for the CLI, the
 * IntelliJ plugin, and tests without any conditional logic here.
 */
@Module
public final class CoreModule {
  private final Settings settings;

  /** Creates a settings owner for this component, loading the existing user preferences. */
  public CoreModule() {
    this(new Settings());
  }

  /** Reuses a settings owner, e.g. Globals.getSettings() during legacy migration. */
  public CoreModule(Settings settings) {
    this.settings = Objects.requireNonNull(settings);
  }

  @Provides
  @Singleton
  Settings provideSettings() {
    return settings;
  }

  @Provides
  @Singleton
  static SimulationSettings provideSimulationSettings(Settings settings) {
    return settings.getSimulationSettings();
  }

  @Provides
  @Singleton
  static MarsSettings provideMarsSettings(SimulationSettings settings) {
    return settings;
  }

  @Provides
  @Singleton
  static IntegerRegisterFile provideRegisterFile(SimulationSettings settings) {
    var configuration = MemoryConfigurations.createConfiguration(settings.getMemoryConfiguration());
    return new IntegerRegisterFile(
        configuration.getGlobalPointer(),
        configuration.getStackPointer(),
        configuration.getTextBaseAddress());
  }

  @Provides
  @Singleton
  static MarsRegisterFile provideMarsRegisterFile(IntegerRegisterFile registers) {
    return registers;
  }

  @Provides
  @Singleton
  static Coprocessor0State provideCoprocessor0() {
    return new Coprocessor0State();
  }

  @Provides
  @Singleton
  static Coprocessor1State provideCoprocessor1() {
    return new Coprocessor1State();
  }

  @Provides
  @Singleton
  static DelayedBranchState provideDelayedBranch() {
    return new DelayedBranchState();
  }
}
