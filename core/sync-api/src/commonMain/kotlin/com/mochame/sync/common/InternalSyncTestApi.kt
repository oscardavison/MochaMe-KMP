package com.mochame.sync.common

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is for testing only and must not be used in production code."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class InternalTestApi