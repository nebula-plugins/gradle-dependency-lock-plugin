package nebula.plugin.dependencylock.utils

import org.gradle.api.Project

class DependencyLockingFeatureFlags {
    static final String ISOLATED_PROJECTS_SUPPORT = 'nebula.dependency-lock.isolatedProjectsSupport'

    static boolean isIsolatedProjectsSupportEnabled(Project project) {
        project.providers.systemProperty(ISOLATED_PROJECTS_SUPPORT)
            .map { Boolean.parseBoolean(it) }
            .getOrElse(false)
    }

    static boolean isCoreLockingEnabled() {
        Boolean.getBoolean("nebula.features.coreLockingSupport")
    }

    static boolean isPathAwareDependencyDiffEnabled() {
        Boolean.getBoolean("nebula.features.pathAwareDependencyDiff")
    }
}
