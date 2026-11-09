# MARS plugin implementation progress

The active goal is full MARS functionality inside IntelliJ, reducing reliance on Venus through tested,
committed steps. The Java engine remains independent. New plugin code is Kotlin. IntelliJ 2026.3
source contracts guide parser, PSI, indexing, worker, UI and lifecycle choices.

## Completed foundations

- `c7d40ff`: Jupiter IDE fixtures verify real project service registration and isolation. Plugin tests
  use the pinned IDE's JUnit 6.1.3 fixture dependencies and the Jupiter engine only.
- `8787d1a`: lossless Kotlin scanner, including line endings, escapes, character literals, exponent
  signs, address arithmetic, every token boundary and incremental restart slices. All scanner lines
  and branches are covered.

## Native parser integration

`src/main/grammar/Mips.bnf` is the parser source. Grammar-Kit 2023.3.4 generates Kotlin parser/type
code against IntelliJ's `syntax-api`. Generated sources live in the build directory.
No generated Java or generated PSI is shipped. Kotlin PSI adapters use the platform's
`SyntaxGrammarKitFileElementType`, `GrammarKitLanguageDefinition`, `LexerAdapter`, and
`elementTypeConverterOf`. There is no parallel MARS syntax-tree framework.

The grammar handles labels, instructions, operands, registers, parameters, literals, addition and
subtraction, parenthesized/address expressions, repeated data, continued data syntax, generic
assembler directives, `.eqv` replacement text, macro definitions and MARS/SPIM invocation forms.
Semantic validation and build-context resolution remain separate work. Parsing a construct does
not assert that its registers, argument types, symbols or segment usage are semantically valid.

Real PSI tests cover every canonical basic/pseudo-instruction example, every program in the core
assembly corpus, directive/macro variants, malformed lines, truncated input, native syntax entry
points, file associations, lexer restoration and highlighting.

### Build-tool detail

Grammar-Kit's released command-line entry point always creates its Java generator. The build-only
`parserGenerator` source set calls its official `KotlinParserGenerator` instead. Its bootstrap uses
Grammar-Kit's package because the library's AST factory is package-private. This entry point and
Grammar-Kit dependencies are not part of the plugin distribution. Generation checks reject Java
output and malformed BNF.

### Coverage

Authored runtime code retains 100% line and branch gates. Raw JaCoCo reports include generated parser
code as well. The generated parser contains mechanically unreachable lookahead/pinning outcomes and
platform recursion/progress guards. Their exact source lines, maximum missed outcomes, and reasons
are recorded in `plugin/coverage/generated-parser-branches.tsv` with a source checksum.
The verification task fails on uncovered generated lines, a changed generator output, or any
unreviewed missed branch. Grammar alternatives and error recovery remain tested; the generated
package is not removed from the raw report. This is the narrow defensive-branch policy from the plan.

## Launch protocol

From `mars5`, use `./gradlew clean cleanSandbox runIde` for every sandbox launch. There are no aliases.
The launch depends on plugin/core/CLI/desktop checks and the architectural guard. Sandbox preparation
runs after cleaning when both tasks are requested. Use a separate smoke project to avoid disturbing
the development checkout. Record startup/log/UI evidence before calling the integration accepted.

The first clean launch passed all 1,233 tests and coverage checks. The IDE log confirmed that
`MARS MIPS` loaded in the separate `/tmp/mars-plugin-smoke` project without a plugin error.
The UI automation could not attach to the sandbox Java window. Visual editor acceptance remains
open; log evidence does not replace it. The test process was stopped without closing the development IDE.

## Native editor actions

MARS registers `Commenter` and `PairedBraceMatcher` implementations, following the TOML and
EditorConfig examples in the reference checkout. IntelliJ owns comment toggling, parenthesis
insertion, closing-parenthesis skipping, paired deletion and brace matching. There is no MARS
typing handler or replacement editor action.

