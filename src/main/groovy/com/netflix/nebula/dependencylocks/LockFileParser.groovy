package com.netflix.nebula.dependencylocks

import groovy.json.JsonSlurper
import nebula.dependencies.comparison.ConfigurationsSet
import nebula.dependencies.comparison.Dependencies

/**
 * Parser for legacy Nebula lock files
 */
class LockFileParser {
    static ConfigurationsSet readLocks(File file) {
        if (!file || !file.exists()) {
            return new ConfigurationsSet([:])
        }
        def contents = new JsonSlurper().parse(file)

        Map<String, Dependencies> lock = contents.collectEntries { configuration, dependencies ->
            [(configuration): new Dependencies(dependencies.collectEntries { dependency, props ->
                [(dependency): props.locked]
            })]
        }

        return new ConfigurationsSet(lock)
    }
}
