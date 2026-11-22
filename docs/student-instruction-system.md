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

## Native lesson pane readability

The pinned Features Trainer LearnPanel uses an internal minimum text width of 460 logical pixels and builds navigation buttons from lesson names. The earlier saved pane was narrower, causing horizontal scrolling and clipping. Neither internal LearnPanel nor internal UISettings is accessed by MARS. The public ToolWindowEx.stretchWidth API is present in the platform API dump and implements native dock resizing; its supported contract was inspected before implementation and verified in the packaged plugin.

After the public suspending file-open API completes on EDT, MARS fits a visible side-docked Learn pane to at least 500 scaled pixels. It leaves wider panes, horizontal docks, floating/sliding windows, hidden windows and not-yet-laid-out components unchanged. Concise lesson names keep the native Back/Next row within that width. Stable lesson IDs and source files are unchanged. Since bundled content identities include the message resource, this new bundle receives new digest-specific histories; earlier edition records are retained, not silently migrated or overwritten.

The full plugin check passes 981 tests, including 86 learning tests, with all 825 learning lines and 428 branches covered. The unchanged package-wide line/branch and generated-parser coverage gates pass. The separate authoring driver measures ordinary Swing viewport, text and button bounds without importing native internal lesson classes. Its tests detect left/right text clipping, oversized panels and overflowing navigation controls. Screenshot automation remains outside MARS. Exact native capture and API evidence is retained privately in readable-learning-01; final capture scope and any remaining limitations are documented there. This does not replace human usability review or establish every display scale and window arrangement.

## Recoverable first-unit history reset

Tools now exposes Reset MARS Learning Progress and Recover MARS Learning Progress for the bundled first unit's seven current content identities. Both use standard DialogWrapper confirmation and project persistence. The pinned course/lesson APIs have no recovery transaction for MARS's source-bound evidence; their internal whole-lesson passed flag is not modified. Existing alwaysRestart lessons consult project evidence when reopened. New screenshot/reset automation remains solely in the separate authoring plugin.

Reset saves one recovery map for this edition, removes its active histories and cancels outstanding check tickets. A later reset with actual quiz, hint or successful-check history replaces that edition's copy. An opened but empty lesson is insufficient: it cannot overwrite a useful recovery copy. Other courses/content editions are preserved. Recovery merges saved quiz attempts before newer attempts and unions revealed hints, consumes the copy and cancels pending tickets. All execution credit in this unit is cleared, including a segment first attempted after reset; current source must be checked again. Source files and instructor policy are preserved. The first guided segment opens after either confirmed operation.

The model/Actions step 4c227e08 passes 988 plugin tests, including 93 learning tests, with all 893 learning lines and 468 branches covered. Package-wide 100% line/branch and generated-parser gates remain unchanged. Jupiter covers serialization, project/scope isolation, late checks, new history, replacement of recovery copies, empty reset, cancellation and disappearing history during confirmation.

The private recoverable-reset-01 batch creates real prediction/Run/Debug/output/diagnosis/repair evidence and one hint through production UI. Reset cancellation retains the hint. Confirming reset yields zero prediction-pass callbacks and disables another empty reset. Native save/close persists seven recovery records containing ten quiz attempts, one hint and five execution passes. After an offline comment edit and a fresh IDE process, production recovery restores the choices and hint, preserves all four sources including that edit, consumes the copy and starts no process. Run and repair remain pending. Saved XML confirms all seven recovered records have zero execution credit. The repeatable private audit rejects restored stale credit, altered quiz history and incorrect source-edit evidence.

The final separate authoring package passes 97 Jupiter tests and paired pinned API verification (86 MARS and 20 authoring experimental public usages, no compatibility/internal/override-only diagnostics). Eleven installed JARs match the appropriate exact retained package in each phase. All launches run actual clean cleanSandbox runIde. An initial dialog image was taken before native placement; it is explicitly rejected, retained and recaptured with stable coordinates. Native save/close succeeds before controlled termination of each owned welcome-screen IDE, producing nonzero runIde termination exits. The retained logs have no SEVERE entries.

