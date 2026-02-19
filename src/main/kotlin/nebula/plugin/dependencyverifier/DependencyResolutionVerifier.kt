/**
 *
 *  Copyright 2020 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencyverifier

import nebula.plugin.dependencylock.DependencyLockPlugin
import nebula.plugin.dependencylock.DependencyLockTaskConfigurer
import nebula.plugin.dependencylock.utils.ConfigurationFilters
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import java.io.File

const val UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD: String = "dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild"
const val CONFIGURATIONS_TO_EXCLUDE: String = "dependencyResolutionVerifier.configurationsToExclude"

/** Verifies dependency resolution and lock file consistency; task + FlowAction for reporting-only builds; config-cache compatible. */
class DependencyResolutionVerifier(
    private val verificationService: Provider<DependencyVerificationBuildService>,
    private val extensionFromRoot: DependencyResolutionVerifierExtension,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders
) {

    private val logger = Logging.getLogger(DependencyResolutionVerifier::class.java)

    fun verifySuccessfulResolution(project: Project) {
        val projectKey = uniqueProjectKey(project)
        val projectName = project.name
        verificationService.get().initializeProject(projectKey)
        val configurationsToExclude = getConfigurationsToExclude(project)
        val shouldFailTheBuild = getShouldFailTheBuild(project)
        val tasksToExclude = extensionFromRoot.tasksToExclude.get()
        val requestedTasks = project.gradle.startParameter.taskNames
        val allTasksExcluded = requestedTasks.isNotEmpty() && requestedTasks.all { taskName ->
            val simpleTaskName = taskName.substringAfterLast(':')
            tasksToExclude.contains(simpleTaskName) || tasksToExclude.contains(taskName)
        }

        if (allTasksExcluded) return
        val resolvableConfigurations = mutableListOf<Configuration>()
        val resolutionResultsMap = mutableMapOf<String, Provider<ResolvedComponentResult>>()
        project.configurations.configureEach { conf ->
            if (try {
                    conf.isCanBeResolved && !shouldSkipConfiguration(conf, configurationsToExclude)
                } catch (e: Exception) {
                    logger.debug("Skipping configuration '${conf.name}': ${e.message}", e)
                    false
                }
            ) {
                resolvableConfigurations.add(conf)
                try {
                    resolutionResultsMap[conf.name] = conf.incoming.resolutionResult.rootComponent
                } catch (e: Exception) {
                    logger.warn("Could not get resolution result for '${conf.name}': ${e.message}")
                }
            }
        }

        val verificationTask = project.tasks.register(
            "verifyDependencyResolution",
            DependencyVerificationTask::class.java
        ) { task ->
            task.usesService(verificationService)
            task.parameters.projectKey.set(projectKey)
            task.parameters.projectName.set(projectName)
            task.parameters.shouldFailTheBuild.set(shouldFailTheBuild)
            task.parameters.missingVersionsMessageAddition.set(extensionFromRoot.missingVersionsMessageAddition)
            task.parameters.resolvedVersionDoesNotEqualLockedVersionMessageAddition.set(
                extensionFromRoot.resolvedVersionDoesNotEqualLockedVersionMessageAddition
            )
            task.parameters.configurationsToExclude.set(configurationsToExclude)
            val ignoreLocks = try {
                DependencyLockTaskConfigurer.shouldIgnoreDependencyLock(project)
            } catch (_: Exception) {
                false
            }
            val effectiveLockValidation = extensionFromRoot.enableLockFileValidation.get() && !ignoreLocks
            task.parameters.enableLockFileValidation.set(effectiveLockValidation)
            task.parameters.configurationNamesToValidate.set(resolvableConfigurations.map { it.name })
            task.parameters.taskNames.set(project.gradle.startParameter.taskNames.toList())
            task.parameters.coreAlignmentEnabled.set(System.getProperty("nebula.features.coreAlignmentSupport", "false").toBoolean())
            task.parameters.coreLockingEnabled.set(DependencyLockingFeatureFlags.isCoreLockingEnabled())
            task.parameters.reportingOnlyBuild.set(isReportingTasksOnly(project))

            val lockedDepsMap = readLockFileForConfigurations(project, resolvableConfigurations)
            val overrideDepsMap = readOverrideLockFileForConfigurations(project, resolvableConfigurations)
            task.parameters.lockedDependenciesPerConfiguration.set(lockedDepsMap)
            task.parameters.overrideDependenciesPerConfiguration.set(overrideDepsMap)
            task.parameters.resolutionResults.set(resolutionResultsMap)
        }

        @Suppress("UNCHECKED_CAST")
        listOf(AbstractCompile::class.java, DependencyReportTask::class.java, DependencyInsightReportTask::class.java).forEach { taskType ->
            (project.tasks.withType(taskType) as TaskCollection<Task>).configureEach { t ->
                if (!tasksToExclude.contains(t.name)) t.finalizedBy(verificationTask)
            }
        }

        val lifecycleNames = setOf("build", "check", "test", "assemble", "dependencies")
        @Suppress("UNCHECKED_CAST")
        (project.tasks as TaskCollection<Task>).configureEach { t ->
            if (t.name in lifecycleNames) t.finalizedBy(verificationTask)
        }

        registerFlowActionStrategy(project, projectKey)
    }

    private fun <T> propertyOrExtension(project: Project, propName: String, default: T, parse: (String) -> T): T =
        project.findProperty(propName)?.let { parse(it.toString()) } ?: default

    private fun getConfigurationsToExclude(project: Project): Set<String> =
        propertyOrExtension(project, CONFIGURATIONS_TO_EXCLUDE, extensionFromRoot.configurationsToExclude.get()) { str ->
            str.split(",").map { it.trim() }.toSet()
        }

    private fun getShouldFailTheBuild(project: Project): Boolean =
        propertyOrExtension(project, UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD, extensionFromRoot.shouldFailTheBuild.get()) { it.toBoolean() }

    private fun uniqueProjectKey(project: Project): String {
        return "${project.name}-${if (project == project.rootProject) "rootproject" else "subproject"}"
    }

    /**
     * Skip configurations that should not be resolved by the verifier (config-cache safe: called only during configuration).
     * Uses [ConfigurationFilters.safelyHasAResolutionAlternative] for parity with GenerateLockTask and MigrateToCoreLocksTask.
     */
    private fun shouldSkipConfiguration(
        conf: Configuration,
        exclusions: Set<String>
    ): Boolean {
        return conf.name.startsWith("incrementalScala") ||
                conf.name.startsWith("kotlinCompilerPluginClasspath") ||
                conf.name.endsWith("DependenciesMetadata") ||
                exclusions.contains(conf.name) ||
                conf.name.endsWith("CompileOnly") ||
                conf.name == "compileOnly" ||
                ConfigurationFilters.safelyHasAResolutionAlternative(conf)
    }

    private fun readJsonMap(file: File): Map<*, *>? {
        if (!file.exists()) return null
        return try {
            groovy.json.JsonSlurper().parseText(file.readText()) as? Map<*, *>
        } catch (e: Exception) {
            logger.warn("Failed to read ${file.absolutePath}: ${e.message}")
            null
        }
    }

    private fun readLockFileForConfigurations(
        project: Project,
        configurations: List<Configuration>
    ): Map<String, Map<String, String>> {
        val lockFilename = project.findProperty(DependencyLockPlugin.LOCK_FILE)?.toString() ?: "dependencies.lock"
        val globalLockFilename =
            project.findProperty(DependencyLockPlugin.GLOBAL_LOCK_FILE)?.toString() ?: "global.lock"
        val lockFile = if (File(project.rootProject.projectDir, globalLockFilename).exists()) File(project.rootProject.projectDir, globalLockFilename) else File(project.projectDir, lockFilename)
        val lockData = readJsonMap(lockFile) ?: return emptyMap()
        return configurations.associate { conf ->
            val confLocks = lockData[conf.name] as? Map<*, *>
            val lockedDeps = confLocks?.filter { (it.value as? Map<*, *>)?.containsKey("locked") == true }?.mapValues { ((it.value as Map<*, *>)["locked"] as String) } ?: emptyMap()
            conf.name to lockedDeps.mapKeys { it.key.toString() }
        }
    }

    private fun readOverrideLockFileForConfigurations(
        project: Project,
        configurations: List<Configuration>
    ): Map<String, Map<String, String>> {
        val overridePath = project.findProperty(DependencyLockPlugin.OVERRIDE_FILE)?.toString() ?: return emptyMap()
        val overrideMap = readJsonMap(File(project.projectDir, overridePath))?.mapKeys { it.key.toString() }?.mapValues { it.value.toString() } ?: return emptyMap()
        return configurations.associate { conf -> conf.name to overrideMap }
    }

    private fun isReportingTasksOnly(project: Project): Boolean {
        return ReportingTasks.isReportingTasksOnly(project)
    }

    /**
     * Registers a FlowAction that runs after the build.
     * Used only for reporting-only builds (e.g. dependencies, dependencyInsight): the task defers
     * reporting (throwOnFailures = false), and this FlowAction reads BuildService and fails or logs at the end.
     */
    private fun registerFlowActionStrategy(project: Project, projectKey: String) {
        val projectName = project.name
        val shouldFailTheBuild = getShouldFailTheBuild(project)
        val lockMismatchMessage = extensionFromRoot.resolvedVersionDoesNotEqualLockedVersionMessageAddition.get()

        flowScope.always(DependencyResolutionFlowAction::class.java) { spec ->
            spec.parameters.projectName.set(projectName)
            spec.parameters.projectKey.set(projectKey)
            spec.parameters.shouldFailTheBuild.set(shouldFailTheBuild)
            spec.parameters.lockMismatchMessage.set(lockMismatchMessage)
            spec.parameters.missingVersionsMessageAddition.set(extensionFromRoot.missingVersionsMessageAddition.get())
            spec.parameters.requestedTaskNames.set(project.gradle.startParameter.taskNames.toList())
            spec.parameters.buildResult.set(flowProviders.buildWorkResult)
            spec.parameters.verificationService.set(verificationService)
        }
    }
}
