# Source style

Use imports instead of fully qualified class names in Java, Kotlin, tests, and Gradle Kotlin scripts.
Use Kotlin import aliases or explicit static imports when names conflict. Do not qualify a name inline
merely to avoid an import or resolve a collision.

Use a fully qualified name only when a framework or API explicitly requires it. For example, a
Settings dialog API may require a qualified page name to avoid internal API access. Explain the
concrete requirement beside a non-obvious exception. Package declarations, imports, and required
framework metadata are not inline type qualifications.
