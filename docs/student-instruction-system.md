# MARS student instructional system

## Scope and publication boundary

The author authorized a new implementation goal on 20 November 2026: a main
learning book, a written lab book supplementing interactive activities, and the
student instructional system in the main MARS plugin. This supersedes the earlier
proposal to keep the entire tutor outside MARS. Screenshot and publication-authoring
automation remain in the separate plugin depending on MARS. Private solutions and
instructor-only checks remain in the separate private book repository.

Begin with a complete first-program unit across the three products, then review
that teaching approach before extending it to all 35 project packages. The original
manuscript, captures and distributions remain recoverable at the baseline commits
recorded in the private instructor repository. There is no page target or ceiling.

## Platform inspection and justified MARS state

The read-only IntelliJ 2026.3 checkout was inspected before choosing an integration.
The `plugins/ide-features-trainer` module provides public `LearningCourse`,
`LearningCourseBase`, `IftModule`, `KLesson`, `LessonContext` and `TaskContext` APIs.
The descriptor exposes `training.ift.learning.course` and
`training.ift.learning.commonCourse`. Its task DSL supports action triggers, state
checks and UI highlighting. Reuse those facilities for the native teaching host;
do not introduce a second general-purpose lesson UI framework.

The concrete persistence gap is in
`plugins/ide-features-trainer/src/training/learn/lesson/LessonStateBase.kt`:
its internal state stores a map from lesson ID to passed/not-passed status.
It does not represent content editions, individual quiz attempts, revealed hints,
source-dependent checks or outstanding checks invalidated by edits. Do not access
that internal state. MARS owns the additional simulator-specific evidence through
public IntelliJ `SerializablePersistentStateComponent` and project service APIs.
Their implementation and current contracts were inspected in
`platform/projectModel-api/src/com/intellij/openapi/components/SerializablePersistentStateComponent.kt`.

## First implemented increment

`MarsLearningProgress` is a project service using the IDE workspace state file,
so student attempts do not become shared project configuration. Histories are
scoped by course, lesson, version and exact content SHA-256. Opening a different
content edition creates a distinct history rather than silently migrating work.

The source revision is an opaque fingerprint supplied by the future lesson
controller. That controller must include all checked source and execution options,
observe edits, and update the revision before accepting results. The progress
service does not claim to observe editor documents itself.

A changed source revision clears successful execution checks and outstanding
requests while retaining predictions, quiz attempts and revealed hints. Returning
to the original source does not silently restore old successful checks. A retry
clears that step's earlier success until the new result arrives. Each result needs
the exact transient request object currently owned by this project service. Late,
repeated, replaced, reset, restored and cross-service results are rejected.
Independent steps may have independent checks in flight.

Progress updates use the platform's atomic persistent-state update mechanism.
Service mutations and state loading are serialized; outstanding requests are never
persisted and state restoration cancels them. Snapshots copy mutable serialization
beans so the caller cannot mutate stored quiz attempts accidentally.

Jupiter tests cover actual project service lookup, platform XML serialization,
source changes including editing back to the same source, concurrent attempts,
retries, independent steps and content editions, resets and stale results.
This increment is a progress foundation, not a completed student-facing lesson.

## Native prediction lesson and project preservation

The main plugin now registers the MARS language and course with Features Trainer.
The first prediction segment presents two questions, records wrong attempts,
provides misconception-specific feedback and accepts a revised prediction. The
correct explanations remain in the native lesson text. The public task DSL has
text, action and state-check facilities but no multiple-choice feedback widget;
a standard IntelliJ `DialogWrapper` supplies that small MARS assessment surface.
It is not a separate lesson framework. Formative client feedback is inspectable;
private instructor grading and book quiz keys remain separate.

Use the native Learn IDE Features window and select MARS. The course uses
`LessonType.USER_PROJECT` and `openFileAtStart=false`. The native dedicated-project
installer deletes/replaces its demo directory when its training-version marker
changes (`ProjectUtils.importOrOpenProject`), before a language support hook can
preserve student files. That concrete lifecycle gap makes it unsuitable as the
owner of editable MARS student work.

