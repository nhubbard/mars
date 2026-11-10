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
The instruction catalog fixture assembles all forms as written, with repeated separators, through
mnemonic aliases, and through parameterized macros. It compares emitted words and source origins
with delay slots enabled and disabled, and checks the corresponding operation nodes in IntelliJ.
Names beginning with a dot and digit, such as `.1_count`, retain their identifier role unless
the full token is a decimal literal. An assembler-backed fixture verifies these names in label
declarations and references, macro definitions and invocations, and equivalence definitions.
Zero-offset loads retain separate register and base operands when the comma is omitted.
Numeric, character, and label offsets still group with their base register when commas appear
before the opening parenthesis.

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

MARS registers `Commenter`, `PairedBraceMatcher`, and `QuoteHandler` implementations.
IntelliJ owns comment toggling, paired insertion and deletion, skipping closing delimiters,
and brace matching. The quote handler uses the lexer's closed and unclosed tokens for
MIPS double-quoted strings and single-quoted characters.

Jupiter tests use the platform's `codeInsightProjectFixture` and `codeInsightFixture`.
They exercise real editor actions, selected lines, existing comments, hashes inside strings,
parenthesis typing and deletion, nested matching, and quote typing around escaped delimiters.
Authored plugin code retains 100% line and branch coverage.

The second `clean cleanSandbox runIde` launch passed all 1,238 tests and loaded the packaged
plugin without a plugin error. The visual acceptance limit above still applies.

## Native Structure view

IntelliJ's Structure view and File Structure popup list labels, macros, `.eqv`
constants, and `.extern` declarations in source order. After indexing, the
include-ordered analysis supplies macro names and boundaries introduced by
equivalences. Written declarations inside a local macro appear below it, even
when the macro has no closing directive yet. An opening line in another file
does not add a remote node to the outline. Navigation opens the written token
that supplied the macro name. During indexing, the outline uses the current
file's written PSI and still follows unsaved edits. Selecting another assembly
entry refreshes the open Structure pane and folding from the same project
notification, even when the shared source text stays the same.

## Native macro folding

IntelliJ collapses nonblank macro bodies in `.asm` and `.s` files. After indexing,
the shared assembly timeline pairs the opening and closing lines, including
boundaries supplied by equivalences in the including entry. Both boundaries must
be in the same file. The signature, its inline comment, and the terminator stay
visible. The initial fast pass and indexing-mode pass use the current file's
written directives. Unsaved changes in MARS documents and explicit assembly
entry selection can add or remove folds in other open MARS editors through
IntelliJ's folding refresh API. Platform tests also cover creating, rewriting,
and deleting an unopened include on disk.

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
For an instruction or operand supplied by an equivalence, the handler uses the include-ordered
source cache and counts the expanded tokens, including delimiters and multi-operand aliases.
Register and label completion and instruction references use the same token positions.
While indexes rebuild, written mnemonics retain their written-token hints. IntelliJ owns
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

An operation-head alias uses the catalog entry for its effective instruction or directive in
include order. Go to Declaration on that token opens the written equivalence definition.
The include prepass requires a physical `.include`, so that entry is available on the physical
directive.

On a resolved macro call, Quick Documentation shows the selected overload's expanded
formal parameters and the comment on its opening line. The popup identifies the source
file and can navigate to the written macro name, including when an equivalence supplied
the signature. Its smart pointer rebinds the call after an edit and drops the target if
the invocation no longer matches. Parameter Info continues to show the available
overloads while a call is being typed.

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
when looking for a direct label. The include-ordered source cache also records labels supplied
by equivalence expansion. A generated prefix opens the written operation alias. A renamed
physical label opens its declaration and appears in completion under the expanded spelling.
Written and generated labels both follow the expanded macro boundaries, including across
includes. An included editor can look up forward labels in its selected entry using live
source, including after a failed build.
The IDE fixtures check these bindings alongside case sensitivity, macro shadowing, duplicate
declarations, and non-label operands. Labels supplied by macro arguments still use assembly
results.

## Find Usages

IntelliJ's Find Usages locates references to written labels, macros, equivalences,
and named external symbols. The platform word index finds candidates and the
existing PSI references resolve them using include order, macro scope, and call
arity. Code, comments, and strings keep their separate search contexts. Labels
such as `.target.label`, `$buffer`, and `.` remain searchable.

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
while navigating to the written token that supplies the name. Header aliases link to
their effective `.eqv` definitions. A use of an expanded formal opens the written
parameter or the alias that supplies it.

## Go to Declaration for macro parameters

The same include-ordered boundary traversal finds the active macro for `%name` and SPIM
`$name` uses. The cached header keeps the assembler's exact formal parameter tokens,
so navigation selects the first matching spelling and opens its precise source offset.
Parameter uses are distinct from header declarations and quoted or commented text.
An undeclared SPIM parameter in an active macro does not bind to an outer label.

