# Source formatting

Kotlin production code, tests and Gradle scripts use KTFMT 0.65's default META style.
The root Spotless target explicitly includes every module's Kotlin source and scripts;
build outputs, Gradle caches and downloaded IntelliJ installations are excluded.
The root `check` task depends on `spotlessCheck`.

From `mars5`:

```sh
./gradlew spotlessApply spotlessCheck
```

Use `spotlessCheck` without `spotlessApply` for a read-only verification. Keep separate
statements on separate lines and retain formatter-inserted spacing between functions.
Semicolons inside strings and required enum syntax are data or syntax, respectively.
Do not compress code after running the formatter.

Examples outside `mars5` can be checked with the shared source-audit tool in the sibling
book checkout:

```sh
python3 ../mars-book/tools/format_sources.py --root "$PWD" --language kotlin
```

Run that command from this repository root; add `--apply` to format. The tool pins the
same KTFMT version and style. It requires Java and dependency access on its first run.
The book checkout documents Python and native LaTeX formatting separately.