Five Jupiter tests use the platform's `codeInsightProjectFixture` and `codeInsightFixture`.
They exercise real editor actions, selected lines, existing comments, hashes inside strings,
parenthesis typing/deletion, nested matching, token registration and the extension contracts.
Authored plugin code retains 100% line and branch coverage.

The second `clean cleanSandbox runIde` launch passed all 1,238 tests and loaded the packaged
plugin without a plugin error. The visual acceptance limit above still applies.

## Native declaration index

`MipsDeclarationIndex` follows the reference checkout's `EditorConfigIdentifierIndex` pattern.
It is a registered `FileBasedIndexExtension` with a MARS file filter, native string keys and
a value externalizer. IntelliJ owns storage, scheduling, invalidation, document overlays and query
scopes. The producer reads only `FileContent.psiFile`. It never resolves a symbol or queries an index.

The values record syntactic label, macro and constant candidates. Each value contains its name
offset and enclosing macro offset. Duplicate declarations remain distinct. These facts do not
claim that a declaration is valid or visible in an assembly context. References and include
sentinels will join these facts when queries have the relevant build context.

Four Jupiter tests cover actual index registration and queries, every declaration kind, duplicate
names, macro ownership, malformed declarations, comments/strings, `.asm`/`.s` filtering, empty
contributions, value serialization, unsaved document edits, deletion and native file scopes.
The index has 100% line and branch coverage. The clean launch passed all 1,242 tests. The IDE log
confirmed that `mars.declarations` initialized and the plugin loaded without a plugin error.
The visual acceptance limit above still applies.

## Shared include index

`MipsIncludeProvider` contributes unresolved paths to IntelliJ's existing `fileIncludes` index.
It uses the current `acceptFile(IndexedFile)` contract. There is no additional MARS include store.
Extraction uses the native MARS lexer and follows the assembler's include prepass: first include
per line, case variations, optional comma separators, macro/replacement content and raw quoted paths.

Four Jupiter tests cover extraction, malformed input, native relative/absolute resolution, spaces,
Unicode, dot segments, reverse lookups, cycle-safe traversal, newly created targets and unsaved edits.
The producer has 100% line and branch coverage. The clean launch passed 1,245 tests and loaded the
plugin without a plugin error. The final path compatibility test then passed with all 47 plugin tests,
bringing the verified total to 1,246. The distribution was rebuilt successfully.

The pinned platform's cached forward include graph can retain an empty result after a missing target
is created. Tests verify fresh native resolution and reverse lookup for that case. The component plan
requires unresolved path sentinels and VFS structure dependencies for future semantic caches.
Production query consumers must follow that rule. Visual acceptance remains open as described above.

## Include navigation and refactoring

The native AST factory creates a PSI leaf for MARS string tokens. Only strings selected by the
assembler's include prepass expose file references. IntelliJ supplies caret navigation, filename
completion, usage search, file and directory rename, and move handling through `FileReferenceSet`
and the registered element manipulator. The shared include provider uses those same references
when resolving its indexed paths. There is no parallel reference or refactoring engine.

MARS paths retain spaces and question marks as literal filename characters. Relative paths use the
source directory. Absolute paths use the filesystem root, including when a native move rewrites a
reference. The PSI cache stores path text by offset; it does not cache resolved targets. A reference
to a missing file resolves after the target is created, even without an edit to the source.

Eight new Jupiter editor fixtures cover the navigation, completion, refactoring, raw path, missing
target, and quote preservation contracts. The shared-index absolute path test has no relative alias
that could conceal a failed absolute lookup. All 1,254 tests passed in the clean launch and authored
runtime line and branch coverage remains 100%. The IDE loaded the plugin and opened the smoke
project without a plugin error. Visual editor acceptance remains open as described above.

## Macro boundaries and directive case

The lexer recognizes macro and equivalence keywords case-insensitively and preserves the source
spelling in PSI. The parser accepts physical files containing only one macro boundary, including
an opening directive at end of file. Macro and equivalence keywords inside `.eqv` replacement
text remain replacement tokens. The declaration index version advances so existing projects
rebuild their candidates. Its macro offsets describe physical PSI nesting before expansion.

