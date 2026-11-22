# Native lesson content

A student package may opt into native lesson content with a `nativeLesson` path
in its existing version-1 `package.json` manifest. The path follows the same
relative-path rules as its entry and assignment. Archive preparation validates
the definition before returning the immutable import preview. Older packages
without the field keep their existing file-import behavior.

Importing a package with this field opens its predictions in the native Learn
pane. Tools → Continue Downloaded MARS Lab reopens the project's selected edition.
Questions, feedback and graduated hints use inline links; no Find Action step is
needed. Format 1 retains conceptual predictions. Format 2 permits an ordered
sequence of predictions and observations of ordinary student Run launches.
Format 5 adds optional source files to Run and Debug activities, for example to
compare full and empty ranges without editing a previously observed program.
Format 6 adds an explicit function assessment kind, with the same source-bound
progress, instructor controls and native Check this program interaction.

## Format 1 predictions

```json
{
  "formatVersion": 1,
  "introduction": "Read the byte inspector before changing it.",
  "conclusion": "Explain both interpretations of the same eight bits.",
  "steps": [{
    "id": "unsigned-byte",
    "explanation": "The set bits contribute 128, 16, 4 and 2.",
    "question": "What is the unsigned value of 0x96?",
    "choices": [
      {"id": "a", "text": "150", "feedback": "Their sum is 150."},
      {"id": "b", "text": "-106", "feedback": "That is its signed eight-bit meaning."}
    ],
    "correctChoice": "a",
    "anchor": {"label": "interpret", "mnemonic": "andi", "occurrence": 0},
    "hints": [{"id": "place-values", "text": "Start with the highest set bit."}]
  }]
}
```

The manifest supplies identity, version, title, target and source paths. The
entire archive already has a content digest and a distinct working folder for
each edition. The native definition comes from that archive's private snapshot;
changing the selected ZIP after preparation cannot replace it.

| Element | Required fields and limits |
| --- | --- |
| Root | Exactly `formatVersion`, `introduction`, `conclusion`, `steps`; version is integer 1; 1–128 steps |
| Step | `id`, `explanation`, `question`, `choices`, `correctChoice`; optional `anchor`, `hints`; distinct step IDs |
| Choice | Exactly `id`, `text`, `feedback`; 2–6 choices with distinct IDs; `correctChoice` names one |
| Anchor | Exactly `label`, `mnemonic`, `occurrence`; assembly label up to 128 characters; lowercase mnemonic up to 32; integer occurrence 0–127 |
| Hint | Exactly `id`, `text`; zero through four hints per step, with distinct IDs and retained order |
| Text | Nonblank; question/choice text at most 2,048 characters; other prose at most 8,192 |
| ID | 1–64 ASCII characters; first a letter, then letters, digits, periods, underscores or hyphens |

Definitions use strict UTF-8 and strict JSON, at most 1 MiB of decoded content
and nesting depth 32. Duplicate fields at every depth, unknown fields and trailing
top-level content are rejected. Display text must contain valid XML characters;
the runtime escapes markup before passing it to the native renderer. The parsed
lists cannot be mutated.

Titles use the host's plain label API, so punctuation is retained literally.
An HTML-prefixed title is rejected before preview to prevent the label from
interpreting downloaded markup. Body text follows the XML message contract above.

Anchors describe instruction matches within a named label's block. The runtime
resolves the current committed PSI, requires a unique label, and counts matching
mnemonics within that block. Missing or ambiguous anchors produce no highlight.
The native highlight callback acquires read access when its timer asks for fresh
geometry. An anchor does not establish that an instruction has executed.

Each project's history is scoped by course, package ID, version and complete ZIP
digest. Correct answers are skipped when continuing that edition. Wrong attempts
and revealed hints remain in history; requesting a hint does not answer a question.
The instructor's existing hint-release policy is checked on each request.

