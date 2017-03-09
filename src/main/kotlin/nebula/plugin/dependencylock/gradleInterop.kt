package nebula.plugin.dependencylock

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project

inline fun <T> T.groovyClosure(crossinline call: () -> Unit) = object : Closure<Unit>(this) {
    @Suppress("unused")
    fun doCall() {
        call()
    }
}

inline fun <U> Any.action(crossinline call: U.() -> Unit) = Action<U> { call(it) }

fun Project.findStringProperty(name: String): String? = if (hasProperty(name)) property(name) as String? else null