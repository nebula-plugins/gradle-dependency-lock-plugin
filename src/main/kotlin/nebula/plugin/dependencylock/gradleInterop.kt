package nebula.plugin.dependencylock

import groovy.lang.Closure
import nebula.plugin.dependencylock.utils.DependencyLockingPropertyLookup
import org.gradle.api.Action
import org.gradle.api.Project

inline fun <T> T.groovyClosure(crossinline call: () -> Unit) = object : Closure<Unit>(this) {
    @Suppress("unused")
    fun doCall() {
        call()
    }
}

inline fun <U : Any> Any.action(crossinline call: U.() -> Unit) = Action<U> { call(it) }

fun Project.findStringProperty(name: String): String? = findProperty(name)?.toString()

fun Project.findStringProperty(name: String, isolatedProjectsSupportEnabled: Boolean): String? =
    DependencyLockingPropertyLookup.findProperty(this, name, isolatedProjectsSupportEnabled)?.toString()