The selected edition is persisted separately in the project's workspace. Continuing
revalidates the retained archive and its location before opening it. Definitions
come from that immutable archive, while the working source keeps student edits.
Missing working files cause a visible error rather than being silently regenerated.
The native platform caches course catalogs globally, so downloaded lessons are
opened directly through its public lesson host with a project-specific module.
They do not alter the bundled course catalog or its navigation.

Format 1 describes prediction steps and explanatory feedback. All formats accept no
action IDs, class names, executable scripts, execution predicates or grading code.
Text is data and must be escaped by its renderer. Additional step kinds require
an explicit format change rather than silently ignored fields.

## Format 2 ordered activities

The root has the same fields, with integer `formatVersion: 2`. Every step has a
`kind`: `prediction` retains the format-1 prediction fields, while `run` uses the
fields below. IDs remain unique across all activity kinds. Activities can be
interleaved; they are not separate start/end templates.

```json
{
  "kind": "run",
  "id": "run-150",
  "explanation": "Run the byte inspector with 150 and compare both results.",
  "integerInputs": [150],
  "stdout": "Enter an integer from 0 to 255: Unsigned byte: 150\nSigned byte: -106\n",
  "success": "The same low byte represents unsigned 150 and signed -106.",
  "anchor": {"label": "interpret", "mnemonic": "andi", "occurrence": 0},
  "hints": [{"id": "console", "text": "Enter the requested decimal integer in the Run console."}]
}
```

`kind`, `id`, `explanation`, `integerInputs`, `stdout` and `success` are required.
Anchors and graduated hints follow the same rules as predictions. Inputs are an
ordered list of zero through 64 exact signed 32-bit integers. Expected stdout is
XML-compatible text of at most 16,384 characters. Empty output and whitespace-only
output are valid exact transcripts, including for programs that only write to a
memory-mapped display. Success feedback and
explanations retain the 8,192-character limit. The whole definition remains bounded
by the existing 1 MiB, 128-activity and JSON-depth limits.

MARS subscribes to the native pre-start event, before process output begins. It
observes only ordinary Run for the package entry with the default assembly
options. The current contract covers a single source file and service-5 integer
input. A read-only register accessor observes completed input results on the
simulator worker; the optional SYSCALL code field does not affect recognition.
Exception entries do not count as completed input. The observer never starts a
run, supplies input, steps a machine or changes registers.

A pass requires the exact consumed integer sequence, exact stdout, no stderr,
normal exit, and the captured source matching the document revision at launch
and acceptance. Output and input history are bounded. A newer launch replaces
the earlier observation; edits, resets, disposal and late results cannot grant
credit to another attempt. Feedback distinguishes input, output, termination and
source changes. Equivalent instruction sequences are accepted when they satisfy
this public behavior contract.

Run history has a separate identity per activity and package digest. Prediction
answers and hint history survive source changes; successful Run observations do
not. Continuing rechecks each activity against its current document. The final
gate requires all Run observations to remain valid for their current source.
Completed Run controls retire with their native task, and the conclusion is
shown only when reached. These checks do not award Debug or independent-writing
assessment credit.

Formative client feedback explains material already taught in the publication.
Private instructor solutions, summative answer keys and hidden grading cases
remain in the instructor distribution. Native LaTeX remains the book's composition
authority; this runtime asset does not generate or reorder edited book prose.

## Format 3 ordered Debug observations

Integer `formatVersion: 3` retains the format-2 prediction and Run contracts and
adds `kind: "debug"`. A Debug activity requires `id`, `explanation`,
`integerInputs`, `stops` and `success`, with optional graduated `hints`. It has
one through 32 ordered stops, with distinct stop IDs. Each stop requires `id`,
`explanation`, `anchor`, `registers` and `success`; stop-specific `hints` are
optional. The existing text, ID, hint, input and archive limits apply.