The written lab adds direct native LaTeX guidance for these controls. Its six-page proof passes source/pixel/font/tag checks, color/grayscale inspection and the pinned automated PDF/UA-2 profile (1,728 rules, 24,864 checks, zero failures). This is not a human accessibility or learner review. Finer instructor policies/retries, appropriate content delivery, remaining interrupted-task cases and the full curriculum still require work; this first-unit feature does not complete the goal.

## Instructor-selected guided requirements

Settings → Tools → MARS Learning now selects any of the first unit's five execution activities as prerequisites for independent assessment. Defaults preserve existing course availability. Each selection requires its current-edition successful check and, for output preparation and diagnosis, correct latest associated question answers. The independent lesson names missing activities and disables its check action. Unknown stored requirement IDs remain present and block assessment with a version-mismatch explanation; their untrusted text is not inserted into trainer markup. Other setting updates preserve all selections.

A project-lifetime source tracker replaces lesson-lifetime subscriptions. Native document and VFS events invalidate existing source-bound histories even after leaving a lesson, including an edit followed by restoring the original text. Reset histories are not recreated by unrelated edits. Bindings replace the existing identity/path entry after file recreation. Quizzes and hints survive execution invalidation. Before starting and accepting an independent assessment, required sources are refreshed outside read actions, and the checker validates the same selected requirement set against current document text. Changed requirements or source while a check executes cannot receive credit. Native file/document services and the public project lifecycle provide this mechanism; no parallel file watcher or trainer implementation was introduced.

The implementation commit 5fea0595 passes 994 plugin tests, including 99 learning tests. All 969 learning lines and 520 branches are covered under unchanged package-wide line/branch and generated-parser gates. Tests cover each requirement, associated latest answers, XML persistence, unknown IDs, unloaded or missing source, external disk edits, edits restored after leaving a lesson, and a prerequisite changed during an otherwise correct independent assessment.

The separate authoring plugin passes 98 Jupiter tests against the exact retained MARS archive. The private guided-requirements-01 native batch selects all five requirements through Settings, observes their actual Run/Debug checks and questions, enables independent assessment and rejects its unfinished starter. An inactive edit-and-restore clears exactly the three worked-source observations. Selecting only the unchanged diagnosis and repair activities permits assessment again. Native save/close followed by a fresh clean cleanSandbox runIde launch loads those settings and detects an offline repair-source edit. Releasing repair leaves the unchanged diagnosis accepted; the starter again fails all five public cases. No private solutions or saved progress are injected.

Eleven installed JARs in both accepted phases match their exact retained archives. Paired API verification is compatible, with 86 MARS and 20 authoring experimental public API usages and no internal/override-only/compatibility diagnostics. Accepted logs have no SEVERE entries. The first partial native run is retained as rejected: the authoring driver incorrectly asserted that an edit stamp would remain changed after saving restored text. The corrected driver records the actual edit before saving and asserts the invalidated state; source tracking itself was unchanged. Each accepted project saves and closes before its owned welcome-screen IDE is terminated, producing the expected nonzero runIde exit.

A repeatable private audit verifies saved policy, seven content histories, ten preserved quiz attempts, source archives, raw image hashes, pane geometry and installed packages. Negative variants reject restored stale credit, a missing required policy selection and a falsified offline-edit digest. Selected native states were visually reviewed; raw frames containing update notifications remain verification evidence rather than publication assets.

The native six-page lab proof explains prerequisites and independent.asm separately from the worked example. Its three figures retain fourteen native callouts and four unchanged original captures. The current PDF passes embedded-font, pixel/tag/geometry checks and automated PDF/UA-2 validation (1,728 rules, 25,707 checks, zero failures); color overview, changed prose and grayscale figures were inspected by AI. All 391 public tooling tests and the worked example's positive, zero and negative cases pass. Human instructional and accessibility review remain open. This is first-unit progression, not arbitrary curriculum sequencing, retry limits, remote delivery or completion of the 35-project goal.

## Completed-assessment policy

The first-unit course setting Maximum completed independent assessments accepts zero for unlimited formative checking, or a limit from one to 1,000. Defaults preserve unlimited attempts. Its new control uses the pinned platform's validated intTextField/bindIntText contract; JBIntSpinner is marked obsolete and is not introduced for the new setting. An accessible name identifies the field. The public trainer lesson API does not provide MARS's source-aware assessment accounting; the existing project progress component owns that simulator-specific state.

