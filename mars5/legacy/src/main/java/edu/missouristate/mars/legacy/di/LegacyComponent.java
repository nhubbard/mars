package edu.missouristate.mars.legacy.di;

import dagger.Component;
import edu.missouristate.mars.legacy.LegacyServices;
import javax.inject.Singleton;

/** Shares the existing compatibility session; independent simulations still use MarsComponent. */
@Singleton
@Component(modules = LegacyBindings.class)
public interface LegacyComponent {
  LegacyServices services();
}