```json
{
  "kind": "debug",
  "id": "inspect-extension",
  "explanation": "Launch a fresh Debug session with input 150. Follow each requested stop in order.",
  "integerInputs": [150],
  "stops": [
    {
      "id": "after-extension",
      "explanation": "Pause before the first la under report. Inspect all sixteen hexadecimal digits of t1.",
      "anchor": {"label": "report", "mnemonic": "la", "occurrence": 0},
      "registers": {"$t0": "0x0000000000000096", "$t1": "0xffffffffffffff96"},
      "success": "The sign-extended byte occupies a full 64-bit register. Its upper bits are ones."
    }
  ],
  "success": "Explain why both registers have the same low byte but different upper bits."
}
```

`registers` contains one through 32 integer registers. Register names follow the
assembler's spellings and are normalized to their canonical names. Aliases for
the same register cannot occur twice in a stop. Values are strings containing
`0x` followed by exactly sixteen hexadecimal digits, including leading zeroes.
They denote raw 64-bit patterns, not signed decimal numbers or expressions.
Floating-point, vector and system registers and `pc` are outside this contract.
The source anchor establishes the expected program counter separately.

The observer attaches to the native Debug pre-start event for the package entry
and default assembly options. It checks the captured single-file source and the
same completed service-5 inputs as Run observations. All declared input must have
been consumed before the first accepted stop. Each accepted stop requires a new
pause, the requested complete register values, and the first emitted machine
instruction of the anchored source operation. A later stop also requires actual
instruction execution since the preceding accepted stop. A second instruction
inside a multi-instruction pseudo-operation cannot count as its starting boundary.
Missing anchors, non-emitting source and ambiguous source-line expansions cannot
grant evidence.

The debugger captures the immutable instruction source map with its scalar
snapshot. A separate history revision records applied register or memory edits
and both reverse commands. Inspection, failed edits, no-op edits and forward
steps leave that revision unchanged. This initial Debug contract requires an
unmodified forward session: after an edit or backstep, students can continue
exploring, then launch a fresh Debug session to record the ordered observations.
No lesson observer adds breakpoints, supplies input, steps or edits the machine.

Only the newest eligible session can advance an active activity. Source changes,
history resets, disposal and callbacks retained from an older session cannot
award progress to the current attempt. Each stop has its own explanatory text,
source pointer and hint controls. The observer spans the lesson lifetime while
individual controls retire with their native task. Feedback distinguishes the
wrong boundary, missing anchor, wrong full-width values, wrong input, modified
execution history and changed source.

Completion is committed atomically only after every stop succeeds in one
session. Partial execution evidence is transient; continuing an incomplete
activity requires a fresh Debug sequence. Completed Debug evidence is saved
under the activity's separate identity and package digest, and is invalidated
by a source change. The final lesson gate checks both Run and Debug evidence
against the current source. These are guided intermediate-state observations;
they do not impose register names or source anchors on independent-writing
assessment.

## Format 4 independent console assessments

Integer `formatVersion: 4` retains the prediction, Run and Debug contracts and
adds `kind: "assessment"`. This activity opens a student source file and provides
an inline **Check this program** control. It requires `id`, `explanation`, `source`,
`caseProfile` and `success`, with optional graduated `hints`.

```json
{
  "kind": "assessment",
  "id": "independent-output",
  "explanation": "Write the requested program in independent/output.asm, then check its behavior.",
  "source": "independent/output.asm",
  "caseProfile": "independent/output.cases.json",
  "success": "Explain why your implementation satisfies the boundary cases."
}
```

Both paths must name exported files in the same package and satisfy its portable
relative-path rules. Case data is validated before import and read from the retained
archive. Editing an extracted case file cannot change the lesson's expectations.
Profiles shared by several activities are parsed once into an immutable list.
The host does not execute downloaded Kotlin grading scripts.

A case profile has exactly `formatVersion: 1` and `cases`. Each case has exactly
`name`, `input`, `stdout` and `instructionLimit`:

