package nebula.plugin.dependencylock.tasks

import groovy.transform.EqualsAndHashCode

/**
 * Map key for locked dependencies.
 */
@EqualsAndHashCode
class LockKey {
    String group
    String artifact

    @Override
    String toString() {
        "${group}:${artifact}"
    }
}