Only a complete, current five-case independent assessment consumes one attempt, whether it passes or fails. Assembly errors, cancellation, source edits, superseded tickets and discarded results do not count. A completed retry remains a new attempt even when the build cache reuses its image. Counts survive ordinary source edits and reopening. A blocked retry is rejected before beginning a new source check, preserving a genuine successful result. Reaching a limit also leaves that result valid for native lesson completion and keeps optional hints and ordinary Run/Debug available. Increasing the limit releases more checks. The feedback reports the used count and explains what does and does not count.

Acceptance checks the capability and count under the progress service's existing monitor while holding source read access. A limit exhausted during execution rejects the result without charging it. Recoverable reset treats even a failed completed assessment as real history, saves its count, and starts a new active history. Recovery adds saved and newer counts, consumes the recovery copy and clears execution credit. Persisted negative counts normalize to zero; additions saturate rather than overflow. Reset/recovery confirmations explicitly describe the count. These inspectable local formative controls do not create an authenticated or tamper-resistant quota.

The final full check passes 1,001 plugin tests, including 106 learning tests, with all 1,005 learning lines and 554 branches covered. Package-wide line/branch and generated-parser gates pass unchanged. Jupiter covers successful final attempts, rejected retries preserving success, failed checks, uncharged assembly errors and cancellation, limits reduced during execution, persistence, source edits, reset/recovery addition and overflow. Two earlier full runs exposed an existing debugger fixture race: the pause callback preceded queued editor navigation, allowing that navigation to replace the test's assembly selection. Commit 5d4ca9cb opens the paused source before enabling assemble-on-open, retaining all original pause/register/exit assertions and the timeout. The corrected test passes in the focused run and final full suite. Earlier failures and an intermediate passing run are retained, rather than relabelled as final evidence.

The private assessment-attempts-01 batch exercises native Settings and real independent feedback. One completed check reaches a limit of one; another request and a source edit do not reset the count. Releasing a second assessment permits exactly one more check. Native save/close followed by fresh clean cleanSandbox runIde restores two of two used. Releasing a third permits one more, then blocks again. The unfinished starter fails all five cases on each permitted check; no answers, progress or completed solution are injected. Successful final attempts and cancellation are Jupiter evidence, not claimed native capture observations. Both accepted native logs have zero SEVERE entries, and eleven installed JARs match their exact archives per phase. Each owned welcome-screen IDE is terminated only after saved/closed receipts; the nonzero Gradle termination exits are controlled cleanup.

The separate driver passes 99 Jupiter tests. Paired final API verification is compatible, with 86 MARS and 20 authoring experimental public API usages and no internal/override-only/compatibility diagnostics. The repeatable private audit verifies counts, policy, source, fourteen raw image hashes and saved project/archive bindings; negative variants reject erased counts, changed limits and false source receipts. Selected settings, limit and count-footer views were inspected. These remain verification captures, not newly annotated publication assets.

The written lab adds concise native guidance. The six-page proof retains required embedded fonts and its three figures/four unchanged original captures, passes automated PDF/UA-2 validation (1,728 rules, 26,170 checks, zero failures), and preserves exact reviewed pixels on the other five pages. The changed page was inspected in color and grayscale. All 391 public tooling tests, student package validation/build and the worked example's three cases pass. Human instructional and assistive-technology review remain pending. The full 35-project goal, general content delivery and arbitrary per-step curriculum policy remain open.

## Versioned student-archive file delivery

Tools → Import MARS Lab now imports the existing format 1 public distributions through native path selection, preview and editor opening. The current supported payload is a project distribution, not executable lesson code. Manifest identity/version/title, MIPS64 Release 6 or MSA target, educational ABI and starter/assignment references are validated. The importer does not interpret historical steps as trainer tasks or run the included Kotlin checks. The bundled first-unit lessons remain separate. General native content interpretation, a remote catalog and explicit learning-progress migration remain open parts of the goal.

The native path field uses the supported Browse listener and current user-home expansion API inspected in the pinned reference checkout. Modal progress and Dispatchers.IO keep archive work away from the UI thread; DialogWrapper owns preview/cancellation. Public suspending editor APIs open the assignment and starter. New import messages use a separate bundle so delivery-UI wording does not create a new first-unit content identity. The preview identifies the target, version and exact digest and wraps ordinary explanation at word boundaries.