`MarsLearningWorkspace` creates only missing files under the current project's
`mars-labs/console/`. It never overwrites an existing source. Occupied directory
paths, source directories and symlinks are rejected without changing their targets.
The native lesson opens its prepared source with the supported nonblocking
`requestOpenFile` API and waits for its future. Restarts retain edits and prediction
history. The host's ID-only passed recap is bypassed with its public `alwaysRestart`
contract so the current content edition's evidence is evaluated again.

The separate authoring plugin verified this segment in the real IDE after the
required clean, cleanSandbox and runIde tasks. It exercised both wrong-answer
feedback dialogs, accepted corrected answers, observed native lesson progression,
and verified that a source comment survived a lesson restart. Five light-theme
captures were inspected. Their full-window views retain an IDE plugin-update
notification; they are verification evidence, not finished publication figures.

The paired packaged-plugin API check reports both plugins compatible on
IU-263.7307.200. The full MARS plugin suite passes 926 tests; the learning package
has 31 passing Jupiter tests with all 207 source lines and 68 branches covered.
The separate authoring driver passes 88 tests. Exact artifacts, logs and receipts
are retained in the private instructor verification directory
`verification/restructuring/native-predictions-02/`. These results cover the
prediction segment only; they do not establish the complete first-unit behavior.

## Source-bound Run observation

The second native lesson observes an ordinary MARS Run. It uses the public
`timerCheck` task facility because worker completion need not produce an editor
action. The host's ordinary `stateCheck` is driven by UI/document activity and
cannot alone detect a later asynchronous result. The first graphical attempt
exposed that distinction; fixtures now assert the timed-check registration. The implementation uses
`ExecutionListener.processStarting`, whose public contract fires before the process
handler's `startNotify`, and the public `ProcessListener` output and termination
callbacks. The current platform implementations were inspected in
`platform/execution/src/com/intellij/execution/ExecutionListener.java` and
`platform/util/src/com/intellij/execution/process/ProcessHandler.java`.
No instruction, register or console input is changed by the student observer.

The checkpoint is intentionally the worked single-file observation, separate from
independent-program grading. It requires the selected example entry, the documented
default execution options, normal exit and the exact expected output transcript.
The process handler supplies its actual source set, options and retained assembly
capture through read-only properties. A different source set, additional include,
custom exception handler, different executor or different options cannot satisfy
this checkpoint. Source text in the executed capture must match the document that
was observed when that process started. Transcript retention is bounded.

A document stamp rejects edits during a Run, including editing away and back.
The native bulk-aware document listener invalidates progress before an edit;
synchronous VFS notifications invalidate it before relevant rename, delete or disk
refresh events. Ordinary document saves are not new edits. Completion is accepted
under a read action with the existing progress service's per-attempt capability,
so a superseded or reset attempt cannot restore progress. Listeners belong to the
native lesson lifetime, and observation work uses the project service's injected
coroutine scope. Closing the lesson cancels its pending capability and removes its
listeners while leaving the student's process alone.

Real-process Jupiter fixtures cover actual input/output, the native starting event,
wrong results, source edits, save/rename behavior, additional includes, custom
exception handlers, ignored executions and invalid document bindings. Both lesson
bodies are also exercised through the actual native DSL registrar. The previous
prediction-only GUI evidence does not establish graphical Run integration. The
separate authoring driver now verifies both segments in a fresh actual IDE after
`clean cleanSandbox runIde`. It observes two prediction-pass callbacks (including
restart), preserved student edits, the exact Run transcript and normal exit, then
the native Run-pass callback without a subsequent editor action. Seven actual
light-theme captures were inspected. Full-window views retain an update notification;
the completed Run view also needs scroll/layout cleanup for publication.

