# Nullability and lifecycle audit

The complete remaining assertion inventory is in
[`kotlin-nullability-remaining.json`](kotlin-nullability-remaining.json). It records
tracked production Kotlin files, assertion counts, and line numbers after phase 8.
Regenerate it with `python3 tools/audit-kotlin-idiomaticity.py --non-null-inventory`.
Counts are textual, as in the original audit; a lower count alone does not prove
that a lifecycle is correct.

## Changes at lifecycle boundaries

| File | Before phase 8 | After phase 8 | Change |
| --- | ---: | ---: | --- |
| MIPSprogram | 4 | 0 | Validate prepared source at the assembler boundary; keep nullable source lines coherent; handle absent executable lists in metadata queries. |
| ProgramStatement | 17 | 7 | Non-null private operand storage, with an empty invalid-word representation and the original nullable public getter; validate image capture before constructing it. |
| Assembler | 3 | 0 | Use the validated source snapshot when publishing metadata. |
| FirstPassProcessor | 17 | 6 | Retain current source, symbol table, parsed statements, and macro pool for the pass instead of retrieving nullable program fields repeatedly. |
| Parser | 4 | 1 | Reuse the first pass's prepared source and macro pool. |
| Tokenizer | 6 | 0 | Include frames capture the required source and filename once; source locations remain optional when provenance is absent. |
| MarsDebugProcess | 7 | 0 | Capture a typed execution containing machine, program, and loaded symbols at launch; retain it through breakpoint binding and suspensions. |

Nullable preparation properties and the public fluent APIs remain available.
Snapshots retain the existing mutable objects used by each pass; they do not copy
or freeze in-progress assembly. Session ownership and stopped-state claims remain
with the existing simulator mechanisms.

Invalid instructions continue to expose `null` operands through `getOperands()`.
They cannot be captured as executable image entries. Valid instruction images still
copy operands, and each image load still owns its own operand array. Source-line
clearing now clears both physical and written source views, consistent with the
nullable public setter. Tests cover preparation order and both of these boundaries.

## Retained assertions and follow-up work

The remaining 219 assertions were inventoried across production sources. They
are retained for the reasons below; this is not a claim that each precondition is
an architectural ideal or that every unusual platform race has been proved safe.

- **Core instruction and register contracts.** Catalog `nop` lookup, architecturally
  required registers, validated operand matches, and symbol provenance depend on
  earlier validation. Replacing these with silent skips would hide simulator or
  assembler defects. Existing instruction, exception, and backstep tests retain
  those failure boundaries.
- **Assembly stages and macros.** Some public statement fields remain nullable
  because decoded, source-backed, and detached statements share a compatibility
  API. Macro bodies similarly retain an active-definition invariant. A future
  separation into parsed, resolved, encoded, and decoded types should migrate
  these together, with explicit compatibility methods. Mutable `machineStatement`
  is also publicly overridable; rewriting its update order solely to remove two
  assertions could change subclass-visible behavior.
- **Device attachment and checkpoints.** Device observers retain memory/port and
  restore-checkpoint assumptions while attached or restoring. Converting these to
  typed attached/detached states is useful follow-up work that should include
  connection, teardown, and backstep tests as one change.
- **UI selection and validated dialogs.** Tool controls and table actions use
  enabled-state or successful-validation preconditions. A typed selected-execution
  state would express those relationships more directly, especially for stale
  queued actions. This pass leaves their event/disposal behavior intact.
- **PSI, VFS, and refactoring.** Most remaining plugin assertions connect validated
  offsets, committed documents, captured revisions, resolved declarations, and
  write-command targets. A blanket fallback would risk partial renames or missing
  diagnostics. Stronger operation-specific snapshots should be introduced with
  cancellation and file-change tests, rather than by changing all failure paths
  in an idiomaticity sweep.
- **Resources and diagnostics.** Bundled resource availability, known plugin
  metadata, explicitly constructed diagnostic messages, and matching map entries
  retain local preconditions. Device and instructional state snapshots also have
  guarded optional fields. These can be given more explicit types incrementally.

The debugger now handles an edit failure without a message by presenting its
exception description; it no longer assumes every implementation of the edit
request interface supplies a message. Errors remain reported on the IDE thread,
and failed edits do not advance execution or its revision.

No simulator accuracy rule, instruction encoding, delayed branch rule, forbidden
slot rule, reservation behavior, or backstep algorithm was changed in this phase.