The query uses the selected assembly entry or the unique including entry when IntelliJ
can infer one. Fixtures cover both parameter spellings, duplicate names, address and
operation uses, include boundaries, case, and indexing mode.

Parameters can also supply a label name, as in `%entry: addiu $t0, $zero, 7`.
The parser retains separate label and instruction nodes, and navigation on the label token
finds the formal parameter for both MARS and SPIM spellings. Completion offers the current
macro's formals at operation, operand, and label use sites, including expanded headers and
included bodies. The lookup marks them as **Macro parameter** alongside the regular register
and label suggestions. It observes case and visible equivalences. Tab replaces the whole
parameter token. The assembler fixtures verify the inserted code and an exported
macro-generated label.

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
An alias that expands to `.globl` links its operands to local labels.
The assembler-backed fixture verifies the words written for both inline and continued
operands. Segment and list changes caused by executing a macro require expansion context.

## External storage declarations

The generated parser preserves the complete `.extern` payload and exposes addressable names
as native declarations. The source cache binds the first valid reservation for each name in
include order. Navigation and completion share that cache, including when an included editor
uses the selected entry. The Java tokenizer checks the expanded name and nonnegative size
for both literal and generated `.extern` directives. Navigation opens the written name of a
literal reservation or the operation alias that generated one. The per-file declaration and
reference indexes store physical sites.

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

When an include group has no top-level entry, the editor highlights its cyclic
paths using the cached reverse include graph. Includes that leave the cycle
remain ordinary references. Selecting an assembly in Build uses the assembler's
own diagnostic for the directive that closes the cycle.

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

Include navigation and document capture require a directory at each dot segment.
They follow directory symlinks before applying a parent segment and stay at the
filesystem root when it has no parent. MARS rechecks dot-segment edges from the
shared index because IntelliJ can fall back to a generic resolver after an
unresolved path.

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

A physical `.end_macro` closes a macro only when the rest of the line contains
separators or a comment. If extra tokens are present while editing, the parser
reports the malformed line and keeps later declarations and references in the
macro up to its standalone terminator. Both source indexes rebuild their
physical macro ownership on upgrade.

A malformed parameter tail in a named `.macro` declaration is recovered on
that line. The macro body remains available to the editor, and parameter
references can navigate to valid formals before the first invalid argument.
Label resolution continues to distinguish macro-local and outer names. The
malformed signature cannot match a macro invocation.

A physical `.macro` opener inside another macro stays on its own line. The next
standalone terminator closes the outer body in the physical syntax tree. The opener
retains its signature for include-aware resolution, since an earlier include can close
the first macro before the assembler reaches the second opener. An include on the
opener line itself still takes precedence. The declaration and reference indexes rebuild
affected entries, including names that start with a dot and a digit.

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

The grammar consumes one leading label per physical statement, as the Java
assembler does. For example, `entry: emit: 7` retains `entry` as the label
and exposes `emit` as a macro call with a colon and a number as its arguments.
The same rule applies inside macro bodies. Known directives and registers do
not produce label declarations, so a colon after `.set` remains part of the
ignored directive payload. The declaration and reference indexes rebuild.

The source lexer keeps a malformed assembler candidate together. A spelling
such as `bad%name` cannot become a declaration, and `fake/.include` cannot
introduce an include edge. Percent-prefixed macro formals still accept the
punctuation allowed by MARS. The declaration, reference, and include indexes
rebuild on upgrade. Debugger expressions keep their own operator boundaries.

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

The core reports lexical errors on retained lines during assembly tokenization,
after it knows their original file and line. An include line that gets replaced
still reports malformed tokens from the prepass. This prevents the two passes
from reporting the same token twice in IntelliJ Build. The diagnostic links
to the original source, including unsaved includes.

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

Lexical errors in the written line are reported once. Intermediate substitutions
do not emit diagnostics. An invalid token introduced by the finished expansion
links to the written alias that supplied it, using the same offset mapping as
the editor's expanded token analysis.

Operand, directive, and symbol diagnostics also use the written column.
Tokens retain their expanded spans for parsing and macro substitution. A
diagnostic for text supplied by an equivalence points to the alias use, and an
operand written after that alias keeps its own column. The Build fixture
checks navigation to an invalid operand in an unsaved include.

Macro expansion carries the written token alongside each substitution. A
forwarded argument keeps its original call site through nested expansion,
including an equivalence used as the argument. A token from the macro body
keeps its definition file and column, even when retokenizing the expansion
resolves a later equivalence. The Build action and editor use these same
assembler locations.

An `.eqv` at the third assembler token of an unlabeled macro call can define
an alias during tokenization. The editor's source timeline records that
definition for later uses, including when the directive is itself supplied
by an alias. Go to Declaration opens the written directive or its alias at
the call site. Formatting keeps the replacement text intact.

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

