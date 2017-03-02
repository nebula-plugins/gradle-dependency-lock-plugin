package nebula.plugin.dependencylock.wayback

import groovy.transform.TupleConstructor
import nebula.plugin.dependencylock.model.GradleDependency
import org.gradle.api.Incubating
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Provides information about dependencies used at some point in the past so we can "rewind" our dependency
 * lock back to that time. Provider implementations can be provided for a variety of different backing datastores (e.g.
 * Nebula Metrics) and selectors (e.g. past commit, last tagged release, dependencies used in the context of a particular
 * deployed application).
 */
@Incubating
abstract class WaybackProvider {
    Project project

    WaybackProvider(Project project) { this.project = project }

    /**
     * @param selector - The point in time at which to lock (could be a timestamp, a version number,
     * or any other ordered sequence supported by a provider implementation).
     * @param configuration - The configuration to retrieve dependencies for. Some providers may only be able to
     * provide runtime dependencies, etc. depending on what their backing datasources are.
     * @return The dependencies in <code>configuration</code> as of <code>selector</code>.
     */
    abstract Set<GradleDependency> wayback(String selector, Configuration configuration)
}