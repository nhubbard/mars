# Source style

Use imports instead of fully qualified class names in Java, Kotlin, tests, and Gradle Kotlin scripts.
Use Kotlin import aliases or explicit static imports when names conflict. Do not qualify a name inline
merely to avoid an import or resolve a collision.

Use a fully qualified name only when a framework or API explicitly requires it. For example, a
Settings dialog API may require a qualified page name to avoid internal API access. Explain the
concrete requirement beside a non-obvious exception. Package declarations, imports, and required
framework metadata are not inline type qualifications.

# IntelliJ integration

Follow the API contracts and implementation patterns in the read-only IntelliJ 2026.3 checkout at
`/Users/nhubbard/IdeaProjects/intellij-community`. Prefer native parsers, PSI, indexes, fixtures,
coroutine scopes and lifecycle facilities. Do not introduce parallel frameworks or custom adapter
layers where the platform provides the required mechanism. Keep custom code focused on MARS semantics.
Write new plugin code in Kotlin. Use Jupiter tests, preserve near-full meaningful branch coverage,
and commit each tested implementation step. Before every sandbox launch, run the real Gradle tasks
`clean cleanSandbox runIde`; do not add aliases. Never modify the IntelliJ reference checkout.
