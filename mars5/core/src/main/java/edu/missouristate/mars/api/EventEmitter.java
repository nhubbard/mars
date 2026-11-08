package edu.missouristate.mars.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Thread-safe, typed event bus. Replaces the deprecated untyped observer pattern. */
public final class EventEmitter<T> {
  private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

  public void subscribe(Consumer<T> listener) {
    listeners.add(listener);
  }

  public void unsubscribe(Consumer<T> listener) {
    listeners.remove(listener);
  }

  public void emit(T event) {
    listeners.forEach(l -> l.accept(event));
  }
}
