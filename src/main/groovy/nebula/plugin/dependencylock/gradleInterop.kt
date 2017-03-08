package nebula.plugin.dependencylock

import groovy.lang.Closure
import org.gradle.api.Action

inline fun <T> T.groovyClosure(crossinline call: () -> Unit) = object : Closure<Unit>(this) {
    @Suppress("unused")
    fun doCall() {
        call()
    }
}

inline fun <U> Any.action(crossinline call: U.() -> Unit) = Action<U> { call(it) }
