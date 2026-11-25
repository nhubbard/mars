package edu.missouristate.mars

import edu.missouristate.mars.util.javaClassOf
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
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

  @JvmRecord internal data class Vector(val example: String, val words: List<Int>)

  @ParameterizedTest(name = "{0}, delayed branching={1}")
  @MethodSource("configurations")
  fun everyPseudoFormKeepsItsExactExpansion(
      layout: String?,
      delayed: Boolean,
      vectors: List<Vector>,
  ) {
    TestSessions.create().use { session ->
      session.setMemoryConfiguration(layout)
      session.settings.setDelayedBranchingEnabled(delayed)
      Assertions.assertThat<Vector?>(vectors).hasSize(388)
      val source = directory.resolve("pseudo.s")
      for ((example, expectedWords) in vectors) {
        Files.writeString(source, ".text\n$example\nlabel:\ntarget: nop\n")
        val program: MIPSprogram = session.assemble(listOf(source))
        val machine = checkNotNull(program.machineList)
        val words = machine.subList(0, machine.size - 1).map(ProgramStatement::binaryStatement)
        Assertions.assertThat<Int?>(words).`as`(example).containsExactlyElementsOf(expectedWords)
      }
    }
  }

  companion object {
    @JvmStatic
    fun configurations(): List<Arguments> {
      val groups = linkedMapOf<String, MutableList<Vector>>()
      checkNotNull(
              javaClassOf<PseudoExpansionTest>()
                  .getResourceAsStream("/pseudo-expansion-vectors.tsv"),
          ) {
            "pseudo-expansion-vectors.tsv"
          }
          .use { input ->
            input
                .bufferedReader(StandardCharsets.UTF_8)
                .lineSequence()
                .filter { line: String -> !line.startsWith("#") && line.isNotBlank() }
                .forEach { line: String ->
                  val fields = line.split('\t')
                  val words =
                      if (fields[3].isBlank()) emptyList()
                      else
                          fields[3].split(',').dropLastWhile(String::isEmpty).map {
                            it.toLong(16).toInt()
                          }
                  groups
                      .getOrPut("${fields[0]} ${fields[1]}") { mutableListOf() }
                      .add(Vector(fields[2], words))
                }
          }
      return groups.map { (configuration, vectors) ->
        val (layout, delayed) = configuration.split(' ')
        Arguments.of(layout, delayed.toBoolean(), vectors)
      }
    }
  }
}