```json
{"formatVersion":1,"cases":[
  {"name":"zero","input":"0\n","stdout":"2","instructionLimit":20000},
  {"name":"positive","input":"3\n","stdout":"5","instructionLimit":20000}
]}
```

The profile must be strict UTF-8 JSON, at most 1 MiB, without duplicate keys,
unknown fields or trailing content. There must be one through 256 cases with
unique, nonblank printable names of at most 128 Unicode code points. Input and
stdout are strings of at most 64 KiB each in UTF-8; either may be empty and may
contain control characters. Each instruction limit is a positive JSON integer
of at most 1,000,000; the sum across the profile must not exceed 10,000,000.
Feedback escapes markup and displays disallowed message control characters
visibly. These bounds constrain each check; cases are formative public material.

Checking assembles the activity's current editor text with the default lesson
options, then loads the resulting image into an independent machine for each
case. The current contract requires a single source file. The existing console
grader checks stdout, errors, normal termination and the instruction bound.
It does not prescribe labels, scratch registers or an instruction sequence.
A student's ordinary Run or Debug session is unaffected. A successful result
means that these declared cases passed, rather than proving all possible inputs.

The check button requires the activity's source editor to be selected. It disables
overlapping button launches while its check is active. Background work is cancellable
and tied to the task lifetime. The source revision, progress ticket and current
instructor policy are rechecked before accepting results; edits, resets, disposal
and superseding checks cannot grant stale credit. Assembly errors, cancellation
and stale results consume no assessment attempt. Each completed case set consumes
one attempt, including a failing set. The instructor's independent-assessment
limit applies separately to each activity and package digest; zero is unlimited.

`allowFreeWriting` gates these independent activities. When `requirePredictions`
is enabled, all preceding prediction activities in this plan must be answered
correctly. Existing hint release controls apply. These controls are local
instructional policy, not a secure examination boundary. Assessment history and
valid source-bound passes survive continuation. Edits invalidate a pass while
preserving attempts and hints. The final gate checks all Run, Debug and assessment
evidence against their respective current source documents.

Format 4 needs no additional mode metadata. Each assessment can also be opened
directly through **Choose Downloaded MARS Lab Activity**. This uses the same
source file, archived cases, progress identity, prediction prerequisites, hints
and attempt limit as the guided route. Choosing an activity is navigation;
it does not establish execution evidence or reset a retry count. The project's
saved selection identifies the exact retained package edition and activity.
Continuing an independent activity does not replay the guided tasks. Returning
to guided work still checks all required source-bound observations.

### Instructor-selected prerequisites

**Tools → Configure Downloaded MARS Lab Prerequisites…** configures each
independent activity in the selected edition. It offers only earlier activities:
predictions, Run or Debug observations, and independent checks. This ordering
prevents cycles. Requirements of an earlier independent check also apply, so
requiring that check cannot bypass its current prerequisites. The global
prediction switch adds all preceding questions; access, hints and assessment
limits retain their existing meanings.

Draft choices survive switching exercises inside the dialog. Cancel leaves
saved policy unchanged. Save applies the selected edition's rules atomically in
the project's shareable `mars-learning.xml` policy, with separate entries keyed
by each assessment's full content identity. Other editions' rules remain
separate. The standard Settings page explicitly identifies the built-in first
program's named requirements; downloaded-course selection uses this dialog.

The host refreshes prerequisite files outside read access before binding their
documents, both when opening an independent task and before checking it. Current
document text establishes each prerequisite's source revision. Document and VFS
events invalidate old observations even after leaving guided work; restoring
edited text does not restore its old pass. Missing files and source changes
made while the IDE was closed cannot reuse saved execution credit. Prediction
answers stay independent of source edits.