Preview owns a bounded private ZIP snapshot. Actual compressed bytes, file bytes, total expansion and entry counts are checked (32 MiB, 16 MiB, 128 MiB and 4,096 respectively). Strict JSON has a bounded nesting depth; duplicate manifest fields and unsupported metadata are rejected. Portable paths reject traversal, hidden/instructor-only components, case conflicts and reserved device names. The platform's extended ZIP extractor supplies attribute/symlink handling. Preflight and extracted hashes also reject inconsistent ZIP size fields and conflicting Unicode filename metadata. The published file-only ZIP layout is supported; explicit directory records are rejected. No second archive framework, file watcher or simulator was introduced.

Complete working copies are published under mars-labs/packages/id/version/full-archive-digest using the existing-target-protected filesystem move pattern already used for MARS starter creation. An identical reimport opens existing work; another version or different bytes at the same version produce another folder. The original archive and receipt remain beside the editable files. Corrupted receipts/caches, missing entry files and symlink parents are reported without replacing student work. Temporary snapshots and staging directories are cleaned up on cancellation or failure. This is explicit version separation, not a source merge or automatic progress migration.

The importer implementation d9aabc5d and word-wrap correction 2b9c394a pass the final full check: 1,032 plugin tests, including 137 learning tests, with all 1,229 learning lines and 716 branches covered. Unchanged package-wide line/branch and generated-parser gates pass. The initial implementation also has a passing full run. An interrupted later run recorded a null XDebuggerTreeRestorer during native frontend session stop; its binary results, log and recovered exception are retained. The final isolated run passes without modifying or weakening that test. Do not attribute the frontend exception to the archive importer or to the desktop-app update without further evidence.

The separate authoring plugin passes 101 tests. The private lesson-import-01 batch drives the production action for all 35 current public distributions plus a new-version console archive and a changed-content archive at the same version. Cancelling selection and preview creates no working copy. All 37 imports select the expected starter, and reimport preserves a normal student comment. Normal Run exits zero with empty output for the unfinished first starter; Debug pauses before its exit-selector load, then visibly shows v0=10 after stepping. This exercises imported source execution, not completed submission grading. The first after-step image was taken before repaint and is explicitly rejected. The corrected driver waits for the native view to paint and captures a visibly correct value; it does not synthesize debugger pixels.

A fresh clean cleanSandbox runIde process loads the saved final project, verifies all 37 entry hashes, reimports the edited first package and retains every source. Both final phases save/close before controlled termination of their owned welcome-screen IDE, so the Gradle termination exits are nonzero cleanup results. Eleven installed JARs per phase match the exact final archives. Paired API verification is compatible (91 MARS and 20 authoring experimental public API uses), with no internal, override-only or compatibility diagnostics. An intermediate report that inspected the previous distribution before buildPlugin finished is retained under its actual earlier archive binding; final verification is bound to the completed build.

The repeatable private audit reconstructs every captured regular file from exact input distributions plus small overlays and compares full sizes/hashes. It verifies 37 native import receipts, version/content isolation, the edited entry, fresh-process preservation, the two debugger observations, installed packages and thirteen final raw-image receipts. Negative variants reject missing import coverage, altered source preservation and corrupted tree evidence. Selected preview and debugger views are visually reviewed; update notifications and long native digest-based tab names remain in these verification screenshots. They are not new publication figures or human usability evidence. All 391 public tooling tests pass; original manuscript and unrelated source edits are preserved. The author's independent walkthrough and the full main-book/lab/native-curriculum revision remain open.

## Inline prediction checkpoint (21 November 2026)

Guided predictions use native Learn-pane callback links for answer choices and
inline wrong-answer feedback. A native source highlight follows the relevant
instruction. Independent work also exposes “Check my program” and “Open a hint”
as lesson links. The legacy prediction actions remain available for compatibility;
the guided path does not require Find Action. Existing prediction identity and
history remain compatible because the presentation strings have a separate bundle.

The trainer queries highlight geometry from a Swing repaint timer without the
lesson task's read context. The callback explicitly acquires read access for PSI
lookup and editor geometry. A raw Swing timer regression starts without read
access and checks that the callback succeeds. The isolated full plugin check passes
1,039 tests, with all 1,287 learning lines and 748 branches covered. The integrated
checkout's 18 focused tests also pass alongside the author's separate cleanup.

