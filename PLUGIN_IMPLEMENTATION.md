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

The editor colors instruction mnemonics from the parsed operation, including built-in and
pseudo-instructions. A label or operand with the same spelling retains its ordinary styling.
An operation-head alias receives the mnemonic style when its expansion supplies an instruction.
The mnemonic style is editable under **Editor → Color Scheme → MARS MIPS**.

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

## Mnemonic and directive completion

IntelliJ's basic completion uses the generated parser's statement-head PSI and the Java core's
instruction and directive catalogs. Suggestions include each mnemonic once, with an example
operand list, and directives with their catalog descriptions. Matching is case-insensitive.
Tab replaces the full mnemonic, including dots, while IntelliJ owns the lookup and insertion.

The contributor uses no indexes and caches only catalog text. It creates new lookup elements
per request because IntelliJ attaches editor and project state to them. Native Jupiter fixtures
exercise suggestions and insertion after labels and inside macros, and verify that the catalog
stays out of operands, declarations, strings and comments.

## Instruction register completion

Register completion uses instruction PSI or an expanded operation candidate, together with
the core's tokenized operand formats.
The assembler tokenizer counts the tokens before the caret, including address parentheses,
and handles whitespace, optional commas and signed offsets. Candidate formats must agree
with the address punctuation already typed. Completion offers canonical integer names and
numbers, floating-point registers, or the implemented CP0 register numbers for the second
operand of `mfc0` and `mtc0`. The spelling lists come from the Java register banks.

The include-ordered source cache supplies visible equivalences for operation candidates.
Label navigation and completion count tokens in the expanded prefix, including operands
supplied by an alias. Go to Declaration on the alias itself follows its equivalence definition.
Native fixtures cover basic and pseudo formats, omitted and symbolic address offsets,
CP0 and CP1 transfers, aliased instructions, non-register operands, and register insertion.

## Instruction label completion

The operand contributor also offers case-sensitive label names when a matching core format
expects an identifier. The project analysis service reads per-file declarations from IntelliJ's
file index for the current source file and reachable indexed MIPS includes. It shares cached
include paths and native file reference resolution with include navigation, and visits each file
once per request. It reads the index only after indexing is ready and never scans project-wide
symbol keys. Completion accepts forward labels and committed unsaved edits.

This query uses the current file as the entry and filters by physical macro scope. Separate
build entries, macro expansion that changes the physical scope, and labels in includes without
the MARS file type require build-context binding. Fixtures exercise nested and cyclic includes,
an unrelated export, an unresolved include, unsaved label edits, macro-local visibility,
indexing mode and Tab replacement of dotted names.

## Instruction Parameter Info

The native Parameter Info action lists distinct basic and pseudo-instruction forms from the
Java catalog at an instruction's operands. It uses the core token spans to highlight the
current operand token, including a base register inside parentheses. The popup follows
whitespace-separated operands, repeated commas, signs, and incomplete address syntax.
For an instruction supplied by an equivalence, the handler uses the include-ordered source
cache and highlights the next operand after any tokens supplied by the alias. IntelliJ owns
the popup and caret updates. When an edit changes the expanded mnemonic, the popup rejects
its old instruction forms.

Jupiter fixtures invoke the registered editor action and inspect the rendered popup for
overloaded instructions, missing operands, aliases that supply address punctuation, and
positions outside instruction operands. An update fixture covers unsaved include edits.

## Quick Documentation

Native documentation targets show the Java catalog's instruction forms and descriptions
and its directive descriptions. The editor provider checks the keyword's PSI role, so a
label or operand that shares a mnemonic spelling keeps its own meaning. The lookup provider
uses the same immutable catalog entries for completion documentation. Only lookup elements
are created per invocation. The documentation pointers retain no file or editor state, and
the presentation uses the platform's documentation markup with escaped catalog text.

Jupiter fixtures use IntelliJ's documentation service to resolve source and lookup targets,
render their content, and verify that comments, strings, labels and unrelated lookup items
stay outside the catalog provider.

## Go to Declaration for instruction labels

