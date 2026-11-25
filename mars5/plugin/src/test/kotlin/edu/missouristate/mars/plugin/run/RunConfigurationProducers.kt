package edu.missouristate.mars.plugin.run

import com.intellij.execution.actions.RunConfigurationProducer
import edu.missouristate.mars.util.javaClassOf

internal inline fun <reified T : RunConfigurationProducer<*>> runConfigurationProducer(): T =
    RunConfigurationProducer.getInstance(javaClassOf<T>())