An unfinished character literal ends at the next separator, unless that separator
is the character inside a closed literal. The parser marks the unfinished span
and retains later data operands, equivalence replacement tokens, macro arguments,
and comments. The assembler-backed fixture checks delimiter characters, escaped
apostrophes and backslashes, and signed octal escapes.

The lexer, assembler, and debugger share the character decoder. Closed invalid
spellings get a syntax error over the original literal without consuming later
operands. IntelliJ's syntax pass reports the literal in the editor, and Build
also reports any error at an alias use. The source indexes rebuild with the
revised token boundaries.

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

Commas around a label's plus and offset remain part of the address operand,
including when an equivalence supplies the offset and a base register follows.
When an instruction mnemonic names a label, a plus joined directly to digits
still starts a signed literal under the assembler's tokenization rules.

For comma-free operands, the parser follows MARS's signed-token rule. A sign
immediately followed by a digit begins a new operand after a number, register,
or character literal. A sign after an identifier can still introduce a label
offset. The debugger's expression entry point keeps ordinary arithmetic. An
assembler-backed fixture compares the PSI operand spans and stored values for
integer, character, and floating-point lists and continuation lines, then checks
the machine encoding of an instruction with an adjacent signed immediate. The
reference index rebuilds against the revised PSI boundaries.

Macro invocation and `.eqv` replacement nodes use the same signed-token rule
before expansion. For example, `emit(,-2,7)` preserves `-2` as one argument,
and `emit -7` preserves the separate `-` and `7` tokens that MARS sees after
the macro name. An equivalence's original text is still available for the
assembler to tokenize again in the replacement's destination context.

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

The assembler reports an unfinished `.ascii` or `.asciiz` string at its opening
quote, including on continuation lines in unsaved includes. Build diagnostics link
to that position. Closed strings retain MARS's existing escape behavior.

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

The Run configuration also stores delayed branching and self-modifying code.
Both default to off for older configurations. Run and Debug take an immutable
snapshot of those choices and apply it to the new session before assembly.
Delayed branching can add instructions to a pseudo-instruction's expansion,
so the source map and simulator use the same setting. Background diagnostics
and symbol facts use the selected launch options and separate cached results
when those options change.

The process handler preserves simulated exit values, routes stdout and stderr
to their matching console streams, and requests a stop before releasing the
session on cancellation or detach. Relative file syscalls resolve from the
entry file's directory. The output decoder retains UTF-8 sequences split across
descriptor writes.

The IDE fixtures exercise native Run with an unsaved include and a nonzero exit
value, the configuration editor and persistence, runtime and assembly failures,
split console output, relative file access, and stop and detach.
Execution fixtures verify delay slots in both Run and Debug, and verify that
stepping through rewritten code uses the live instruction without a source
position from the original assembled image.

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

### Dialog syscalls

MARS syscalls 50–59 display IntelliJ dialogs for confirmation, typed input,
and messages. The simulator waits for the response while the rest of the
IDE stays usable. Confirmation returns 0 for Yes, 1 for No, and 2 for
Cancel or closing the window. Input preserves the simulator's distinction
between an empty response and cancellation. Guest message text is displayed
as plain text, with the icon requested by the syscall.

Each dialog belongs to the launch that opened it. Stopping Run or Debug,
or closing the project, dismisses the dialog and releases the simulator.
Standard console input still uses the Run or Debug console.

### Clear screen

Syscall 60 clears the IntelliJ console attached to the current Run or Debug
launch, including earlier output queued for display. Output from subsequent
instructions appears after the clear. Input already submitted to the
program stays in its input stream. The native console may discard input
still being edited when it clears the transcript.

### Time and sleep

Syscall 30 returns the wall-clock time as two 32-bit register values.
Syscall 32 waits for the requested number of milliseconds on the simulator
worker. Stopping an IntelliJ Run or Debug launch cancels that wait, even
when the program requested a long sleep.

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

Breakpoint placement uses the editor's include-ordered equivalences. Aliases that expand
to instructions or possible macro calls offer a breakpoint. Expansion to a directive, a
literal, or a label alone leaves the line without a breakpoint. Unresolved operations stay
eligible while the editor is indexing or lacks the containing entry's definitions. The
assembled source map determines the actual address at debug time.

The Debug view shows the live CPU frame followed by observed callers and
interrupted locations. Older frames navigate to the physical source of the call
or interrupted instruction when available. Calls from executable data show an
address. Older frames show read-only call metadata. Live registers and expression
evaluation belong to the current CPU frame.

Debugger frames and **Disassembly → Open Source** use the source captured with
the running image. Unchanged files open in the project editor. If a file has
been edited or removed, the debugger opens a read-only **assembled snapshot**
tab at the original line. **Run to Cursor** accepts positions in that snapshot
or an unchanged file. A cursor in changed text cannot select a line in the
running image. New or updated line breakpoints resolve only when their
source matches the assembled text.