Native polyvariant PSI references bind directly written label operands using the same
instruction formats and indexed include scope as completion. The resolver rechecks the
indexed name and offset against current PSI. Macro-local labels take precedence, including
labels introduced by a nested include inside the macro. Duplicate candidates remain separate
navigation targets. References are soft while build-context binding is still in progress.

The resolver prioritizes visible equivalence definitions and skips SPIM macro parameters
when looking for a direct label. Full expansion and multi-entry build binding remain separate from this physical
scope query. The IDE fixtures check native target lookup, case sensitivity, nested includes,
macro shadowing, duplicate declarations, and non-label operands.

## Go to Declaration for equivalences

Symbol references navigate to the first valid case-sensitive `.eqv` definition in the source
prefix after expanding physical includes in order. A visible equivalence takes precedence over
a same-named label. Supported use sites
include immediates, segment addresses, operation aliases, macro arguments, and partial
operands. Replacement text retains its deferred meaning until an alias is expanded.

The Java tokenizer reports definitions created by expansion, such as `DEFINE LIMIT 7` after
`.eqv DEFINE .eqv`. Navigation to `LIMIT` opens the written `DEFINE` token. The per-file
indexes retain physical declarations. An entry-scoped PSI cache shares expanded facts between
equivalence, macro, and data queries. It records the first effective definition by name and
locates the caret in include order by binary search. Changes to contributing PSI or the VFS
structure invalidate the cache. The selected build entry supplies context for included
editors. Native fixtures cover definition order, malformed and duplicate definitions,
include cycles, an include appearing after lookup, and unsaved edits.

## Go to Declaration for macro calls

Direct macro calls resolve by case-sensitive name and token argument count. The plugin uses
the Java tokenizer and shares the assembler's rule for removing one outer pair of call
parentheses. Navigation selects the latest matching definition that closed before the call.
Macro boundary facts are cached on each file's PSI and combined with physical includes in
source order, so a macro can open and close in different files. Missing and cyclic includes
leave the editor query usable while the assembler reports source errors.

The query supplies visible physical and generated `.eqv` definitions from the entry prefix
to the Java tokenizer before counting arguments. The tokenizer preserves replacement source
text, retokenizes chained substitutions and reports cycles. This covers aliases that add tokens
or supply the outer call parentheses. Calls in macro bodies still require expansion context.
IDE fixtures exercise redefinitions, arity,
case, incomplete definitions, raw call arguments, nested includes, and the indexing guard.
An assembler-backed fixture compares the selected overload with the emitted instruction.

Macro headers preserve aliases that supply the name, the formal list, or its parentheses.
The editor uses the Java tokenizer to expand definitions visible before the header,
including definitions from earlier includes. Calls use the expanded name and arity
while navigating to the written header. Header aliases link to their effective `.eqv`
definitions. A use of an expanded formal opens the header because the expanded token
column may have no corresponding source token.

## Go to Declaration for macro parameters

The same include-ordered boundary traversal finds the active macro for `%name` and SPIM
`$name` uses. The cached header keeps the assembler's exact formal parameter tokens,
so navigation selects the first matching spelling and opens its precise source offset.
Parameter uses are distinct from header declarations and quoted or commented text.
An undeclared SPIM parameter in an active macro does not bind to an outer label.

The current file is the entry for this query, including a header supplied by an earlier
include. Resolving a body edited directly in a separate included file still needs selection
of the containing build context. Fixtures cover both parameter spellings, duplicate names,
address and operation uses, include boundaries, case, and indexing mode.

Parameters can also supply a label name, as in `%entry: addiu $t0, $zero, 7`.
The parser retains separate label and instruction nodes, and navigation on the label token
finds the formal parameter for both MARS and SPIM spellings. Label completion hides parameter
spellings inside macros, including in included bodies, and preserves literal dollar-prefixed
labels outside them. The assembler fixture verifies that a macro-generated label can be exported.

