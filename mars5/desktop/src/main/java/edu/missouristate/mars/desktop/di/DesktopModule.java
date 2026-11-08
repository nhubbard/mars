package edu.missouristate.mars.desktop.di;

import dagger.Binds;
import dagger.Module;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.desktop.WindowGuiProxy;
import javax.inject.Singleton;

/** Binds the frontend router owned by this desktop component. */
@Module
public abstract class DesktopModule {

  @Binds
  @Singleton
  abstract MarsGuiProxy bindGuiProxy(WindowGuiProxy impl);
}
