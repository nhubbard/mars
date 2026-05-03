package edu.missouristate.mars.di;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.core.SimulationSettings;
import javax.inject.Singleton;

/**
 * Dagger module for mars-core. Provides the headless-safe simulation settings and binds the
 * {@link MarsSettings} interface to the concrete {@link SimulationSettings} implementation.
 *
 * <p>{@link edu.missouristate.mars.api.MarsGuiProxy} is intentionally absent — callers supply it
 * via {@link MarsComponent.Builder#guiProxy} so the same component graph works for the CLI, the
 * IntelliJ plugin, and tests without any conditional logic here.
 */
@Module
public abstract class CoreModule {

  @Provides
  @Singleton
  static SimulationSettings provideSimulationSettings() {
    return new SimulationSettings();
  }

  @Binds
  @Singleton
  abstract MarsSettings bindMarsSettings(SimulationSettings settings);
}