Three fixtures use the Java assembler to verify the emitted instruction and the IntelliJ parser
to verify each physical file. They cover mixed case, both directions of split include boundaries,
and `.eqv` aliases that introduce macro and equivalence directives. Build-context analysis owns
diagnostics for unmatched macro boundaries after include and equivalence expansion.
The clean sandbox launch ran all 1,257 tests, loaded the plugin, and opened the smoke project.
The installable distribution was rebuilt after startup verification.

## Macro parameter spellings

Macro signatures preserve parentheses and commas as tokens. The assembler ignores those tokens
when collecting formal parameters, so the grammar accepts them in any order. The scanner keeps
punctuation inside percent-prefixed names and follows the assembler's signed exponent token rule.
It preserves exact spans when restarted inside a bounded source slice. Both declaration and include
index versions advance because the same lexical change affects their stored facts.

The assembler and PSI fixtures verify `%value!`, `%value-7`, and SPIM-style `$value`. The include
fixture verifies that `.include` inside a percent-prefixed token does not create an include path.
The clean sandbox launch ran 1,259 tests and opened the smoke project with the plugin loaded.
The rebuilt distribution passed its ZIP integrity check. Visual editor acceptance remains open.

## Raw macro arguments

The lexer recognizes instruction and directive names from the Java core catalogs. Known
instructions retain structured operand PSI. Other operation-shaped lines preserve their token
sequence in `OPERATION_CANDIDATE`, including punctuation that the assembler can pass to macros.
This also preserves the source of dotted macro names, equivalence uses, and symbolic data
continuations until analysis has the assembly context. Mnemonic spellings remain valid label
tokens, and the declaration index rebuilds those entries after upgrade.

The assembler and PSI fixtures cover standalone plus and colon tokens, quoted strings, mnemonic
arguments, unmatched argument parentheses, dotted and dollar-prefixed names, and operations
supplied by macro parameters. The instruction corpus checks that every canonical instruction
example still produces structured instruction PSI.
The clean sandbox launch ran 1,261 tests and opened the smoke project with the plugin loaded.
The rebuilt plugin archive passed its integrity check. Visual editor acceptance remains open.

## Operands completed by substitution

The grammar prefers a complete structured operand list. If that parse cannot consume the line
and the line contains a symbol or parameter that can expand, the parser preserves an
`OPERAND_FRAGMENT`. Lines without an expandable token keep ordinary syntax recovery. The lexer
uses the core register catalog to distinguish dollar-prefixed identifiers from registers, and the
declaration index version advances to rebuild affected label and macro candidates.

Assembler-backed fixtures verify equivalent load instructions when `.eqv`, a percent parameter,
or a SPIM parameter supplies a parenthesis. The fixtures check that ordinary addresses still
produce structured address PSI and that the fragments retain their exact operand text.
The clean sandbox launch ran 1,262 tests and loaded the plugin in the smoke project. The rebuilt
plugin archive passed its integrity check. Visual editor acceptance remains open.

## Whole-line include preprocessing

The parser recognizes an `INCLUDE_LINE` when the assembler's include prepass would replace the
physical line. It preserves the full token sequence and avoids producing declaration or instruction
nodes for the replaced prefix. A valid include on a macro closing line takes precedence over the
physical closing token. Incomplete include directives still have ordinary directive PSI.

The assembler fixtures cover labels, equivalences, incomplete operands, an earlier include without
a quoted path, and a macro body inserted in place of a closing line. The declaration index rebuilds
to drop labels and constants from replaced lines. Editor highlighting and include indexing use the
same lexer token for the directive.
The clean launch ran all 1,264 tests and loaded the plugin in the smoke project. The release ZIP
passed its integrity check, and all six packaged JARs matched the sandbox JARs by SHA-256.
The window was unavailable to UI automation, so visual editor acceptance remains open.

