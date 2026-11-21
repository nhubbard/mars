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
in the pinned reference checkout. Jupiter verification passes 952 plugin tests, including 57 learning tests, covering
all 535 learning lines and 258 branches. The separate authoring plugin passes 90
tests. The actual `native-diagnosis-01` batch observes all six native segments,
including wrong-answer feedback, faulty argument pauses, an equivalent `or` repair,
normal Run exit with output 41, and a second native repair pass after restart.
The repair survives restart while the counterexample remains unchanged. The log
has no SEVERE entries and all eleven installed JARs match the saved packages.

The batch retains twenty-two raw light-theme captures; the eight new diagnosis and
repair views were visually inspected. Publication cleanup, annotations and
accessibility verification remain open. Direct process input does not verify
console typing/echo. The faulty Debug observation stops before output and does not
claim normal termination or an observed faulty console transcript. The repaired
Run verifies normal exit separately. Paired API verification is compatible; the
concurrent separate Gradle verifier failed after an extracted JAR disappeared
from its shared cache. The serial Gradle retry succeeds, including the full test suite and unchanged coverage
and generated-parser gates. Package verifiers must run serially.

## Independent writing with public behavioral checks

The seventh native segment opens `independent.asm`, a skeleton containing the
prompt, new answer-prefix and newline data, plus a normal exit. Students write
input, computation and output for 3x+1 over -1000 through 1000. The check action
runs public inputs 20, 0, -3, -1000 and 1000, requiring the supplied transcript,
normal exit and a 2000-instruction bound. It accepts different labels, scratch
registers and equivalent implementations. It does not inspect a reference source
or require the worked program's instruction sequence.

The normal build service captures the current editor text and reuses its assembly
image. `PreparedConsoleChecks` is a new public programmatic entry point in the
existing assignment-definition library. It uses the grader's existing bounded
console, host-service restrictions, normal-exit and exact-output checks. Every
case loads the image into a fresh factory session; the student's Run/Debug machines
are untouched. Cancellation is checked during loading and between bounded case
executions. No downloaded Kotlin script is evaluated.

A native background action reports each case through a standard dialog. Source
revision capabilities, document/VFS invalidation and captured-source comparison
prevent edited, replaced or closed lesson attempts from earning an earlier pass.
This first independent task uses one file without a custom exception handler.
The shared checker API also tests restored target/byte-order data, isolated case
state, output/instruction bounds and cancellation cleanup. The full check passes 962 plugin tests, including 67 learning tests, and all 131
CLI tests pass. Learning code covers all 632 source lines and 304 branches. The
separate authoring plugin passes 90 tests; paired verification finds both exact
packages compatible (85 and 20 experimental public API uses).

The fresh `native-independent-01` IDE batch observes all seven segments. The empty
starter fails all five public cases. A private fixture with a different entry
label and scratch register passes, then a source edit requires rechecking. After
that new pass, restart preserves the work and produces a third native independent
completion callback. Its log has no SEVERE entries, and all eleven installed JARs
match the retained packages. Six new independent-program captures were inspected;
the batch contains twenty-eight raw light-theme views. Instructor solution views
remain private and must not be published as student exercise material. Notification,
scroll/layout, annotation and publication-accessibility work remains open.
Public formative checks and answers remain inspectable; instructor grading is
separate. Full project reopen/resume and human review are not yet verified.

## Graduated hints for independent writing

The independent exercise offers four optional hints: plan the value flow, retain
needed values, combine them deliberately, and compare with a concrete trace.
`Mars.Learning.IndependentHint` uses a standard accessible native dialog. Revealing
help records stable hint IDs in the content-specific progress history; it neither
edits source nor completes an execution check. Reopening shows only the hints
already requested. Edits retain that history while invalidating source checks.
Explicit progress reset starts a fresh hint history. Help is scoped to the active
lesson and becomes unavailable when the native trainer disposes that lesson.

Hint actions work from the Learn pane without editor-specific data keys. The
independent check action also accepts native Learn-pane invocations, but still
requires the selected editor to be the bound lesson document. The public,
experimental lock-free selected-editor API supports its background update.
Renaming or deleting the bound file prevents the old document from earning a pass.
Jupiter fixtures cover those cases, reveal order/exhaustion, stale dialogs, reset,
persistence, and preserving current source evidence when revealing a hint.
The full check passes 968 plugin tests, including 73 learning tests; learning
coverage is all 687 lines and 346 branches, with unchanged coverage gates.

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

The `native-hints-01` batch verifies all seven segments in the actual light-theme
IDE. From the real Learn-pane context, the driver reveals two hints, reopens and
reveals the remaining two, verifies no source edit or completion from help, then
checks an equivalent solution. After an edit/restart it observes the same four
hints with reveal disabled, requires rechecking and verifies retained work on a
further restart. Three native independent completion callbacks are observed.
The thirty-five raw captures include seven new hint views inspected by AI.
Both exact packages pass paired API verification (86 MARS and 20 authoring
experimental public API uses); eleven installed JARs match the retained archives.
The successful IDE log has no SEVERE entries. A prior authoring attempt incorrectly
requested help after native completion disposed the lesson; that failed receipt is
retained separately, and the corrected capture stays within the active lesson.


