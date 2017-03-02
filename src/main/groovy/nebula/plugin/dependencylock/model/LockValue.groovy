package nebula.plugin.dependencylock.model

import groovy.transform.Canonical

@Canonical
class LockValue {
    Set<LockKey> transitive = [] as Set
    Set<LockKey> firstLevelTransitive = [] as Set
    Boolean childrenVisited = false
    String requested
    String locked
    Boolean project = false
    String viaOverride
}