The corrected implementation passes 937 plugin tests, including 42 learning tests,
and the unchanged coverage gate: all 298 learning source lines and 124 branches.
The separate authoring plugin passes 88 tests. Paired package verification reports
both plugins compatible on IU-263.7307.200 (85 and 20 experimental public API uses).
All eleven installed JARs match the retained tested package bytes. Exact packages,
XML test archives and GUI receipts are retained privately under
`verification/restructuring/native-run-02/`. The preceding `native-run-01/` batch
retains the asynchronous-check failure and must not be treated as a successful
lesson verification. Debug and the complete first-unit acceptance remain open.

## Source-bound Debug observation

The third native lesson follows one Debug session through a pause before
`addu $t0,$v0,$v0`, a pause after it produces 40, and a pause after
`addiu $t0,$t0,1` produces 41. It reads the evaluator's existing stopped-register
snapshot and a copied instruction word; no live register or memory evaluation is
required. The checker compares the basic instruction encodings, PC boundaries,
fresh suspension numbers and the input/result values. It does not parse source or
require fixed line numbers. A valid local source position is still required.

Both Run and Debug now share `MarsLearningSource` for document/VFS invalidation,
actual assembled-source comparison, execution options and per-attempt capabilities.
The progress service exposes a read-only capability test for intermediate pauses.
A new matching Debug attempt supersedes the previous one. Old pauses and cleanup
cannot advance or clear the new attempt. Native session listeners and disposables
own observation cleanup. Completed Run observations now release their per-process
cleanup registration rather than retaining old handlers until the lesson closes.

The public debugger session-listener contracts were inspected in the reference
checkout. The native paused notification installs its suspend context before
calling listeners; the observer binds only to MARS's concrete debug process and
therefore reads `MarsSuspendContext`. Foreign debugger sessions are ignored. Native
`timerCheck` consumes the asynchronous observations. New fixtures exercise actual
source stepping, simultaneous Debug sessions, restored source edits, extra includes,
a source-less instruction pause, foreign sessions and stopped-snapshot stability.
The fresh graphical batch `native-debug-03` verifies all three implemented lessons
in IU-263.7307.200 after the actual `clean cleanSandbox runIde` tasks. The separate
authoring plugin observes two prediction-pass callbacks, retained student edits,
normal Run exit with result 41, and the native Debug-pass callback. Actual debugger
rows show input 20 and results 0, 40 and 41 at the three pauses. The new Debug
session starts with result zero even after the preceding Run computed 41.

The first Debug capture attempt stopped at an incorrect register-group caption in
the authoring driver; it is retained as partial evidence, not a successful Debug
check. The corrected driver checks the actual packaged `Integer registers` caption
and selects the owned native register tab before reading its rows. The intervening
`native-debug-02` batch contains prelaunch validation only. The final batch retains
exact tested packages, 945 passing plugin tests (50 learning tests), 90 passing
authoring tests, and paired compatible API reports (85 and 20 experimental public
API uses). The unchanged coverage gate passes; learning code covers all 398 source
lines and 202 branches. All eleven installed JARs match the retained package bytes.

All ten final light-theme screenshots were inspected. They remain raw verification
artifacts: update notifications, clipped/scrolled lesson text and transient analysis
indicators need cleanup before publication. Native register values are hexadecimal;
20, 40 and 41 display as 0x14, 0x28 and 0x29. The driver supplies standard process
input directly; it does not verify keyboard entry and echoed input in the console.
No new annotated publication figure or human learner review is claimed.

## Following output preparation

The fourth native lesson asks the learner to distinguish the retained answer in
`$t0`, the output argument in `$a0` and the service selector in `$v0`. Its prediction
records wrong answers and explanations before allowing a revised choice. It then
observes a fresh Debug session before `move $a0,$t0`, after that copy, and after
`li $v0,1` at the integer-output `syscall`. The expected stopped states are:

| Boundary | `$v0` | `$a0` | `$t0` |
| --- | --- | --- | --- |
| Before copying the answer | 4 | Answer-prefix address | 41 |
| After copying it | 4 | 41 | 41 |
| Before integer output | 1 | 41 | 41 |

