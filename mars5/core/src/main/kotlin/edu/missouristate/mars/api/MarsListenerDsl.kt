package edu.missouristate.mars.api

/** Keep nested listener configuration separate from callbacks on the native listener receiver. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class MarsListenerDsl
