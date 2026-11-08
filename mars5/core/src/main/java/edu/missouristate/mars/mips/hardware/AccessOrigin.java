package edu.missouristate.mars.mips.hardware;

/** Immutable provenance captured with each memory/register notice, independent of thread names. */
public enum AccessOrigin {
  APPLICATION,
  EXECUTION;

  private static final ScopedValue<AccessOrigin> CURRENT = ScopedValue.newInstance();

  public static AccessOrigin current() {
    return CURRENT.orElse(APPLICATION);
  }

  /** Binds provenance for one lexical execution scope; exceptions restore the caller's origin. */
  public static <T, X extends Throwable> T duringExecution(ScopedValue.CallableOp<T, X> operation)
      throws X {
    if (CURRENT.isBound()) return operation.call();
    return ScopedValue.where(CURRENT, EXECUTION).call(operation);
  }
}
