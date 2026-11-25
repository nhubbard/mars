package edu.missouristate.mars.plugin.grading

import com.intellij.util.PathUtil
import edu.missouristate.mars.cli.grading.AssignmentScript
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.util.javaClassOf
import java.nio.file.Path
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider

internal class MarsAssignmentDefinitions : ScriptDefinitionsProvider {
  override val id: String = "MARS assignment"

  override fun getDefinitionClasses(): List<String> = listOf(javaClassOf<AssignmentScript>().name)

  override fun getTemplateClasspath(): List<Path> =
      listOf(
          Path.of(PathUtil.getJarPathForClass(javaClassOf<AssignmentScript>())),
          Path.of(PathUtil.getJarPathForClass(javaClassOf<MarsContext>())),
      )
}