The exact packaged MARS and separate authoring plugins pass API verification on
IU-263.7307.200. Native interaction verification remains incomplete: the corrected
IDE run timed out waiting for the first question, before any answer clicks or new
screenshots. Its log did not reproduce the previous read-access exception, but
this does not establish that the complete inline walkthrough works. Resume by
investigating that first-question wait, then exercise all five questions, retries,
source highlights, ordinary Run/Debug, independent check/hint links and restart.
Do not treat the earlier dialog-based captures as evidence for the new controls.

### Resumed inline walkthrough verified

The resumed `inline-prediction-02` batch supersedes the pending native walkthrough
above. A fresh IDE process completed all five quizzes through visible answer links,
with wrong-answer feedback, correct retries, instruction focus and retirement of
completed choices. Correct explanations appear at their runtime step rather than
as inactive previews. The trainer's paragraph-based removal requires the quiz to
be one message; XML line feeds inside its text parts preserve visible choice lines
without creating separately completed paragraphs.

Normal Run, arithmetic Debug, output preparation, diagnosis and repair completed.
The independent starter failed, an equivalent implementation passed all five
public cases, edits required reassessment, and check/hint links worked directly.
Prediction restart produced two native passes with source retained; independent
restart preserved edited work and hint history. The project saved and closed
normally. All 35 captured images and the native receipts are retained privately.
The final IDE log has no SEVERE or ERROR entries, including no read-access failure.

The full plugin check passes 1,039 tests with all 1,288 learning lines and 748
branches covered. Eighteen focused tests pass alongside the author's cleanup.
The separate authoring plugin passes 104 tests; the exact plugin pair passes
packaged API verification. Native logs confirm zero-width caret rectangles, which
the capture driver now normalizes to one pixel for visibility checks. The driver
also waits for the current diagnosis wording. Both corrections remain outside MARS.

This batch verifies the ordinary first-unit path and lesson restart. It does not
repeat fresh-process policy, reset or recovery scenarios, and it is not human
learner review. The complete curriculum and downloaded native lesson delivery
remain unfinished. The revised written lab now explains the inline controls and
includes an accessible native LaTeX figure from this successful capture.


## Course-server archive retrieval

The existing Import MARS Lab action now accepts an HTTPS course link as well as
an absolute local archive path. Both routes use the same immutable package
preview and installation path, preserving edited imports and isolating different
versions/content. The public platform `HttpRequests` API supplies proxy, TLS and
redirect handling. Its bounded input-stream loop adds the archive-byte limit
that `Request.saveToFile` does not expose; no parallel networking framework is
introduced. See [lesson delivery](lesson-delivery.md) for student and server behavior.

This retrieves assignment files; it does not interpret old package steps as native
trainer lessons. Downloadable native lesson execution remains a separate part of
the active curriculum goal. Publication capture automation remains in the separate
authoring plugin. Source and test bindings, API verification and actual IDE
observations are retained in the private `lesson-download-01` evidence record.


Verified in `lesson-download-01`: the full plugin check passes 1,046 Jupiter
tests and strict learning-package line/branch coverage; the separate authoring
plugin passes 106 tests. Both exact packaged plugins pass paired IntelliJ
2026.3 API verification with experimental public usages and no internal or
override-only problems. A real light-theme IDE downloads the bound public ZIP,
cancels preview without creating a working copy, imports and preserves an ordinary
student edit on reimport, runs the unfinished starter, observes `v0=0` and then
`v0=10` at adjacent Debug boundaries, and saves/closes the project normally.
All eleven installed JARs match the verified archives. Source-field width and
screen-paint timing were corrected after visual inspection; the initial captures
remain historical evidence. These are development observations, not newly
published book figures or proof of downloaded native lesson execution.


## Versioned native content reader

Packages may opt into a bounded prediction definition through `nativeLesson`.
`MarsNativeLessonPlan` reads strict UTF-8/JSON, rejects nested duplicate and unknown
fields, validates stable question/choice/hint IDs and label/instruction anchors,
and returns immutable definition lists. Validation occurs against the private
archive snapshot before preview. Legacy packages keep their existing import path.
The protocol and its current limits are documented in
[native lesson format](native-lesson-format.md).