After a successful assembly, a reference without a physical label target can use that build
from its entry or any captured include. The Java core publishes immutable local and global
symbol definitions with final addresses and physical origins, including nested macro calls.
Exporting a label preserves its definition site. The editor opens that site in the macro body
and checks the captured document revisions before following the link. Starting a new build
supersedes the previous result, and a late older build cannot replace the current result.
Direct navigation works before building. Build-backed links open definitions in files associated
with MARS MIPS.

Instruction operand completion also joins the last build's local and global names with physical
declarations. The assembler marks names it rewrites for macro hygiene, so completion can omit
those names while preserving caller-supplied spellings with similar suffixes. Completion searches
the built names by prefix and retains them while typing on one ordinary instruction line in
the active source. Edits elsewhere in the captured graph require another build.

**Build → Assemble Containing MIPS Entry** lists top-level MIPS sources that reach the open
file through includes. It follows IntelliJ's reverse include index and offers each entry
once. Choosing an entry builds it and selects its context for the editor. Label navigation
and completion use successful build results. Equivalence definitions, macro calls and
parameters, and the active data list use live source events up to the caret in expanded
include order. That context is available after a failed build and drops back to the open
file when the selected entry no longer reaches it. The Build menu retains a separate action
to assemble the current file as an entry.

## Background assembler diagnostics

IntelliJ's external highlighting pass captures the current entry and its unsaved includes,
then invokes the Java assembler in an isolated session. Warnings and errors appear in the
editor for their physical source file. The pass leaves Build output and running sessions
independent. IntelliJ schedules the pass after its ordinary syntax analysis and cancels
obsolete editor work.

Editors in the same assembly reuse the entry's captured sources through IntelliJ's
document and VFS dependencies. A bounded project cache also reuses the completed
assembly for matching source revisions. It keeps warnings and errors grouped by
physical file, so sharing the work still gives each editor its own diagnostics.

For an include, diagnostics use the selected entry or infer it when there is exactly one
containing root. An ambiguous include needs an explicit entry. Before marking source,
the pass checks that the entry and source revisions are still current. Reverse include
queries use the shared index directly so a newly introduced parent can invalidate an
inferred context. Successful background results also supply symbol navigation and completion
without a manual build. The project keeps a bounded cache of detached symbol maps and uses
only the map for the selected or uniquely inferred entry. The editor can reuse a compatible
map for completion while the current instruction operand is incomplete. Stale results and
changes elsewhere in the captured source graph cannot supply names.

## Go to Declaration for data labels and exports

Native references navigate from `.globl` lists, integer data operands and their continued
lists. The include-ordered source cache records integer data-list state at each effective
event, so operand lookups can read the state at the caret without replaying the prefix.
Physical directives and those created by `.eqv` expansion both contribute segment and list
changes. Directives inside unexpanded macro bodies leave that state alone.

The Java tokenizer classifies ambiguous operands after substitution, including when an
alias introduces a leading label. A matching macro call takes precedence over a bare list.
The assembler-backed fixture verifies the words written for both inline and continued
operands. Segment and list changes caused by executing a macro require expansion context.

## External storage declarations

The generated parser preserves the complete `.extern` payload and exposes addressable names
as native declarations. References prefer a local label, then the first valid external
reservation in include order. `.globl` continues to bind only local definitions. Navigation
and operand completion use the Java tokenizer to check the expanded name and nonnegative
size, including `.eqv` aliases visible at the reservation. The per-file declaration
and reference indexes rebuild for the external symbol kind.

The query follows the current entry and physical includes. Storage created only during macro
expansion and visibility across separate assembly entries still need build-context binding.
The fixtures cover names, aliases, invalid reservations, parser recovery, completion, and the
addresses the assembler allocates for duplicate declarations and subsequent reservations.

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

Macro navigation also binds operation candidates that expand to `.macro` or `.end_macro` in
the include-ordered source prefix. A call or formal use resolves to the written opening alias
when the signature came from expansion. The alias itself still resolves to its `.eqv` definition.
The physical PSI and indexes retain the source spelling. Expansion uses the core tokenizer and
the same leading-label rule as assembly, including the rule that labels inside a macro body do
not expose a following closing directive.

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

## Named floating-point literals

