# Scripted timing in assignment checks

`provideTiming(readings, maxEvents = 65_536)` enables time service 30 and sleep
service 32 for one grading case. Without it, those services remain disabled. Each
time request consumes the next copied 64-bit value, including its original bit
pattern. This uses the normal syscall implementation, which splits the result into
the low and high 32-bit return halves. Sleep requests are recorded without waiting
on the host or advancing the scripted readings.

```kotlin
val clock = provideTiming(listOf(1000L, 1010L, 1040L))
// Execute submitted deadline/wait code, then inspect ordered TIME and SLEEP events.
expectCondition(
    clock.snapshot().filter { it.operation == TimingCheck.Operation.SLEEP }
        .map { it.milliseconds } == listOf(30L),
    "Wait only for the time remaining before the deadline",
)
```

Import `TimingCheck` in the script when naming its nested types. `snapshot()` is a
detached immutable event list with `operation` and `milliseconds` fields. A zero
sleep is recorded. Negative sleeps, exhausted time readings and event-history
overflow fail explicitly; histories are never silently truncated. Configure once
per case. Invalid history capacity does not enable timing. An empty reading list
supports sleep-only checks but fails if the submission requests time.

Script explicit post-sleep times to model exact wakeup, early return, oversleep or
a backward wall-clock change. The fixture does not invent a monotonic clock or
assert that a real host sleep or IDE repaint has that timing. Program correctness
should distinguish logical deadlines, requested waiting and actual presentation.

`runUntilSyscall(number)` pauses before a matching syscall, using the current
service register and the complete instruction address. It also works before a
service is enabled, without enabling it. Inspect program state at that boundary,
then `step()` to execute the service before searching for its next occurrence.
This allows frame checks at timing boundaries without requiring private assembly
labels or reading state while the simulator is running. A missing service fails
when execution stops for another reason, such as normal termination or its
instruction limit. The predicate does not publish memory accesses.