## Independent exercise across native project close and fresh IDE processes

`native-reopen-01` now verifies persistence beyond a lesson restart. The separate
authoring driver first executes all seven segments, then uses the public
`ProjectManager.closeAndDispose` contract to save and close the fixture. The native
workspace contains the content-specific source revision, execution checks, quiz
attempts and four hint IDs. A new IDE process opens that existing project after
actual `clean cleanSandbox runIde` tasks. The unchanged independent program earns
one native completion callback without invoking its check action or installing a
solution. The source remains byte-identical, and native save/close succeeds again.

After confirmed termination of that IDE process, one comment is prepended outside
the IDE. The workspace/progress file is untouched. Another fresh process requires
a new execution check, retains all four hints with reveal disabled, passes the five
public cases and completes once. The driver never injects progress or a solution
on either resume path. Before and after source hashes, saved workspaces, exact
packages and installed JAR checks are retained privately.

The driver uses the public toolwindow command queue before opening lessons and the
public asynchronous Learn-window show callback. Two earlier observations timed out
with the restored editor visible and Learn hidden; their partial evidence remains
separate. The synchronized driver succeeds on the same saved project. This change
is confined to the separate authoring plugin; student persistence code is unchanged.
Its 91 Jupiter tests and paired package API verification pass. The MARS package is
the unchanged one previously checked by 968 plugin tests, including 73 learning
tests; this step does not claim another full MARS suite invocation.

Both successful reopen logs report a cancellation error attributed to the bundled
Air agent-provisioning plugin when closing the project. They are not error-free
logs. The successful MARS lesson callbacks and native save/close receipts are
recorded separately. Seven new successful reopen views were inspected by AI;
clipped lesson text and notifications still need publication layout and annotations.
This verifies the independent exercise's reopen behavior, not automatic resumption
at every Run/Debug task, instructor policy enforcement or full curriculum delivery.


## First-unit instructor policy

Settings → Tools → MARS Learning now exposes two project-shared course controls:
the number of released independent-program hints (0–4), and whether both current
prediction quizzes are required before independent assessment. Native
`LessonProperties` has no MARS course-policy facility. A normal project persistent
component and native `BoundConfigurable` supply those MARS semantics, while the
public trainer task DSL continues to own lesson presentation and progression.
The policy is stored in `.idea/mars-learning.xml`; personal evidence stays in the
workspace file. Client-side policy is inspectable/editable and does not replace
private instructor grading.

The independent source opens before a native prerequisite task. That task explains
which quizzes are required; the assessment/hint checkpoint is not installed until
the requirement is met. The checker also validates policy before execution and
before accepting results, so invoking it programmatically or changing policy during
execution cannot bypass an unsatisfied prerequisite. Genuine source evidence is
retained, but native completion also consults current prerequisites. Changes to task
display require lesson restart; hint dialogs refresh on reopening. Hint release
limits new reveals to the first N hints and never erase previously read help.

The final `native-policy-01` batch uses the actual clean cleanSandbox runIde tasks
and invokes the real native project Settings dialog. It requires the independent
assembly file alongside the blocked lesson, verifies both actions disabled, answers
the two real prediction dialogs, then observes independent assessment become
available. Releases 0, 2, 0 and 4 respectively disable help, allow two hints, retain
those hints under restriction, and allow the remainder. Neither policy nor hints
edit source or complete a task. The unchanged starter fails all five public cases.
Native save/close writes the shared policy file. Eleven installed JARs match the
exact retained archives, and the final IDE log has no SEVERE entries.

Earlier attempts remain distinct: the first functional batch captured a settings
loading overlay; the next waited for native settings painting but showed an empty
editor's agent welcome screen beside the prerequisite. The final lesson opens the
student source before its gate, and the separate driver verifies that source is
selected. All sixteen final views were inspected by AI. They are raw light-theme captures,
with notification, clipping/scrolling and publication annotation work still open.
Per-step required-check policy, retry limits, explicit mode controls and remote
policy delivery remain future work. Two first-unit controls are not full curriculum
policy enforcement.

The final full check passes 974 plugin tests, including 79 learning tests. Learning
code covers all 729 lines and 374 branches under unchanged gates. The authoring
package passes 92 Jupiter tests; all 391 public Python tests pass and the existing
student package validates and rebuilds. Paired API verification finds both final
packages compatible (86 MARS and 20 authoring experimental public API uses), with
no override-only diagnostics in the final authoring report. Its three direct action
update calls were replaced by the supported `ActionUtil.updateAction` API; earlier
reports with those diagnostics remain bound to their old packages.


