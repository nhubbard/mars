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

Format 1 describes prediction steps and explanatory feedback. Both formats accept no
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
nonblank XML-compatible text of at most 16,384 characters. Success feedback and
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