Resume, Pause, Stop, source
Step Into, Step Over, Step Out, Step Instruction, and Run to Cursor are wired. The platform handles breakpoint
enablement, muting, dependencies, and non-suspending logpoints. Breakpoint
conditions and log expressions share the evaluator described below.

Step Out uses the simulator's observed link writes and completed jumps to saved
return addresses across pauses. Recursive calls have distinct frames even when
their return addresses match. A breakpoint or exception handler can interrupt
Step Out. The eret instruction restores the interrupted call history, and backstep
restores the history at the rewound instruction. Step Out requires an observed
caller and uses the same 100,000-instruction slice limit as Step Over.

Step Into advances until the live instruction's source origin changes or execution
returns to the starting address. A pseudo-instruction's emitted instructions share
one origin object. Each macro expansion has its own occurrence,
so consecutive executions of the same physical body line still produce distinct stops.
The simulator checks that boundary alongside line breakpoints on its execution worker. A source step
has a 256-instruction limit per simulator slice, and stepping from a position with
no source origin executes one instruction. The Debug toolbar's Step Instruction
action always executes one machine instruction.

Step Over follows the same source boundaries when the statement makes no call. For an executed
linking jump or taken linking branch, it follows the return addresses reported by the simulator.
This handles nested calls, recursion, delay slots, and link registers other than `$ra`. Breakpoints
can stop inside a callee, and an exception handler stops the step at its entry. A slice stops at the
100,000-instruction limit if the call runs that long, keeping control available when code does not
return conventionally.

Jupiter fixtures exercise IntelliJ's line-breakpoint toggle, stops in an
include and repeated macro expansions, aliased instructions and calls, instruction stepping, Run to Cursor,
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

### Editing registers

**Set Value** is available on the writable general-purpose registers and on
`hi` and `lo` in the Integer registers group. It is also available on the four
implemented Coprocessor 0 registers (`vaddr`, `status`, `cause`, and `epc`).
The editor accepts any debugger expression that produces a 32-bit integer,
including a memory read. The worker evaluates the expression at the current
pause, writes the register under the core's stopped-session lease, and refreshes
the frame's registers, watches, and stack-pointer address.

In the Coprocessor 1 group, **Set Value** accepts numeric literals or debugger
expressions for single registers and double pairs. A standalone `0x` literal
writes the exact 32-bit or 64-bit pattern, including signed zero and NaN payloads.
Each editor opens with the current raw bits. The FCC entry accepts an integer
expression from 0 through 255 to set all eight flags together.

A changed value starts a new backstep baseline. Invalid expressions and assignments
of the existing value preserve history. Each pause owns its edit queue, so a
modifier retained across resume or step cannot change a later suspension.

## Read-only debugger expressions

Evaluate Expression and Watches use the selected frame's captured registers and
assembled symbols. Expressions accept CPU register names and numbers, `pc`, `hi`,
`lo`, Coprocessor 0 numbers such as `cp0.$14`, floating-point registers, and `FCC`.
Local labels use the active instruction's assembly entry, then global labels.
The generated parser has an expression entry point for the debugger. It accepts
one complete expression with numeric and character literals, parentheses, unary
and binary `+` and `-`, comparisons, and bitwise operators. From tightest to loosest,
the binary operator groups are addition and subtraction, shifts, relational comparisons,
equality, `&`, `^`, and `|`. Unary `~` complements a word. Use
parentheses around a mask before comparing it, as in `(word(flags) & 0xff) == 7`.
Comparison and bitwise tokens are recognized only in the debugger.

Integer arithmetic wraps to 32 bits and displays hexadecimal. Floating-point
arithmetic displays a decimal result. Comparisons use signed integer values or
the decoded floating-point value, and return `0x00000001` or `0x00000000`.
Bitwise operators require integer operands and return a 32-bit word. Shifts use
the low five bits of the count, as MIPS variable shifts do. The `>>` operator
preserves the sign bit and `>>>` fills from the left with zeros.

Scalar evaluation operates on the paused frame's snapshot. Explicit `byte(address)`,
`half(address)`, and `word(address)` reads run on the simulator worker that owns the
current pause. Addresses can use registers, labels, arithmetic, and nested reads.
Byte and halfword values are zero-extended and follow the machine's byte order.
The inspector reads code and mapped data without emitting simulated access events.
Resume and backstep close the old pause's request queue. A retained frame keeps its
scalar values and reports its memory as unavailable.

The native Breakpoints dialog uses the same editor for conditions and log expressions.
Both evaluate at the instruction boundary using the captured registers and the stopped
machine's memory. Zero skips the breakpoint and its log expression. A nonzero result
passes the condition. Invalid conditions report an error and execution continues.
For example, `word($t3) != 7` skips the breakpoint when the word at `$t3` is seven.
An invalid log expression reports an error and the breakpoint still follows its suspend
policy. XDebugger handles valid log output, dependencies, and the decision to suspend.
A platform test verifies false and true conditions, the exact logged values, both error
paths, and continuation through non-suspending logpoints.

