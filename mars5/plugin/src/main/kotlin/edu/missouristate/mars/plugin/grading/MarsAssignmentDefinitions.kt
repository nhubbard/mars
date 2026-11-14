package edu.missouristate.mars.plugin.grading

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.cli.grading.AssignmentScript
import edu.missouristate.mars.plugin.jarPathForClass
import java.nio.file.Path
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider

internal class MarsAssignmentDefinitions : ScriptDefinitionsProvider {
    override val id: String = "MARS assignment"

    override fun getDefinitionClasses(): List<String> = listOf(AssignmentScript::class.java.name)

    override fun getTemplateClasspath(): List<Path> = listOf(
        Path.of(jarPathForClass<AssignmentScript>()),
        Path.of(jarPathForClass<MarsTarget>()),
    )
}
