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

## Next slices

Finish syntax and semantic edge cases from MARS characterization, then add reference sentinels,
query-time sentinel joins, native named PSI/stubs where required, completion/navigation/docs, detached source
analysis and build results. Execution/debugger/tool work follows the component mapping. No existing
MARS feature is deferred by this delivery order.