## Instruction and source backstep

XDebugger has no reverse-step callback. Its Debug toolbar extension exposes **Backstep Instruction**
and **Backstep Source Statement** while the paused session has history. Instruction backstep undoes
one machine instruction.
Source backstep groups the recorded instructions from the most recent execution of a source
statement, including a pseudo-instruction's expansion. Macro invocations and loop iterations are
separate steps. An instruction without source, such as rewritten code,
is undone by itself. If the undo history begins partway through a statement, the operation stops
at the oldest available instruction.

The simulator worker does the undo and XDebugger receives one updated source position and
Variables frame. Both actions become disabled when history runs out or execution resumes.
Fixtures cover undo across a syscall, included statements and repeated macro invocations with a
multi-instruction pseudo-op, a repeated loop instruction, and rewritten code. Console output
already produced remains visible.

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

## Disassembly

The **Disassembly** tab follows the current program counter. It shows a bounded
window of live addresses, encoded words, decoded basic instructions, and source
locations when available. **Open Source** navigates to the selected instruction's
physical line. Rewritten instructions and executable data are decoded using the
owning simulator's instruction set, without borrowing source locations from the
assembled image. An unmapped row has no word and a misaligned PC shows its address
without selecting an instruction.

The tab uses the same pause-owned inspection queue as Memory. Resume clears the
listing, and a newer pause or disposal invalidates results already in flight.
The core read issues no simulated memory events. Fixtures cover navigation,
source disappearance, code writes and backstep, executable data, invalid words,
and the bounds of the 32-bit address space.

The **Toggle Address Breakpoint** button adds or removes a breakpoint at the selected
address for the current assembly entry. IntelliJ stores the entry and address in its
project breakpoint state. The **MIPS Address Breakpoints** group in the Breakpoints
dialog provides the usual enabled state, conditions, logging, and dependencies.
The disassembly marker reflects enabled, disabled, muted, and log-only states.

An address breakpoint stops at the instruction boundary even when the PC has no
source position. It uses the same worker predicate and expression evaluator as
line breakpoints. When a logpoint and a suspending breakpoint share an address,
the debugger processes the logpoint before suspending.

## Instruction Counter

Open **View → Tool Windows → MARS Tools** and select a Run or Debug execution.
The counter shows completed machine instructions and the R, I, J, and other format
counts and percentages. I-type includes conditional branches. The selector uses
IntelliJ execution IDs and keeps the result available while its Run or Debug tab
remains open.

Each execution starts counting automatically. Disconnect pauses counting, Connect
resumes it, and Reset clears the selected execution's counts. Backstepping keeps
the accumulated counts. Executing an instruction again adds another event.
The panel refreshes only while visible and reads a consistent snapshot.

## Data Cache Simulator

The **Data Cache Simulator** tab in **MARS Tools** tracks data and stack
reads and writes per Run or Debug execution. Fetches from the text segment,
assembly, debugger inspection, and backstep do not count. Fetches from data
memory in self-modifying code mode count as data reads. Re-executed instructions
produce new observations. The tab keeps the totals while the Run or Debug tab
remains available and updates only while visible.

The default cache uses eight direct-mapped blocks of four words. The controls
also support fully associative and N-way layouts, least-recently-used or seeded
random replacement, and the original power-of-two size choices. Changing
replacement policy preserves the current cache. Applying a new layout clears
the cache. Reset clears counts, block contents, and the
access log and restarts the random sequence. The table shows each block's tag
and last result. The optional log retains the most recent 128 accesses while
the totals include all observations.

## Floating Point Representation

The Floating Point Representation tab provides an independent binary32
calculator and can attach to any FPR in the selected Run or Debug execution.
It converts decimal, hexadecimal and separate sign, exponent and fraction
fields, with IEEE 754 classification, effective exponent, hidden bit and NaN
payload. Run and resumed Debug views follow the raw register value and retain
the last value after execution. Editing and reset are available during a
debugger pause through the same serialized register edits as the Variables
view. A changed register establishes a new backstep baseline.

## Digital Lab Sim

Open **View → Tool Windows → MARS Tools → Digital Lab Sim** and select a Run
or Debug execution. Connect while the program is running or enable **Connect
Digital Lab Sim from launch** in its run configuration. That setting keeps the
device active even when the tool window is closed. The final display remains
visible while the execution tab is open.