## Native symbol reference index

The `mars.references` file-based index records source offsets, physical macro nesting, and
possible target kinds. It reads the indexed file's PSI and emits facts even when the declaration
does not exist. Ordinary operands can refer to labels or equivalences. Operation and expansion
tokens can also name macros. Mnemonics in operand positions can name labels through the core's
operand conversion rules. Include replacement lines, register names, literals, and comments
contribute no symbol sites.

Native fixtures verify index registration, source offsets, candidate kinds, file scopes,
unsaved edits, declaration creation, reference deletion, and value serialization.

## Scoped native symbol queries

The project analysis service queries both native indexes by one name in the supplied search scope.
It selects the requested symbol kind and returns detached file URLs, token offsets, and physical
macro offsets. IntelliJ waits for smart mode and committed documents before reading both sides
in one cancellable read action. The same query returns declaration and incoming reference
candidates without changing the background analysis snapshot.

Fixtures cover same-name labels, macros, and equivalences, explicit file scopes, foreign project
rejection, unsaved text, and waiting or cancelling during indexing. Actual reference binding still
requires the effective source order, include expansion, and macro scope of a build unit.

The clean IntelliJ 2026.3 launch passed 1,270 tests, including 71 plugin tests, and
the authored plugin code covered all 562 lines and 339 branch outcomes. The startup
log confirmed that MARS MIPS loaded and the smoke project opened. All six JARs in
the verified distribution ZIP matched the launched plugin by SHA-256. Visual editor
acceptance remains open.

## Go to Symbol

IntelliJ's `gotoSymbolContributor` exposes MARS labels, macros, and equivalences in
Go to Symbol. It uses the declaration index for names and exact offsets, then checks
each offset against the current named PSI before returning it. Native PSI navigation
opens the name token. Duplicate declarations remain separate results, and each result
shows a kind-specific icon and its source filename. The declaration index and navigation share the
same PSI name extraction.

Jupiter IDE fixtures exercise the registered extension, exact and scoped search,
duplicate declarations, incomplete declarations, stale offsets, early termination,
unsaved edits, and file deletion. The IDE navigation descriptors point to the name
tokens for each declaration kind. Rename requires build-context reference binding.

The clean IntelliJ 2026.3 launch passed 1,272 tests, including 73 plugin tests,
and the authored plugin code covered all 591 lines and 360 branch outcomes.
IntelliJ loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256. Visual verification
remains open because the window controls could not attach to the temporary IDE.

## Captured source assembly

The Java `MarsAssembler` API accepts an immutable `AssemblySourceSnapshot` with
ordered entries and captured text keyed by normalized absolute paths. The same
tokenizer and assembler handle captured and disk sources. Snapshot assembly reads
the handler and nested includes from the capture, preserves diagnostic paths and
line numbers, and detects cycles through aliased include paths.

Core fixtures run a program assembled from a captured handler, ordered entries,
and nested includes, including text that differs from the existing disk file.
They also verify missing captures, mixed line endings, nested diagnostics, and
the existing disk entry point.

IntelliJ captures the transitive source set in a cancellable read action through
`VirtualFileManager` and `FileDocumentManager`. It uses the Java assembler's
include prepass on the captured text, including dependencies with other file
extensions. It records each document's revision with the text. Unavailable
dependencies are left to the assembler to diagnose at the include location.
The IDE fixtures verify uncommitted and unsaved text, edits after capture,
literal paths with spaces and non-ASCII characters, cycles, and assembly and
execution of the earlier capture.

The clean IntelliJ 2026.3 launch passed 1,277 tests, including 75 plugin tests,
and authored plugin code covered all 620 lines and 378 branch outcomes.
The startup log confirmed that MARS MIPS loaded and opened the smoke project.
All six JARs in the distribution ZIP matched the launched plugin by SHA-256.

## Assemble Current MIPS File

