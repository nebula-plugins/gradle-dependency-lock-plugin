package nebula.plugin.dependencylock

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import groovy.transform.TupleConstructor
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.model.LockValue
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import static DependencyLockTaskConfigurer.OVERRIDE_FILE
import static DependencyLockTaskConfigurer.GLOBAL_LOCK_CONFIG

@TupleConstructor
class DependencyLockReader {
    private static final Logger logger = Logging.getLogger(DependencyLockReader)
    private static final Moshi moshi = new Moshi.Builder().build()
    private static final JsonAdapter<Map> jsonAdapter = moshi.adapter(Map)
    Project project

    DependencyLockReader(Project project) {
        this.project = project
    }

    Map readLocks(Configuration conf, File dependenciesLock, Map<LockKey, LockValue> requestedDependencies, Collection<String> updates = []) {
        logger.info("Using ${dependenciesLock.name} to lock dependencies in $conf")

        if (!dependenciesLock.exists())
            return null

        Map locks = parseLockFile(dependenciesLock)

        if (updates) {
            locks = locks.collectEntries { configurationName, deps ->
                if (configurationName != conf.name && configurationName != GLOBAL_LOCK_CONFIG) {
                    // short-circuit if this is not the relevant configuration or global lock configuration
                    return [(configurationName): []]
                }
                [(configurationName): deps.findAll { coord, info ->
                    def notUpdate = !updates.contains(coord)
                    def coordinateSections = coord.split(":")
                    def lockValue = requestedDependencies.get(new LockKey(group: coordinateSections[0], artifact: coordinateSections[1], configuration: configurationName)) ?: new LockValue()
                    def requestedAVersion = lockValue.requested != null
                    def isFirstLevel = info?.transitive == null
                    def isFirstLevelTransitive = info?.transitive?.any { deps[it]?.project }
                    notUpdate && ((isFirstLevel && requestedAVersion) || isFirstLevelTransitive)
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
        // Use findProperty() to check both gradle properties (-P) and project extras (ext)
        def overrideFile = project.findProperty(OVERRIDE_FILE)
        if (overrideFile) {
            File dependenciesLock = new File(project.rootDir, overrideFile as String)
            def lockOverride = parseLockFile(dependenciesLock)
            def isDeprecatedFormat = lockOverride.any { it.value.getClass() != String && it.value.locked }
            // the old lock override files specified the version to override under the "locked" property
            if (isDeprecatedFormat) {
                logger.warn("The override file ${dependenciesLock.name} is using a deprecated format. Support for this format may be removed in future versions.")
            }
            lockOverride.each { overrides[it.key] = isDeprecatedFormat ? it.value.locked : it.value }
            logger.debug "Override file loaded: ${overrideFile}"
        }

        // Allow the user to specify overrides via a property as well.
        // Use findProperty() to check both gradle properties (-P) and project extras (ext)
        def override = project.findProperty('dependencyLock.override')
        if (override) {
            override.toString().tokenize(',').each {
                def (group, artifact, version) = it.tokenize(':')
                overrides["${group}:${artifact}".toString()] = version
                logger.debug "Override added for: ${it}"
            }
        }

        return overrides
    }

    private static Map parseLockFile(File lock) {
        try {
            return jsonAdapter.fromJson(lock.text)
        } catch (ex) {
            logger.debug('Unreadable json file: ' + lock.text)
            logger.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
    }
}
