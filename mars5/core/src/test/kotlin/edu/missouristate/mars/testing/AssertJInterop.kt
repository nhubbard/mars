package edu.missouristate.mars.testing

import edu.missouristate.mars.util.boxedClassOf
import edu.missouristate.mars.util.javaClassOf
import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThatExceptionOfType as javaExceptionType
import org.assertj.core.api.ThrowableTypeAssert

internal inline fun <reified T : Any> AbstractAssert<*, *>.isInstanceOf(): AbstractAssert<*, *> =
    isInstanceOf(boxedClassOf<T>())

internal inline fun <reified T : Throwable> AbstractThrowableAssert<*, *>.isInstanceOf():
    AbstractThrowableAssert<*, *> = isInstanceOf(boxedClassOf<T>())

internal inline fun <reified T : Throwable> assertThatExceptionOfType(): ThrowableTypeAssert<T> =
    javaExceptionType(javaClassOf<T>())
