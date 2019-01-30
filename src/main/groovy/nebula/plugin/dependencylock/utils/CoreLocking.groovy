package nebula.plugin.dependencylock.utils

class CoreLocking {
    static boolean isCoreLockingEnabled() {
        Boolean.getBoolean("nebula.features.coreLockingSupport")
    }
}