The lexer classifies the exact spellings `NaN` and `Infinity` as numeric
literals, matching the assembler. Their PSI and highlighting use the same
types as other numeric literals. Differently cased or longer identifier
spellings remain available to the declaration index. Both symbol indexes
rebuild with the updated lexer.

The assembler-backed fixture checks IEEE-754 data for the named values and
numeric PSI for decimal, hexadecimal, suffixed, and signed exponent forms.

## Double-quoted strings

The lexer follows the assembler's immediate-backslash rule for double quotes
and its acceptance of a final escaped quote at the end of a line. This keeps
embedded comment markers inside data strings and preserves the complete raw
`.include` path. Fixtures compare the resulting PSI with assembled bytes and
the indexed include path with the core tokenizer. Both symbol indexes and the
include index rebuild with the updated lexer.

## Segment switch directives

The native parser keeps the full operand text of `.data`, `.kdata`, `.text`,
and `.ktext`, including punctuation in the suffix the assembler ignores.
Only the first operand can select a new address. The reference index records
a leading symbol as an equivalence candidate and skips symbols in the ignored
suffix. The assembler-backed fixture checks the resulting user and kernel
instruction addresses and stored data.

## Directive parity fixture

A two-file fixture assembles and parses every directive listed by the Java core,
in ordinary source and with extra comma separators. The test derives the
expected directive set from the core catalog. It verifies the emitted user and
kernel instructions, data bytes and values, alignment and reserved space, global
and external symbols, and the assembler's `.set` warning.

The native parser preserves `.set` operands as raw assembler tokens because
the core ignores the directive's payload. A fixture covers mixed case, no
operands, unmatched punctuation, and an equivalence that expands to `.set`.
Ignored operands no longer appear in the symbol reference index.

The clean IntelliJ 2026.3 launch passed 1,293 tests, including 86 plugin tests,
and authored plugin code covered all 699 lines and 432 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256.

## Native Run for a MIPS source file

The registered MARS run configuration stores a source path and can be created
from an assembly file's editor context. The runner uses IntelliJ's execution
manager and console, with a separate core session for each launch. Run uses the
same captured-source assembly and Build diagnostics as Assemble Current MIPS
File. It passes the assembled program to the simulator in the session that
produced it.

The process handler preserves simulated exit values, routes stdout and stderr
to their matching console streams, and requests a stop before releasing the
session on cancellation or detach. Relative file syscalls resolve from the
entry file's directory. The output decoder retains UTF-8 sequences split across
descriptor writes.

The IDE fixtures exercise native Run with an unsaved include and a nonzero exit
value, the configuration editor and persistence, runtime and assembly failures,
split console output, relative file access, and stop and detach.

The clean IntelliJ 2026.3 launch passed 1,300 tests, including 92 plugin tests,
and authored plugin code covered all 815 lines and 480 branch outcomes.
The IDE loaded MARS MIPS and opened the smoke project. All six JARs in the
distribution ZIP matched the launched plugin by SHA-256.

## Interactive Run console

The process handler now exposes standard input through IntelliJ's `ProcessHandler`
contract. As in IntelliJ's external-task runner, the console writes to a JDK
pipe on its input worker. MARS reads that pipe through its session's existing
line and descriptor syscalls. Stop closes the read end after requesting a stop,
which releases a simulator waiting on input. Process termination closes both
ends of the pipe.

A Jupiter fixture types into the native console in UTF-8 and UTF-16 and checks
integer, character, and raw descriptor reads in one program. The Run handler
uses the global encoding that IntelliJ's console selects when it attaches. The
session decodes text up to the newline so a following descriptor read sees the
original bytes. Existing CLI sessions use the JVM's default encoding. Another
fixture waits for the simulator to enter a blocking read, then verifies that
Stop completes without a runtime diagnostic.

## Simulator boundary for native breakpoints

The debugger can supply an address predicate to `SimulatorEngine.runUntilAsync`.
The simulator evaluates it on the execution worker before each instruction,
including the entry instruction. Breakpoint decisions can reflect changes made
while the program is running. A stop or pause requested during a predicate
check takes precedence over a breakpoint match. The existing desktop address
array still skips its initial PC when resuming.

