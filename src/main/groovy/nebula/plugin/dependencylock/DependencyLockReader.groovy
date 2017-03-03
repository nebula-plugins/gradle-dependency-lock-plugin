package nebula.plugin.dependencylock

import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import static nebula.plugin.dependencylock.DependencyLockPlugin.OVERRIDE_FILE
import static nebula.plugin.dependencylock.DependencyLockPlugin.GLOBAL_LOCK_CONFIG

@TupleConstructor
class DependencyLockReader {
    private static final Logger logger = Logging.getLogger(DependencyLockPlugin)

    Project project

    Map readLocks(Configuration conf, File dependenciesLock, Collection<String> updates = []) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies in $conf")

        if(!dependenciesLock.exists())
            return null

        Map locks = parseLockFile(dependenciesLock)

        if (updates) {
            locks = locks.collectEntries { configurationName, deps ->
                [(configurationName): deps.findAll { coord, info ->
                    def notUpdate = !updates.contains(coord)
                    def isFirstLevel = info.transitive == null && info.requested != null
                    def isFirstLevelTransitive = info.transitive.any { deps[it].project }
                    notUpdate && (isFirstLevel || isFirstLevelTransitive)
                }]
            }
        }

        // in the old format, all first level props were groupId:artifactId
        def isDeprecatedFormat = !locks.isEmpty() && locks.every { it.key ==~ /[^:]+:.+/ }
        // in the old format, all first level props were groupId:artifactId
        if (isDeprecatedFormat) {
            logger.warn("${dependenciesLock.name} is using a deprecated lock format. Support for this format may be removed in future versions.")
        }

        // In the old format of the lock file, there was only one locked setting. In that case, apply it on all configurations.
        // In the new format, apply _global_ to all configurations or use the config name
        return isDeprecatedFormat ? locks : locks[GLOBAL_LOCK_CONFIG] ?: locks[conf.name]
    }

    Map readOverrides() {
        // Overrides are dependencies that trump the lock file.
        Map overrides = [:]

        // Load overrides from a file if the user has specified one via a property.
        if (project.hasProperty(OVERRIDE_FILE)) {
            File dependenciesLock = new File(project.rootDir, project[OVERRIDE_FILE] as String)
            def lockOverride = parseLockFile(dependenciesLock)
            def isDeprecatedFormat = lockOverride.any { it.value.getClass() != String && it.value.locked }
            // the old lock override files specified the version to override under the "locked" property
            if (isDeprecatedFormat) {
                logger.warn("The override file ${dependenciesLock.name} is using a deprecated format. Support for this format may be removed in future versions.")
            }
            lockOverride.each { overrides[it.key] = isDeprecatedFormat ? it.value.locked : it.value }
            logger.debug "Override file loaded: ${project[OVERRIDE_FILE]}"
        }

        // Allow the user to specify overrides via a property as well.
        if (project.hasProperty('dependencyLock.override')) {
            project['dependencyLock.override'].tokenize(',').each {
                def (group, artifact, version) = it.tokenize(':')
                overrides["${group}:${artifact}".toString()] = version
                logger.debug "Override added for: ${it}"
            }
        }

        return overrides
    }

    private static Map parseLockFile(File lock) {
        try {
            return new JsonSlurper().parseText(lock.text) as Map
        } catch (ex) {
            logger.debug('Unreadable json file: ' + lock.text)
            logger.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
    }
}