Before accepting an assessment, the host checks the live prerequisite graph and
current evidence again. A stricter policy or prerequisite edit during grading
cannot accept a stale result or consume an attempt. Blocked checks consume no
attempt. The task and policy feedback list required activity IDs. Unknown saved
IDs or forward references block checking; the dialog explains that saving its
displayed choices repairs such entries. Cancel preserves them. These remain
formative client controls, separate from private instructor grading authority.

## Format 5 source-specific Run and Debug activities

Use integer `formatVersion: 5` for a lesson whose guided execution activities
need different files. All format-4 activities remain available. A `run` or
`debug` activity may add a `source` field, for example
`"source": "ranges/empty.asm"`. Without it, execution continues to use the
manifest's `entry`. Formats 1–4 reject this new field. Independent assessments
retain their required `source` field; predictions remain anchored to the entry.

Each source is a nonblank relative archive path of at most 240 characters and
must follow the package's existing safe-path rules. Archive preparation rejects
a missing source before presenting the import preview. Definitions remain in the
retained immutable archive, while each installed source file remains editable.

When the next activity uses a different file, the lesson waits for IntelliJ's
file-opening operation to finish before attaching its Run or Debug observer.
Source pointers resolve against that activity's file. Returning to a prediction
returns to the entry file. File opening neither executes the program nor credits
an observation.

Run and Debug results retain separate activity identities and bind to the actual
file's current revision. Launching another file cannot satisfy the activity,
even if it prints the same output. Instructor prerequisites use the same file
binding. Editing a full-range exercise invalidates that exercise's observations;
an unchanged empty-range exercise retains its own evidence. Restoring old text
still requires fresh execution after an edit.

An empty console transcript proves no console output, not correct display
writes. Use independent memory/store/read checks where the learning objective
depends on a display-port sequence or the absence of an out-of-range access.

## Format 6 function assessments

Use integer `formatVersion: 6` to add `"checkKind": "function"` to an assessment.
All existing activities and source-selection rules remain available. An absent
`checkKind` retains console checking; explicit `"console"` also selects it.
Formats 1–5 reject the new field. Unknown kinds and crossed profile schemas are
rejected before the import preview. No kind can name an executable script,
expression, action or implementation class.

```json
{
  "kind": "assessment",
  "id": "reverse-and-invert",
  "explanation": "Write a function that returns through its incoming continuation.",
  "source": "independent/reverse-and-invert.asm",
  "caseProfile": "independent/reverse-and-invert.cases.json",
  "checkKind": "function",
  "success": "Explain which state survived the helper call."
}
```

A function profile has its own integer `formatVersion: 1` and a `cases` array.
Each case requires `name`, `instructionLimit` and `calls`. Optional fields are
`initialRegisters`, `preservedRegisters`, `helpers`, `stackAlignment` and
`callerStackBytes`. A call requires exactly `entry`, `returnTo`, `arguments` and
`expectedRegisters`; a helper interval requires exactly `from` and `until`.

```json
{
  "formatVersion": 1,
  "cases": [{
    "name": "one function call",
    "instructionLimit": 1000,
    "initialRegisters": {"s0": "0x1122334455667788"},
    "preservedRegisters": ["s0"],
    "stackAlignment": 16,
    "callerStackBytes": 32,
    "helpers": [{"from": "reverse_byte", "until": "reverse_byte_return"}],
    "calls": [{
      "entry": "reverse_and_invert",
      "returnTo": "after_invert",
      "arguments": {"a0": "0x8000000000000016"},
      "expectedRegisters": {"v0": "0x0000000000000097"}
    }]
  }]
}
```

Register maps contain exactly sixteen hexadecimal digits after lowercase `0x`,
including the high bit when set. Values represent register bits, not floating
point JSON numbers. Names resolve through the core's integer-register bank,
with or without the leading dollar sign. Aliases for one register cannot be
declared twice. Expected registers are nonempty; arguments may be empty.
Initial values cannot write zero, and arguments cannot replace the stack or
return pointer. Labels follow the public assembly-label grammar and are at most
128 characters. The default initial map, preservation list and helper list are
empty; authors must declare the preservation and helper obligations they intend
to check.