Core fixtures verify that an entry breakpoint leaves registers untouched, a
changed breakpoint is observed on the running worker, and Stop wins over a
concurrent breakpoint match. The native XDebugger session and source breakpoint
binding will consume this boundary in later slices.

## Source map for debugging

The assembled program exposes an immutable map from each machine address to
its physical source line and macro call chain. The assembler retains file and
line information from include expansion and shares the same origin across the
machine instructions of a pseudo-op. Line breakpoint lookup returns the first
emitted address of each occurrence, including repeated macro expansions and
calls nested inside another macro. It uses emission order when a directive
changes the order of text addresses.

Core fixtures assemble captured files with nested includes, macros, a pseudo-op,
and separate entry and handler files. They verify the forward and reverse
lookups and confirm that a runtime text write leaves the newly decoded
instruction without an origin. The map describes the assembled image. The
native debugger uses live instruction metadata when presenting a current
execution position.

## Native line debugging

The Debug executor starts a native XDebugger session for the MIPS Run
configuration. It shares captured-source assembly, console I/O, process
termination, and session ownership with Run. The native breakpoint handler
resolves physical lines against the assembled image and publishes an address
lookup to the simulator worker. Registration changes update that lookup
without scanning the registered lines for each instruction.

The Debug view shows one simulated CPU frame. Resume, Pause, Stop, instruction
Step Into, and Run to Cursor are wired. The platform handles breakpoint
enablement, muting, dependencies, and non-suspending logpoints. Breakpoint
expressions and call-aware stepping report that they are unavailable.

Jupiter fixtures exercise IntelliJ's line-breakpoint toggle, stops in an
include and repeated macro expansions, instruction stepping, Run to Cursor,
breakpoint updates, logpoints, unmapped positions, and process shutdown.

## Register inspection in Debug Variables

Each suspension captures the integer registers, PC, HI and LO, the implemented
Coprocessor 0 registers, and all Coprocessor 1 registers and condition flags.
The Variables tree uses native debugger groups. Coprocessor 1 shows both raw
bits and decoded single- and double-precision values. Reading the registers
for a frame does not emit simulated access events.

A fixture expands IntelliJ's Variables tree for two native debugger frames
after execution advances. It checks the program counter, multiply result,
exception PC, floating-point condition flags, and paired floating-point
values against the machine state at each suspension.

## Instruction backstep

The native Debug toolbar offers **Backstep Instruction** while the paused
session has history. The simulator worker undoes one recorded instruction,
and XDebugger receives a new source position and Variables frame. The
toolbar action becomes disabled when the history runs out or execution resumes.

The debugger fixture walks backward over a syscall and its setup instructions.
It checks that the PC and registers return to their earlier values and that
console output already produced remains visible.

## Memory inspection

The native Debug layout includes a Memory tab for the paused machine. Each
page shows addresses, hexadecimal bytes, and printable characters. The
address field accepts unsigned decimal and hexadecimal values. Pages stop
at the end of the 32-bit address space and show unavailable words with
dashes. Go to Region jumps to text, data, heap, kernel text and data,
memory-mapped I/O, and the PC and stack pointer captured at the suspension.

The debugger reads a page on its background execution coroutine through a
queue owned by the current suspension. Resume closes that queue, disables
navigation, and leaves the last completed page labeled with its suspension
number. A new suspension refreshes the selected address, including after
instruction backstep. The core exposes code and mapped data for inspection
without issuing simulated read events.

Core tests cover code, stack, data, kernel memory, and memory-mapped I/O.
IntelliJ fixtures check navigation, address validation, endian display,
resume, store and backstep refresh, view disposal, and the updated PC and
stack pointer after execution advances.

## Next slices

Continue checking parser edge cases against the Java assembler while building
completion, build-context reference binding, documentation, and native build
actions and diagnostic reporting. Execution/debugger/tool work follows the component mapping. No existing
MARS feature is deferred by this delivery order.
