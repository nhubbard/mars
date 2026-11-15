package edu.missouristate.mars

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Arrays
import java.util.Objects
import java.util.stream.Stream
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Cross-checks the extracted pipeline against every expansion produced by the previous pipeline.
 */
internal class PseudoExpansionTest {
    @TempDir lateinit var directory: Path

    @JvmRecord internal data class Vector(val example: String?, val words: MutableList<Int>?)

    @ParameterizedTest(name = "{0}, delayed branching={1}")
    @MethodSource("configurations")
    fun everyPseudoFormKeepsItsExactExpansion(
        layout: String?,
        delayed: Boolean,
        vectors: MutableList<Vector>?,
    ) {
        TestSessions.create().use { session ->
            session.setMemoryConfiguration(layout)
            session.settings.setDelayedBranchingEnabled(delayed)
            Assertions.assertThat<Vector?>(vectors).hasSize(388)
            val source = directory.resolve("pseudo.s")
            for (vector in vectors!!) {
                Files.writeString(source, ".text\n" + vector.example + "\nlabel:\ntarget: nop\n")
                val program: MIPSprogram = session.assemble(listOf(source))
                val machine =
                    Objects.requireNonNull<ArrayList<ProgramStatement>>(program.machineList)
                val words =
                    machine
                        .subList(0, machine.size - 1)
                        .stream()
                        .map<Int>(ProgramStatement::binaryStatement)
                        .toList()
                Assertions.assertThat<Int?>(words)
                    .`as`(vector.example)
                    .containsExactlyElementsOf(vector.words)
            }
        }
    }

    companion object {
        @JvmStatic
        fun configurations(): Stream<Arguments?>? {
            val groups = linkedMapOf<String, MutableList<Vector>>()
            Objects.requireNonNull<InputStream>(
                    PseudoExpansionTest::class
                        .java
                        .getResourceAsStream("/pseudo-expansion-vectors.tsv"),
                    "pseudo-expansion-vectors.tsv",
                )
                .use { input ->
                    input
                        .bufferedReader(StandardCharsets.UTF_8)
                        .lineSequence()
                        .filter { line: String -> !line.startsWith("#") && !line.isBlank() }
                        .forEach { line: String ->
                            val fields: Array<String> = line.split("\t".toRegex()).toTypedArray()
                            val words =
                                if (fields[3].isBlank()) mutableListOf<Int>()
                                else
                                    Arrays.stream<String>(
                                            fields[3]
                                                .split(",".toRegex())
                                                .dropLastWhile { it.isEmpty() }
                                                .toTypedArray(),
                                        )
                                        .map<Int> { word: String -> word.toLong(16).toInt() }
                                        .toList()
                            groups
                                .getOrPut(fields[0] + " " + fields[1]) { mutableListOf() }
                                .add(Vector(fields[2], words))
                        }
                }
            return groups.entries.stream().map<Arguments?> { entry: Map.Entry<String, List<Vector>>
                ->
                val key: Array<String> =
                    entry.key.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                Arguments.of(key[0], key[1].toBoolean(), entry.value)
            }
        }
    }
}