The two seven-segment displays use the bytes at MMIO base + `0x10` and `0x11`.
The keypad scans the row written at `0x12` and places its row and column code at
`0x14`. Click to latch a key and click again to release it. The counter at
`0x13` schedules an interrupt every 30 completed instructions while enabled.
Keypad and counter interrupts retain their original Cause bits and respect
Coprocessor 0's exception level. Keypad writes happen at instruction boundaries,
including when resuming or stepping from a debugger pause. Reset clears the
displays, key, and timer. Backstep restores the display, applied key, timer,
keypad port, and pending device interrupt bits from the same instruction frame
as the CPU. A queued key press is discarded on rewind, so stepping forward
again requires fresh input.

## Keyboard and Display Simulator

Open **View → Tool Windows → MARS Tools → Keyboard and Display Simulator**
and select a Run or Debug execution. Click **Connect** or enable **Connect
Keyboard and Display Simulator from launch** when the program needs the
device immediately. Type in the **Keyboard** field to send an input byte.
The **Display** uses the IDE console editor and font settings. The panel
offers fixed, uniform, and normal delay modes and a **Display after delay**
toggle. Try the echo program in `examples/mmio-terminal/echo.asm`.

The Kotlin device implements the receiver control and data words at MMIO
base + `0x00` and `0x04` and the transmitter control and data words at
`0x08` and `0x0c`. It applies host input at instruction boundaries and
counts completed instructions for fixed, uniform, and normal transmit delays.
Output can appear when a write is accepted or after the delay. Form feed
clears the display and bell with packed coordinates addresses an 80-column,
24-row screen. Carriage return and line feed move the cursor in screen mode.

Instruction checkpoints restore the display, ready bits, receiver data,
pending interrupts, and the delay sequence. Unapplied host keystrokes are
discarded on rewind. The final display stays available while its execution
tab is open. A connection owned by the panel closes when the panel is
disposed. The launch option keeps the device connected for the execution.

## MarsBot

Open **View → Tool Windows → MARS Tools → MarsBot**, select an execution, and
click **Connect**. Enable **Connect MarsBot from launch** in the MIPS Run
configuration when the program writes to the device immediately. This also
allows the robot to run with the tool closed. Try `examples/marsbot/square.asm`
with the default memory layout. The board retains its final trail while the
execution tab is open.

The panel reports the position, heading, pen and movement state. Hover over
the MMIO label to see the full port map. Set the clock by entering an
instruction count and clicking **Set rate** or pressing Enter. Reset clears
the board and returns the robot to its initial position and heading.

The Kotlin model uses the legacy word ports at `0xffff8010` for heading,
`0xffff8020` for the pen, `0xffff8030` and `0xffff8040` for coordinates,
and `0xffff8050` for movement. Compact layouts use the corresponding
`0x7f90` through `0x7fd0` ports. Heading is clockwise from north.
A pen value of 1 draws and 0 lifts the pen. A nonzero movement value starts
the robot. Coordinates are clamped to the 512×512 board.

One tick moves the robot by one pixel. The default clock ticks every 64
completed instructions while moving. The model preserves fractional
positions and publishes integer coordinates. Backstep restores the clock,
position, port values, and pen trail from the instruction's checkpoint.

## Scavenger Hunt

Open **View → Tool Windows → MARS Tools → Scavenger Hunt** and select a
Run or Debug execution. Connect from the panel, or enable **Connect
Scavenger Hunt from launch** when the assembly program initializes the
game immediately. Try `examples/scavenger-hunt/round.asm`. The map shows
the seven locations and each player's position. Click a scoreboard header
to sort the player list. The final board remains visible with the execution
tab.

**Reset** starts a fresh round at the next instruction boundary without
a store to the device's ports. Debug backstep restores the previous round
and discards pending resets. The default player regions overlap MarsBot's
ports, so the run configuration and both panels allow one of these devices
per execution.

The game model keeps 22 players, seven common locations, and a 20-word
sorting task for each player. It reproduces the original seed-42 sequence
for location placement, initial task data, and replacements after successful
tasks. A new round resets the authentication sequence, player progress, and
task stream while preserving the program-supplied turn count.

Administrator ID 999 starts rounds. Each authentication attempt advances
the 32-bit sequence, and an invalid ID leaves the current player unchanged.
A move spends one energy and must stay inside the 712 by 652 coordinate
bounds and within 2.5 pixels. A correctly ordered task refills energy to 20.
The legacy task check accepts signed words in nondecreasing order. Progress
is recorded when a player reaches a location, and the finish score measures
executed instructions since initialization.

The device listens for aligned CPU word stores. Administration words start
at `0xffffe000` and player regions start at `0xffff8000`, with a stride of
`0x400` bytes. Starting a round publishes the initial positions, energy,
locations, tasks, and handshakes. Accepted move and task requests clear
their ready words, while rejected requests leave them set. The player ID
word reports the authenticated identity. Foreign player writes produce a
diagnostic and leave the game's state intact. As in Venus, ordinary stores
to the backing memory remain visible to the program.

