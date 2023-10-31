package nebula.plugin.dependencylock.validation

import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.DependencyLockPlugin.Companion.UPDATE_DEPENDENCIES
import nebula.plugin.dependencylock.DependencyLockPlugin.Companion.VALIDATE_DEPENDENCY_COORDINATES
import nebula.plugin.dependencylock.DependencyLockPlugin.Companion.VALIDATE_SIMULTANEOUS_TASKS
import nebula.plugin.dependencylock.DependencyLockPlugin.Companion.VALIDATE_SPECIFIED_DEPENDENCIES_TO_UPDATE
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class UpdateDependenciesValidator {
    companion object {
        private val LOGGER: Logger = Logging.getLogger(UpdateDependenciesValidator::class.java)

        @JvmStatic
        fun validate(
            updateDependencies: Set<String>, overrides: Map<*, *>,
            hasUpdateTask: Boolean, hasGenerateTask: Boolean,
            project: Project,
            extension: DependencyLockExtension
        ) {
            val validateCoordinates =
                if (project.hasProperty(VALIDATE_DEPENDENCY_COORDINATES)) project.property(
                    VALIDATE_DEPENDENCY_COORDINATES
                ).toString().toBoolean() else extension.updateDependenciesFailOnInvalidCoordinates.get()
            val validateSimultaneousTasks =
                if (project.hasProperty(VALIDATE_SIMULTANEOUS_TASKS)) project.property(
                    VALIDATE_SIMULTANEOUS_TASKS
                ).toString().toBoolean() else extension.updateDependenciesFailOnSimultaneousTaskUsage.get()
            val validateSpecifiedDependenciesToUpdate =
                if (project.hasProperty(VALIDATE_SPECIFIED_DEPENDENCIES_TO_UPDATE)) project.property(
                    VALIDATE_SPECIFIED_DEPENDENCIES_TO_UPDATE
                ).toString().toBoolean() else extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get()

            validateCoordinates(updateDependencies, validateCoordinates)
            validateSimultaneousTasks(hasUpdateTask, hasGenerateTask, validateSimultaneousTasks)
            validateSpecifiedDependenciesToUpdate(
                hasUpdateTask,
                updateDependencies,
                overrides,
                validateSpecifiedDependenciesToUpdate
            )
        }

        private fun validateCoordinates(updateDependencies: Set<String>, failOnError: Boolean) {
            val errors = mutableListOf<String>()
            updateDependencies.forEach { coordinate ->
                if (coordinate.isEmpty()) {
                    errors.add("An empty element exists in the list")
                } else if (coordinate.split(":").size == 1) {
                    errors.add("$coordinate does not contain groupId:module. Only has one element")
                } else if (coordinate.split(":").size > 2) {
                    errors.add("$coordinate contains more elements than groupId:module. Version and classifiers are not supported")
                }
                if (coordinate.contains(";")) {
                    errors.add("$coordinate contains ; which is invalid")
                }
            }
            if (errors.isEmpty()) {
                return
            }
            if (failOnError) {
                throw DependencyLockException("updateDependencies list is invalid | Errors: ${errors.joinToString("\n")}")
            } else {
                LOGGER.error("updateDependencies list is invalid | Errors: ${errors.joinToString("\n")}")
            }
        }

        @JvmStatic
        private fun validateSimultaneousTasks(hasUpdateTask: Boolean, hasGenerateTask: Boolean, failOnError: Boolean) {
            if (hasUpdateTask && hasGenerateTask) {
                val error =
                    "Using `generateLock` and `updateLock` in the same build will result in re-resolving all locked dependencies. " +
                            "Please invoke only one of these tasks at a time."
                if (failOnError) {
                    throw DependencyLockException(error)
                } else {
                    LOGGER.error(error)
                }
            }
        }

        private fun validateSpecifiedDependenciesToUpdate(
            hasUpdateTask: Boolean, updates: Set<String>, overrides: Map<*, *>, failOnError: Boolean
        ) {
            if (hasUpdateTask && updates.isEmpty() && overrides.isEmpty()) {
                val error =
                    "Usage of `updateLock` task requires specific modules to update. Please specify dependencies to update, such as with `-P${UPDATE_DEPENDENCIES}=com.example:foo,com.example:bar`. " +
                            "You can bypass this fail-fast validation with `-P${VALIDATE_SPECIFIED_DEPENDENCIES_TO_UPDATE}=false`"
                if (failOnError) {
                    throw DependencyLockException(error)
                } else {
                    LOGGER.error(error)
                }
            }
        }
    }

}