Profiles are strict UTF-8/JSON, at most 1 MiB and depth 32. Duplicate keys, unknown
fields, coercions, nonprintable names and oversized containers are rejected.
Names are nonblank and at most 128 UTF-16 code units. All integer control values
must use exact nonnegative JSON integer syntax. A profile admits 1–256 unique
case names, 1–512 calls per case, at most 4096 total calls, a positive per-case
budget through 1,000,000 instructions, and at most 10,000,000 instructions overall.
Register maps and preservation lists admit at most 32 distinct registers; helpers
admit at most 32 unique intervals. Alignment defaults to 16 and must be a power
of two through 4096. Caller-byte observation defaults to 32 and permits 0–65536.

Preparation parses retained archive data into immutable collections. Changing a
working profile does not change the imported checks. Execution uses
[`PreparedFunctionChecks`](prepared-function-checks.md) and a fresh detached
machine per case. Repeated calls within that case share memory and a cumulative
budget. Caller setup aligns the pointer before calling, then each invocation must
return to its requested continuation with its expected results and incoming
preserved values. Helper intervals count actual completed instructions. Caller
bytes are compared after each return; this does not claim an intermediate write
history. Execution is silent and uses the existing restricted grading environment.

The existing Check this program control, source/edition identity, attempt limits,
hints, prerequisite graph, cancellation and stale-result checks remain in force.
Unsaved source text is assembled through the same build service. A failed call
produces an ordinary named case failure; malformed content fails import validation.
Assembly failure, cancellation and stale results do not earn credit or consume an
assessment. Source edits invalidate a previous function pass. No active student
Run or Debug machine is used or modified by grading.

### Function-case profile version 2: data pointers and memory results

Native lesson format 6 also accepts a function-case profile with integer
`formatVersion: 2`. Version 1 retains its exact field set. Version 2 adds optional
`initialMemory` to each case and optional `argumentAddresses` and `memory` to each
call. Existing function controls, source binding, budgets and preservation rules
continue to apply.

```json
{
  "formatVersion": 2,
  "cases": [{
    "name": "overwrite prefilled pixels",
    "instructionLimit": 10000,
    "initialMemory": [{"label": "frame", "bytes": "5a5a5a5a5a5a5a5a"}],
    "calls": [{
      "entry": "render",
      "returnTo": "after_render",
      "arguments": {},
      "argumentAddresses": {"a0": {"label": "source"}, "a1": {"label": "frame"}},
      "expectedRegisters": {},
      "memory": [{"label": "frame", "bytes": "00000000ffffff00"}]
    }]
  }]
}
```

An address object contains required `label` and optional `offset`. A memory range
contains required `label` and `bytes`, and optional `offset`. Offsets use exact
signed JSON integer syntax, with zero written `0`; their magnitude is at most
1048576. Each byte string is nonempty even-length hexadecimal text, in increasing
address order. Numerical words must be encoded for the selected image byte order.
The default offset is zero. Lists and maps default empty when omitted.

Address arguments have at most 32 unique integer registers, combined with scalar
arguments. The same register cannot appear in both forms, including through an
alias. Memory may supply a function's result when `expectedRegisters` is empty;
each call must declare a register or memory expectation. Setup and per-call
memory lists each admit at most 64 ranges, and their combined byte count over all
calls is at most one MiB per case. Setup occurs once, in declared order, before
calling; comparisons occur after every requested return. Memory is retained
between calls. These are byte comparisons, not store-history assertions.

Labels resolve from the assembled submission rather than fixed addresses. Public
fixture labels can move with their allocations, while private implementation
labels, scratch registers and frame layouts remain unrestricted. Missing or
invalid submitted data labels fail a named case; malformed profile shapes fail
validation before preview. Version-1 profiles reject all three new fields.