The simulator applies responses before the next instruction. The CPU
restores the words it wrote, and the device's instruction checkpoint
restores the rest of the response, the game state, and its diagnostic.
An assembly fixture sorts a real task through loads and stores and
verifies that rewind restores the sorted task and request word.

## Screen Magnifier scope decision

Screen Magnifier is outside the IntelliJ plugin scope by user decision.
The capture and annotation prototype was removed from the plugin.
The M6 educational-tool target is 13 tools.

## Introduction to Tools

Open **View → Tool Windows → MARS Tools → Introduction to Tools** for a
working Kotlin provider example. Select a Run or Debug execution before
running or stepping the program. The displayed count measures instructions
observed since selection, including instructions executed again after
backstep. Each selection owns a separate listener and counter.

External plugins can depend on `edu.missouristate.mars` and contribute an
implementation of `MarsToolProvider` through the native extension point.
Use a unique provider ID and a localized title. The tool window adds tabs
in extension order, builds each provider's content on first selection,
and disposes the content and its listeners when the provider unloads.

```xml
<depends>edu.missouristate.mars</depends>
<extensions defaultExtensionNs="edu.missouristate.mars">
  <toolProvider id="example.mips.instructionMonitor" implementation="example.mips.InstructionMonitorProvider"/>
</extensions>
```

The checked-in [IntroToToolsProvider](mars5/plugin/src/main/kotlin/edu/missouristate/mars/plugin/tools/IntroToToolsProvider.kt)
shows how to create the native execution selector, bind a listener to the
selected execution, and update Swing from its own UI coroutine. Instruction
callbacks run on the simulator worker and should return promptly.

## Directory assembly

Choose **Build → Assemble MIPS Directory** with the entry file active, or
enable **Assemble other MIPS files in the source directory** in a MIPS
Run configuration. The entry file is assembled first. Other top-level
`.asm` and `.s` files follow in filename order, using unsaved editor text.
The include prepass can still read files in subdirectories or files with
other extensions. Put include-only fragments in a subdirectory or give
them an extension such as `.inc` to keep them out of the top-level list.

Run and Debug use the same capture as the Build action. Breakpoints and
local symbols resolve in the source file that owns the instruction. For
editing a shared include, the first owning source in build order supplies
its macro and equivalence context. Editor diagnostics reuse the selected directory build and refresh when
sibling files are added or removed. Single-file and directory builds
cache their captures separately.

Set an exception handler under **Settings → Build, Execution, Deployment →
MARS Assembly**. The setting is shared by Build, Run, Debug, and editor
analysis. It supports IntelliJ path variables, and files under the project
can be shared using the project directory variable. Clear the field to
disable it. The handler assembles before the entry file, with its own
includes and unsaved editor text. Directory mode excludes the configured
handler from the sibling list so it assembles once. When the handler itself
is the entry file, it assembles once as the entry.

## Ordered project sources

Use **Settings → Build, Execution, Deployment → MARS Assembly → Project
sources** to choose and reorder source files from across the project. The
first file is the entry. Choose **Build → Assemble MIPS Project** or enable
**Use project assembly sources** in a MIPS Run configuration. The list
supplies the entry and assembly order for Run and Debug, so the individual
file and directory options are inactive in that mode. The source list is
shared in the project settings and uses IntelliJ path variables for
portability. IntelliJ refreshes editor diagnostics and symbol resolution
when the list changes. Project builds use unsaved source text and the
project's exception handler.

The MIPS Build menu actions submit full assembly tasks through IntelliJ's
`ProjectTaskManager`. The MARS runner handles its own tasks and uses the
same source capture and Build output as Run and Debug. A batch assembles
each distinct source selection once, reports assembly errors to the caller,
and stops pending assembly tasks on cancellation. Java and Kotlin builds
continue through their existing runners.

In a MIPS Run or Debug configuration, add **Assemble MIPS Sources** under
**Before Launch** to put assembly at a specific point in the launch task
order. Assembly errors appear in the Build tool window and stop the
launch. A successful task closes its assembly session and passes a detached
image to Run or Debug. The image preserves the initialized memory, entry
point, machine instructions, symbols, macro source map, and captured source
text. Run and Debug load it into a fresh session. Later tasks can change files
on disk or in the editor without changing what that launch executes. Without
the Before Launch task, Run and Debug prepare the image as they start.

Run and Debug reuse recent successful images when the ordered source text,
resolved includes, exception handler, and execution options match. The Build
window marks a reused image as up to date and republishes its diagnostics at
the current document positions. Explicit Assemble actions build a new image.
Each launch loads its own machine state.

For mixed projects that use IntelliJ's bundled JVM compiler, enable
**Assemble project sources before IntelliJ JVM compilation** under
**Settings → Build, Execution, Deployment → MARS Assembly**. A compile
scope that contains a listed MIPS source assembles the entire ordered
unit and its dependencies once. MIPS diagnostics appear alongside JVM
compiler messages, and an assembly error stops that compilation.
The setting is off by default. The bridge loads with IntelliJ's Java
plugin and leaves delegated Gradle and Maven builds to their own tasks.
MIPS Run and Debug keep their own assembly step even when their launch
configuration also requests a JVM build.

