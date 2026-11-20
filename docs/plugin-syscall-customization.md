# Launch-local syscall environment customization

A dependent IntelliJ plugin can decorate the syscall environment of each in-process
MARS Run or Debug launch through `MarsSyscallEnvironmentCustomizer`. Declare a dependency
on `edu.missouristate.mars` and register an implementation of that public interface:

```xml
<extensions defaultExtensionNs="edu.missouristate.mars">
  <syscallEnvironmentCustomizer implementation="example.MyEnvironmentCustomizer"/>
</extensions>
```

The implementation receives the project, entry source path, current `SyscallEnvironment`
and a launch-local `Disposable`. Return the supplied environment unchanged when the
launch does not belong to the integration. Kotlin interface delegation can preserve
unmodified operations while replacing only the services needed by that integration.
This callback provides no simulator memory, register or debugger mutation interface.

MARS invokes customizers in platform extension order on its execution worker before
starting the first guest instruction. Each customizer receives its predecessor's result.
The environment's operations subsequently run on the simulator thread. Mutable state
must belong to that invocation; it must not be shared across simultaneous sessions or
stored in thread-local variables. Implementations must not block waiting for IDE input.

Register resources with the supplied lifetime before any setup operation that can fail.
MARS disposes them after simulation stops and before closing its default session I/O.
This also happens if customization throws, execution fails, or the user cancels the
launch. A customizer must not close its supplied environment or assume ownership of
another customizer's resources. Setup exceptions abort the launch rather than silently
running the program with different I/O.

MARS retains an outer IDE environment for cancellable sleeps, synchronous MIDI, dialogs
and console clearing. A decorator cannot replace those IDE lifecycle operations. With
no customizers, the default environment passes through by identity. Existing console,
working-directory and descriptor behavior remains the default.

The public extension point uses IntelliJ 2026.3's supported `ExtensionPointName`
constructor and ordered `extensionList`. The platform's generic run-configuration
extension callbacks have no contract for an in-process simulator's syscall environment;
this extension is limited to that MARS-specific gap. Integrations and their fixtures
remain in dependent plugins, outside the MARS distribution.