The Build menu action captures the selected local MIPS file and its transitive
includes. A project-owned coroutine assembles that capture in a fresh core
session and reports one start and one terminal event through IntelliJ Build View.
The implementation uses the pinned platform's experimental `BuildProgress`
lifecycle and public diagnostic event builders. It converts the assembler's
one-based positions to editor offsets, checks the captured document revision,
and uses an IntelliJ range marker for each clickable diagnostic. Stale or missing
positions stay visible as messages.

Jupiter IDE fixtures invoke the registered action with an unsaved include,
verify an exact Unicode and tab-aware diagnostic offset, and check success,
failure, cancellation, and isolation from the current simulation program.
The build coroutine supplies its cancellation check to the Java core for source
loading, include and equivalence processing, macro expansion, and both assembly
passes. Core tests cancel at each checkpoint visited by a representative build
and verify that an interrupted assembly publishes no program and releases its
session. An IntelliJ fixture cancels on the first write of a repeated data
directive and verifies that the next word stays untouched.

The clean IntelliJ 2026.3 launch passed 1,283 tests, including 80 plugin tests,
and authored plugin code covered all 690 lines and 424 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256. Visual verification
remains open.

## Chained equivalence expansion

The Java tokenizer expands `.eqv` replacements iteratively and re-tokenizes
the substituted source. This preserves replacements that supply punctuation,
mnemonics, or new directives. The expansion tracks aliases active within the
current replacement, so repeated aliases in separate operands expand normally.
Circular expansions report the alias chain and the original token's file, line,
and column. An unused circular definition remains valid source syntax.

Core fixtures cover a 6,000-link chain, repeated aliases inside one replacement,
and cycles in included source after an earlier alias changes the line length.
An IntelliJ fixture exercises the same cycle in an unsaved include through the
Build action and checks that its diagnostic opens the original alias token.

The clean IntelliJ 2026.3 launch passed 1,286 tests, including 81 plugin tests,
and authored plugin code covered all 690 lines and 424 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256.

## Equivalence character literals

Tokenizer tokens retain the source end position alongside the value the
assembler uses. Equivalence replacement slices the original source span before
re-tokenizing, so ordinary, escaped, octal, and non-ASCII character literals
keep their full spelling. The core fixture checks the resulting data and the
expanded source text. The IDE fixture checks the literal's PSI and the emitted
instruction.

## Macro substitution positions

Macro expansion uses the token spans from the equivalence-processed body and
replaces parameters and local labels from right to left. A quoted string or
comment can contain the same spelling as a parameter or label without becoming
a substitution. Parameters introduced by an equivalence can be substituted too.

The core fixture checks repeated and overlapping parameter names, quoted text,
and a local label passed to a nested macro. The IDE fixture parses the original
macro and checks the resulting data values and string bytes.

The clean IntelliJ 2026.3 launch passed 1,290 tests, including 83 plugin tests,
and authored plugin code covered all 690 lines and 424 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256.

## Comma separators

MARS accepts commas wherever it accepts a token delimiter. IntelliJ's syntax
builder now skips them during grammar matching while the AST keeps distinct
punctuation leaves and exact source text. A predicate on infix and postfix
operators keeps a comma-delimited operand separate, including signed data
values and parenthesized operands.

Assembler and PSI fixtures cover leading, repeated, trailing, and standalone
commas in directives, macro signatures and invocations, instruction operands,
and labels. The declaration and reference indexes rebuild with the updated
grammar. Index fixtures verify source offsets through comma-heavy lines and an
unsaved edit. The generated parser coverage ledger was reviewed for the new
grammar output.

The clean IntelliJ 2026.3 launch passed 1,291 tests, including 84 plugin tests,
and authored plugin code covered all 696 lines and 430 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256.

## Next slices

Continue checking parser edge cases against the Java assembler while building
completion, build-context reference binding, documentation, and native build
actions and diagnostic reporting. Execution/debugger/tool work follows the component mapping. No existing
MARS feature is deferred by this delivery order.
