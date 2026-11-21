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

## Remaining first-unit integration

The unit must connect explanation, prediction, actual Run/Debug observations,
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