## Explicit guided and free-writing modes

Tools → MARS Learning (also available through Find Action) now opens a native
project-specific chooser. Guided work initially opens prediction activities; after
opening other guided segments it returns to the last one opened. Free writing
opens the independent contract and its separate working file. Every lesson's
preparation records navigation only after ensuring its source exists. Sources are
never replaced on mode changes, and choosing a mode neither awards nor resets
quiz, hint or execution evidence. Native CourseManager still owns the lessons.

A workspace component stores the last mode and a separate guided destination.
Only the six bundled guided IDs are accepted; malformed saved destinations fall
back to predictions and cannot navigate into another course. The public toolwindow
queue coordinates opening with native project UI initialization. Headless tests
exercise immediate native course preparation separately because the platform's
headless toolwindow queue deliberately does not execute UI commands. Actual GUI
batches verify the queued production path. Missing course registration fails
without routing into an unrelated lesson; disposed projects ignore queued work.

The shared course policy adds Allow free-writing mode. A restriction disables that
choice and defaults the chooser to guided work while preserving the saved sources
and navigation history. The independent lesson and checker enforce it as well, so
entering through the native lesson list cannot bypass it. An already open chooser
also rejects free writing if policy changes before acceptance. Defaults preserve
existing free-writing availability; older policy files omit the new default field.

The native-modes-01 initial IDE batch uses the production action, real dialogs and
native course settings. Cancel creates no exercise files; guided work opens the
prediction lesson, then Run becomes the remembered guided destination. Two fixture
comments are the only source edits. Repeated mode changes preserve both files and
one revealed hint. The unfinished independent source still fails all five public
cases. Settings disable free writing, the chooser and direct lesson entry both
respect that restriction, and releasing it restores access without losing history.
The batch does not execute normal Run/Debug and does not claim an observed runtime
result from the pending Run lesson.

After native save/close and process termination, a fresh clean cleanSandbox runIde
launch loads the same project. Its chooser selects free writing, shows Run as the
guided destination, opens that edited source, then returns to the edited independent
source and saved hint. No source, quiz, hint, progress or policy is injected on that
path and no execution check is invoked. Both native saves close successfully and
eleven installed JARs match each phase's exact archives. The initial log has no
SEVERE entries; the short reopened phase records a cancellation error attributed
to the bundled Air agent-provisioning plugin during close. That log is not error-free.

The full plugin check passes 979 tests, including 84 learning tests, with all 819
learning lines and 416 branches covered under unchanged gates. The final separate
authoring package passes 94 Jupiter tests; paired API verification is compatible
with 86 MARS and 20 authoring experimental public API uses and no override-only
diagnostics. All 391 publication-tooling tests pass. The directly edited four-page
lab proof explains when to choose each mode, retains its required embedded fonts,
and passes automated PDF/UA-2 validation: 1,728 rules, 13,614 checks, zero failures.
All four proof pages and twenty-five raw light-theme capture views were inspected
by AI. One immediate post-edit view caught the prior paint; later resumed views
show the retained comment. None is an annotated publication figure. Initial raw
capture imperfections, human review and publication selection remain explicit.

## Guided restoration after a fresh IDE process

The separate authoring plugin now has a constrained resume-only driver. It opens the six existing guided lessons through the public CourseManager and LessonListener contracts inspected in the pinned reference checkout. It verifies source hashes before and after, observes native lesson-pass callbacks and counts process starts. It does not inject source, answers, progress or execution. Its plan rejects mixed capture modes and requires all three existing source digests. New capture code remains outside MARS.

The private `guided-resume-01` batch uses the actual clean cleanSandbox runIde tasks for both fresh IDE processes. After native save/close of the earlier observed work, all six guided segments report one native pass callback with zero simulator process starts. A subsequent offline comment edit to first-program.asm makes Run, arithmetic Debug and output preparation remain pending, with zero pass callbacks. Predictions, diagnosis and the separate repaired program still complete. Native workspace XML confirms that exactly the three source-bound execution records lost successful checks; other histories remain unchanged. Both phases preserve source and complete native save/close.

The exact unchanged MARS package reuses its earlier full-suite and coverage evidence. The new separate authoring package passes 95 Jupiter tests; paired verification against the pinned IDE reports compatibility, 86 MARS and 20 authoring experimental public API uses, without internal or override-only diagnostics. Eleven installed JARs match the retained package members in each phase. Controlled termination of the owned welcome-screen IDE yields a nonzero runIde exit after completed observations. The unchanged phase also records the known bundled Air provisioning cancellation error during project close; its log is not error-free.

This proves restoration of completed guided evidence and offline-edit invalidation for the first unit. It does not serialize running machines, restore an unfinished multi-pause trace, establish all interruption paths, or complete reset and finer instructor policies. All twelve raw views were inspected by AI; horizontal clipping of some native lesson text and long navigation buttons remains a usability issue at the captured panel width. Author and representative learner review remain open.
