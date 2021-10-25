package nebula.plugin.dependencylock.utils

class DependencyLockingFeatureFlags {
    static boolean isCoreLockingEnabled() {
        Boolean.getBoolean("nebula.features.coreLockingSupport")
    }

    static boolean isPathAwareDependencyDiffEnabled() {
        Boolean.getBoolean("nebula.features.pathAwareDependencyDiff")
    }
}
