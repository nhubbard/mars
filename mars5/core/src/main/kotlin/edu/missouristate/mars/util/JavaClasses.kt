package edu.missouristate.mars.util

/** A JVM class token, retaining primitive classes for Kotlin primitive types. */
inline fun <reified T : Any> javaClassOf(): Class<T> = T::class.javaPrimitiveType ?: T::class.java

/** A boxed JVM class token for APIs such as Swing's column-class contract. */
inline fun <reified T : Any> boxedClassOf(): Class<T> = T::class.javaObjectType
