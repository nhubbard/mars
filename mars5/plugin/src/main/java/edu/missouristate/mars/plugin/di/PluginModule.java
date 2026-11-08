package edu.missouristate.mars.plugin.di;


import dagger.Binds;
import dagger.Module;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.plugin.VenusMarsGuiProxy;
import javax.inject.Singleton;

/**
 * Dagger module for the IntelliJ plugin. Binds {@link VenusMarsGuiProxy} as the {@link
 * MarsGuiProxy} implementation so the plugin's {@link PluginComponent} can provide a complete
 * {@link edu.missouristate.mars.core.MarsContext} without any {@code @BindsInstance} call.
 *
 * <p>{@link VenusMarsGuiProxy} is also declared as an IntelliJ application service in {@code
 * plugin.xml} so the IntelliJ service registry can retrieve it independently of Dagger (e.g. from
 * legacy Venus code that still calls {@code LegacyApplication.services().getGui()}).
 */
@Module
public abstract class PluginModule {

  @Binds
  @Singleton
  abstract MarsGuiProxy bindGuiProxy(VenusMarsGuiProxy impl);
}
