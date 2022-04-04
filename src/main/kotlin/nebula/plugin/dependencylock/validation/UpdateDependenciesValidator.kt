package nebula.plugin.dependencylock.validation

import nebula.plugin.dependencylock.exceptions.DependencyLockException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class UpdateDependenciesValidator {
    companion object {
        private val LOGGER: Logger = Logging.getLogger(UpdateDependenciesValidator::class.java)

        @JvmStatic
        fun validate(updateDependencies: Set<String>, failOnError: Boolean) {
            val errors = mutableListOf<String>()
            updateDependencies.forEach { coordinate ->
                if(coordinate.isEmpty()) {
                    errors.add("An empty element exists in the list")
                }
                else if(coordinate.split(":").size == 1) {
                    errors.add("$coordinate does not contain groupId:module. Only has one element")
                }
                else if(coordinate.split(":").size > 2) {
                    errors.add("$coordinate contains more elements than groupId:module. Version and classifiers are not supported")
                }
                if(coordinate.contains(";")) {
                    errors.add("$coordinate contains ; which is invalid")
                }
            }
            if(errors.isEmpty()) {
                return
            }
            if(failOnError) {
                throw DependencyLockException("updateDependencies list is invalid | Errors: ${errors.joinToString("\n")}")
            } else {
                LOGGER.error("updateDependencies list is invalid | Errors: ${errors.joinToString("\n")}")
            }
        }
    }

}