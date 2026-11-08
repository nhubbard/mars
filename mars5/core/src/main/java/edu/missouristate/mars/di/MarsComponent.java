package edu.missouristate.mars.di;

import dagger.BindsInstance;
import dagger.Component;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsAssembler;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.MarsMemory;
import edu.missouristate.mars.api.MarsRegisterFile;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.api.MarsSimulator;
import edu.missouristate.mars.core.MarsContext;
import javax.inject.Singleton;

/**
 * Root Dagger component for the MARS core. Provides a fully-wired {@link MarsContext}.
 *
 * <p>Usage (CLI):
 *
 * <pre>{@code
 * MarsComponent component = DaggerMarsComponent.builder()
 *     .guiProxy(new HeadlessGuiProxy())
 *     .build();
 * MarsContext ctx = component.context();
 * }</pre>
 *
 * <p>Each frontend owns its component and supplies its own I/O routing. The standalone desktop uses
 * a window-scoped component; the IntelliJ integration owns a separate core component in each
 * project service. Neither frontend retrieves a process-wide simulation context.
 *
 * <p>Supply {@code .coreModule(new CoreModule(existingSettings))} to share a frontend's
 * preferences. Omit the module to create a separate settings owner for a new session.
 *
 * <p>Tests can pass a mock or no-op {@link MarsGuiProxy} to isolate the core from any UI.
 */
@Singleton
@Component(modules = CoreModule.class)
public interface MarsComponent {

  /** Returns the fully-initialized simulation context for this component. */
  MarsContext context();

  MarsAssembler assembler();

  MarsSimulator simulator();

  /** Persistent settings owner shared with the context's simulation view. */
  Settings settings();

  /** Interface view of the same simulation settings used by the context. */
  MarsSettings simulationSettings();

  /** Interface view of this simulation's integer registers. */
  MarsRegisterFile registerFile();

  /** Public API view of this simulation's memory. */
  MarsMemory memory();

  @Component.Builder
  interface Builder {
    /** Optional: reuse an existing settings owner instead of loading a new one. */
    Builder coreModule(CoreModule module);

    /** Supplies the GUI/I/O proxy for this session. Must be called before {@link #build()}. */
    @BindsInstance
    Builder guiProxy(MarsGuiProxy proxy);

    MarsComponent build();
  }
}