The first boundary identifies the actual next instruction and retained answer;
it does not assert a fixed string address. The next two check both the argument
and selector. The observer reuses the source-bound Debug lifecycle with a separate
content identity and numeric trace contract. In the pinned MIPS64 worked example,
`move` expands to `or $a0,$t0,$zero`; the first matching small `li` template expands
to `addiu $v0,$zero,1`. Actual native stepping fixtures verify these encodings and
copied registers. No simulator semantics are changed. This guided worked trace
requires the demonstrated boundaries; free-writing acceptance remains behavioral
and must allow equivalent implementations.

The first graphical output batch observed native completion and correct register
rows, but its log exposed a write-unsafe VFS refresh during lesson restart.
`native-output-02` retains that defect, not final acceptance. File opening now runs
in the project service coroutine scope, refreshes through `VirtualFileManager` on
IO and uses the public suspending editor API. The native host's write-unsafe caller
context is reproduced in a Jupiter regression test. The first coroutine correction
also exposed the host's requirement that a task future complete on EDT; that
partial attempt is retained as `native-output-03`. The opening coroutine now uses
`Dispatchers.EDT`, with the refresh isolated on IO, and the regression test asserts
the completion callback's thread. Project disposal owns the opening operation.

The corrected full check passes 948 plugin tests, including 53 learning tests,
and the unchanged coverage gates. Learning code covers all 456 source lines and
246 branches. The separate authoring plugin passes 90 tests; both exact packages
pass paired API verification. The final fresh `native-output-04` IDE batch observes
native completion of prediction/restart, Run, arithmetic Debug and output
preparation. Its log has no SEVERE entries, and all eleven installed JARs match the
retained tested ZIP members. All fourteen light-theme captures were inspected.
They remain raw verification views requiring notification removal, scroll/layout
cleanup, annotations and publication accessibility verification. Input still uses
the public process stream, so console typing and echo require separate checking.
This completes four segments; diagnostic repair and independent writing remain open.

## Diagnosing and repairing the output argument

Two further native lessons separate the faulty counterexample from the student's
repair. `wrong-output.asm` copies `$v0` into `$a0` after prefix output. A prediction
uses the last writer to explain why it prints 4 for input 20. Actual Debug pauses
then establish retained result 41, copied argument 4 and integer selector 1. A
second question asks which instruction introduced the incorrect state and diagnoses
misplaced arithmetic or service changes.

`repair-output.asm` starts from that same fault in a separate file. The ordinary
Run checker uses its own content identity and exact source capture. It accepts a
correct transcript independently of instruction spelling; fixtures reject the
fault and accept both `move` and equivalent `or` repairs. This is one observed Run
for input 20, not independent multi-case acceptance or authoritative grading.

The workspace creates only missing exercise files and refuses occupied/symlinked
paths. Edits survive initialization; the worked example, counterexample and repair
are independent. The native USER_PROJECT lesson runtime follows the selected editor
when opening each file. Public course/task and debugger contracts were inspected
in the pinned reference checkout. Focused Jupiter checks pass 57 learning tests,
covering all 535 learning lines and 258 branches. Full and graphical checks for
this edition are still pending.

## Remaining first-unit integration

The unit must complete the connection between explanation, prediction, actual Run/Debug observations,
source-bound checking, diagnostic feedback, graduated hints and an independent
variation. Provide complete public source and checks; do not execute arbitrary
Kotlin downloaded as lesson content. Lesson imports require a versioned content
contract and supported declarative checks or another explicitly trusted execution
path. Public client-side checks and answers are inspectable and cannot substitute
for private instructor grading.

Revised main-book and lab-book passages remain directly edited native LaTeX.
Retain Minion Pro, Noto Sans Math, the actual IntelliJ highlighter bridge and native
accessible teaching figures. Verify revised code, screenshots and final PDFs rather
than transferring old receipts to changed artifacts. Before every actual IDE launch,
run the real `clean cleanSandbox runIde` tasks.
