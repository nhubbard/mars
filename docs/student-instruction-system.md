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

## Remaining first-unit integration

Select the supported Features Trainer course registration and entry action after
checking the actual packaged SDK and compatibility verifier. Inspect public quiz
and choice presentation facilities before introducing any MARS assessment UI; record
a concrete gap if the host cannot provide the required interaction.

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