## Instruction limit

Set **Instruction limit** in a MIPS Run or Debug configuration. **0**
allows unlimited execution. A positive value counts machine instructions
across debugger stops, including instructions executed again after
backstep. Reaching the limit terminates the launch with a console message
and status 1, and any configured after-run dumps use the final state.
A program that exits on its final allowed instruction keeps its own exit
status.

## Run speed

Choose the initial **Run speed** in a MIPS Run or Debug configuration.
The **Speed** control in the Run or Debug toolbar changes the current
launch only. It uses Venus's rates from 0.05 through 30 instructions per
second and **Unlimited**, the default. The simulator updates a pending
delay when the speed changes. Single-instruction stepping still pauses
immediately after executing the instruction.

## Program entry point

Set the default **Start at global main** under **Settings → Build,
Execution, Deployment → MARS Assembly**. Each MIPS configuration can
inherit, enable, or disable it. When enabled, MARS starts at the text label declared with
`.globl main`. If there is no global `main` in a text segment, execution
starts at the text segment base. The launch chooses its initial program
counter when loading the assembly image, so this setting can change
without rebuilding the source.

## Working directory

Set **Working directory** in a MIPS Run or Debug configuration to control
where program file syscalls resolve relative paths. The directory must
exist and may use project macros such as `$PROJECT_DIR$`. Leave the field
blank to use the entry source's directory. Assembly `.include` paths still
resolve from the source file that declares them.

## Program arguments

Enter **Program arguments** in a MIPS Run or Debug configuration. IntelliJ's
argument editor accepts double quotes around a value with spaces, `""` for
an empty argument, and `\"` for a literal double quote. MARS argument
strings use nonzero single-byte characters (`U+0001`–`U+00FF`).

At launch, the simulator puts the argument count in `$a0`, the address
of the null-terminated pointer array in `$a1`, and the count at `$sp`.
The initial stack is visible at the first debugger stop. Each execution
gets its own arguments even when two configurations reuse the same
assembly image. Arguments that exceed the selected layout's stack space
stop the launch with a console error.

## Project memory layout

Under **Settings → Build, Execution, Deployment → MARS Assembly**, set
**Memory layout** to **Default**, **Compact, data at address 0**, or
**Compact, text at address 0**. The choice controls assembly addresses,
editor diagnostics and instruction expansion, and new Run and Debug
sessions. Changing it invalidates incompatible assembly images. A session
that is already running or paused keeps the layout captured at launch.

The compact layouts use the exception vector at `0x00004180`. Debugger
memory shortcuts and memory exports use the selected layout's addresses.
Scavenger Hunt uses its original fixed addresses and requires **Default**.

## Assemble when opened

Enable **Assemble MIPS sources when opened** in **Settings → Build,
Execution, Deployment → MARS Assembly**. The option is off by default.
When a local MIPS editor opens, IntelliJ waits briefly and checks its
selected assembly or uniquely inferred entry. Opening several files from
one configured assembly produces one Build result. For an include with
several possible entries, use **Build → Assemble Containing Entry** to
select the intended assembly.

Automatic checks run in trusted projects and skip files closed before the
check starts. Build results stay available in the Build tool window while
focus stays in the editor. A successful check can warm the assembly image
for Run or Debug, and the check has its own session.

## Assembler policy

In **Settings → Build, Execution, Deployment → MARS Assembly**, choose whether to allow pseudo-instructions
and whether to treat assembler warnings as errors. The project defaults apply to manual assembly,
optional assembly before JVM compilation, and editor diagnostics. The default project policy allows
pseudo-instructions and reports warnings without failing the build.

Run and Debug configurations can inherit or override either setting independently. Before Launch
and process startup use the same resolved options for that launch. A run configuration can use a
different policy without changing diagnostics in the editor. Promoted warnings appear as errors
with the original source locations.

## New MIPS projects

Choose **File → New → Project → MARS MIPS**. The wizard creates a general
IntelliJ module with `src` as its source root. The default starter is
`src/main.asm`. Select **Add a separate console library** to create
`src/console.s` and a starter that calls it. The wizard opens the entry
file, configures the source order, and selects a shared **Run MIPS
Program** configuration that can also be used with Debug. The project
uses IntelliJ's general module support and requires no JDK or external
build tool. Use IntelliJ's **Open** action to work in an existing source
directory.

## Next slices

Continue checking parser edge cases against the Java assembler while building
completion, build-context reference binding, documentation, and native build
actions and diagnostic reporting. Execution/debugger/tool work follows the component mapping. No existing
MARS feature is deferred by this delivery order.
