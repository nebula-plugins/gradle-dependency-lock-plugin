package nebula.plugin.dependencylock.utils

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class ConfigurationUtils {
    private static final Logger LOGGER = Logging.getLogger(ConfigurationUtils)

    /**
     * Returns a collection of configurations that can be locked.
     * @param project
     * @param configurationNames
     * @param skippedConfigurationNamesPrefixes
     * @return
     */
    static Collection<Configuration> lockableConfigurations(Project project, Set<String> configurationNames, Set<String> skippedConfigurationNamesPrefixes = []) {
        Set<Configuration> lockableConfigurations = []
        if (configurationNames.empty) {
            if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {
                lockableConfigurations.addAll project.configurations.findAll {
                    it.canBeResolved && !ConfigurationFilters.safelyHasAResolutionAlternative(it) &&
                            // Always exclude compileOnly to avoid issues with kotlin plugin
                            !it.name.endsWith("CompileOnly") &&
                            it.name != "compileOnly"
                }
            } else {
                lockableConfigurations.addAll project.configurations.asList()
            }
        } else {
            lockableConfigurations.addAll configurationNames.collect { project.configurations.getByName(it) }
        }

        lockableConfigurations.removeAll {
            Configuration configuration -> skippedConfigurationNamesPrefixes.any {
                String prefix -> configuration.name.startsWith(prefix)
            }
        }
        return lockableConfigurations
    }

    /**
     * Filters non lockable configurations
     * @param subproject
     * @param configurationNames
     * @param lockableConfigurations
     * @return
     */
    static Collection<Configuration> filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject(Project subproject, Set<String> configurationNames, Collection<Configuration> lockableConfigurations) {
        if (configurationNames.size() > 0) {
            Collection<String> warnings = new HashSet<>()

            Collection<Configuration> consumableLockableConfigurations = new ArrayList<>()
            lockableConfigurations.each { conf ->
                Collection<String> warningsForConfiguration = provideWarningsForConfiguration(conf, subproject)
                warnings.addAll(warningsForConfiguration)
                if (warningsForConfiguration.isEmpty()) {
                    consumableLockableConfigurations.add(conf)
                }
            }

            configurationNames.each { nameToLock ->
                if (!lockableConfigurations.collect { it.name }.contains(nameToLock)) {
                    Configuration confThatWillNotBeLocked = subproject.configurations.findByName(nameToLock)
                    if (confThatWillNotBeLocked == null) {
                        String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be locked: '${nameToLock}'"
                        warnings.add(message)
                    } else {
                        warnings.addAll(provideWarningsForConfiguration(confThatWillNotBeLocked, subproject))
                    }
                }
            }

            if (warnings.size() > 0) {
                warnings.add("Requested configurations for global locks must be resolvable, consumable, and without resolution alternatives.\n" +
                        "You can remove the configuration 'dependencyLock.configurationNames' to stop this customization.\n" +
                        "If you wish to lock only specific configurations, please update 'dependencyLock.configurationNames' with other configurations.\n" +
                        "Please read more about this at:\n" +
                        "- https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_plugin_and_dependency_management\n" +
                        "- https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph")
                LOGGER.warn('--------------------\n' + warnings.sort().join("\n") + '\n--------------------')
            }
            return consumableLockableConfigurations
        }

        return lockableConfigurations
    }


    private static Collection<String> provideWarningsForConfiguration(Configuration conf, Project subproject) {
        Collection<String> errorMessages = new HashSet<>()

        if (!ConfigurationFilters.canSafelyBeConsumed(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be consumed: '${conf.name}'"
            errorMessages.add(message)
        }
        if (!ConfigurationFilters.canSafelyBeResolved(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be resolved: '${conf.name}'"
            errorMessages.add(message)
        }
        if (ConfigurationFilters.safelyHasAResolutionAlternative(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a deprecated configuration '${conf.name}' " +
                    "which has resolution alternatives: ${conf.getResolutionAlternatives()}"
            errorMessages.add(message)
        }

        return errorMessages
    }


}