The reader is verified by 1,053 plugin Jupiter tests and complete learning-package
line/branch coverage. The actual four-question byte-inspector draft passes through
the packaged reader; its unchanged underlying programs pass all 256 byte inputs,
three outside-range observations, the width boundary and four meaningful fault
checks. The public book repository keeps this runtime draft separate from native
LaTeX composition. Evidence is retained in `native-lesson-content-01`.

This step validates definitions. Native Learn-pane rendering, scoped feedback,
source highlighting, hint release and saved/resumable downloaded selection are
still to be connected. No new IDE lesson walkthrough is claimed for this reader
milestone. The first unit's bundled native implementation remains unchanged.

## Downloaded prediction runtime

Packages carrying a validated native definition now open their predictions in
the native Learn pane after import. The public host receives a project-specific
module directly because its normal course catalogs are cached globally. The
bundled course catalog remains stable. The new continuation action revalidates
the selected edition's retained archive before reopening it.

The renderer shares the bundled inline-choice mechanism, with an explicit lesson
identity for downloaded content. It escapes explanatory text, records wrong and
correct attempts, retires completed controls, and records graduated hints without
granting answer credit. Hint availability follows the project's instructor policy.
History is scoped by package version and full archive digest. Working source is
preserved when continuing; a missing working file produces an error.

Source pointers resolve committed MIPS PSI by unique label, mnemonic and occurrence
within that block. Ambiguous, deleted or uncommitted anchors produce no pointer.
Both instruction resolution and the native timer's geometry callback acquire read
access. A source pointer does not count as observing execution.

The revised byte-inspector draft supplies the first downloaded prediction segment.
Actual light-theme IDE walkthroughs click its wrong answers, correct answers and
graduated hints, then save and close after the second question. A fresh process
uses the continuation action, resumes the third question at `seb`, preserves an
ordinary student source comment, and completes the remaining predictions. Capture
automation remains entirely in the separate authoring plugin. The first capture
attempt exposed an authoring write-context error; it was corrected and the full
workflow repeated. All attempts are retained in `native-lesson-runtime-01`.

These are development observations. They do not constitute a learner review,
new publication figures, or completion of the written lab. Downloaded execution
checks, independent-writing assessment and the remaining curriculum segments are
still part of the active goal. Native LaTeX remains the publication authority.

## Ordered prediction and Run activities

Native format 2 adds explicit activity kinds while retaining format-1 predictions.
A lesson can interleave predictions and observations of ordinary student Run
launches. The revised byte-inspector draft now uses this sequence: predict the
150 results, observe that run, predict the 128 boundary, then observe 128, zero,
127 and 255. Native LaTeX remains the reading source; runtime data does not compose
or reorder the publication.

Run checks reuse the source-binding and progress capabilities already used by the
first unit. Each activity has its own edition-scoped source history. The new
read-only integer accessor follows the process handler's existing floating-point
inspection pattern and retains a detached snapshot after termination. The observer
uses completed service-5 results, rather than treating matching stdout as proof of
input. It does not launch, supply input, step, or alter a machine. The accepted
contract requires consumed integer inputs, exact output, normal termination and
matching captured source. Equivalent instruction sequences are accepted.

The existing lifecycle-owned execution service creates these observers. The latest
supported launch owns its attempt; edits, resets, disposal and queued results
cannot award another attempt credit or deliver stale feedback. Shared hint controls
honor the instructor release policy. Completed controls retire with their task,
and a final gate requires all Run observations to remain valid after source edits.

Actual light-theme IDE verification includes HTTPS import, fresh-process resume,
406 producing the same output as 150 but failing the input check, a source edit
while a run waits for input, rechecking after an edit to previously observed source,
and all five accepted current-source runs. The native lesson retains prediction
and hint history through those retries. Evidence is retained privately in
`native-execution-01`. The lab book describes the checked workflow and distinguishes
the remaining manual Debug and independent activities. Automated publication
accessibility verification is renewed for its native PDF; human review remains
pending. Downloaded Debug and independent assessment, broader I/O contracts and
the remaining curriculum still belong to the active goal.
