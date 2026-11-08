package edu.missouristate.mars.plugin.di;

import dagger.Binds;
import dagger.Module;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.plugin.VenusMarsGuiProxy;
import javax.inject.Singleton;

/** Binds the frontend router owned by this desktop component. */
@Module
public abstract class PluginModule {

  @Binds
  @Singleton
  abstract MarsGuiProxy bindGuiProxy(VenusMarsGuiProxy impl);
}
