package nebula.plugin.dependencylock

import groovy.json.JsonOutput
import groovy.transform.Canonical
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.model.LockValue

import static DependencyLockTaskConfigurer.GLOBAL_LOCK_CONFIG

@Canonical
class DependencyLockWriter {
    File lockFile
    Set<String> skippedDependencies

    void writeLock(Map<LockKey, LockValue> locks) {
        lockFile.parentFile.mkdirs()

        // The result map maps configuration -> map of group:artifact -> map of dep properties -> values. The result
        // would then be transformed into Json. For example:
        // {
        //    "runtime": {
        //       "test.example:foo": { locked: "2.0.0", "transitive: ["test:sub1", "test:sub2"] }
        //       "test:sub1": { "project: true" }
        //       "test:sub2": { "project: true" }
        //    }
        //    "default": {
        //       "test.example:foo": { locked: "2.0.0", "transitive: ["test:sub1", "test:sub2"] }
        //       "test:sub1": { "project: true" }
        //       "test:sub2": { "project: true" }
        //    }
        // }
        def result = new TreeMap().withDefault { new TreeMap().withDefault { new TreeMap() } }

        def filteredSkippedDeps = locks.findAll { LockKey k, v ->
            !skippedDependencies.contains("${k.group}:${k.artifact}" as String)
        }

        filteredSkippedDeps.each { key, lock ->
            def configuration = key.configuration.startsWith('detachedConfiguration') ? GLOBAL_LOCK_CONFIG : key.configuration
            def depMap = result[configuration]["${key.group}:${key.artifact}"]
            if (lock.locked) {
                depMap['locked'] = lock.locked
            } else {
                depMap['project'] = true
            }
            if (lock.viaOverride) {
                depMap['viaOverride'] = lock.viaOverride
            }
            if (lock.transitive) {
                def transitiveFrom = lock.transitive.collect { "${it.group}:${it.artifact}" }.sort()
                depMap['transitive'] = transitiveFrom
            }
            if (lock.firstLevelTransitive) {
                def transitiveFrom = lock.firstLevelTransitive.collect { "${it.group}:${it.artifact}" }.sort()
                depMap['firstLevelTransitive'] = transitiveFrom
            }
        }

        lockFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(result))
    }
}
