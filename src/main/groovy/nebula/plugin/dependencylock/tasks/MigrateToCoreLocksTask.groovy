/**
 *
 *  Copyright 2014-2019 Netflix, Inc.
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

package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.utils.ConfigurationFilters
import nebula.plugin.dependencylock.utils.CoreLocking
import nebula.plugin.dependencylock.utils.CoreLockingHelper
import org.gradle.api.BuildCancelledException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

class MigrateToCoreLocksTask extends AbstractMigrateToCoreLocksTask {
    @Internal
    String description = 'Migrates all dependencies to use core Gradle locks'

    @TaskAction
    void migrateUnlockedDependencies() {
        if (CoreLocking.isCoreLockingEnabled()) {
            def coreLockingHelper = new CoreLockingHelper(project)
            coreLockingHelper.lockSelectedConfigurations(getConfigurationNames())

            Map<String, List<String>> dependenciesInConfigs = new HashMap<>()

            def migratingUnlockedDependenciesClosure = {
                HashSet<String> unlockedDependencies = MigrateToCoreLocksTask.findUnlockedDependencies(it)

                if (unlockedDependencies.size() > 0) {

                    unlockedDependencies.toList().each { lockedDependency ->
                        if (dependenciesInConfigs.containsKey(lockedDependency)) {
                            if (!dependenciesInConfigs[lockedDependency].contains(it.name))
                                dependenciesInConfigs[lockedDependency].add(it.name)
                        } else {
                            dependenciesInConfigs.put(lockedDependency, [it.name])
                        }
                    }
                }
            }
            coreLockingHelper.migrateUnlockedDependenciesClosure(getConfigurationNames(), migratingUnlockedDependenciesClosure)

            writeDependenciesIntoLockFile(dependenciesInConfigs, outputLock)
        }
    }

    static def writeDependenciesIntoLockFile(Map<String, List<String>> unlockedDeps, File configLockFile) {
        try {
            def comments = configLockFile.readLines().findAll() {
                it.startsWith('#')
            }

            Map<String, List<String>> lockfileContents = coreLockContent(configLockFile)

            unlockedDeps.each {unlockedDep ->
                if (lockfileContents.containsKey(unlockedDep.key)) {
                    unlockedDep.value.each {unlockedConfig ->
                        if (!lockfileContents[unlockedDep.key].contains(unlockedConfig)) {
                            lockfileContents[unlockedDep.key].add(unlockedConfig)
                        }
                    }
                } else {
                    lockfileContents.put(unlockedDep.key, unlockedDep.value)
                }
            }

            configLockFile.text = comments.join('\n') + '\n'
            lockfileContents.sort { it.key }.each {
                configLockFile.append("${it.key}=${it.value.sort().join(",")}\n")
            }

        } catch (Exception e) {
            throw new BuildCancelledException("Failed to update the core lock file." +
                    " - Core lock file location: ${configLockFile.absolutePath}", e)
        }
    }

    static private Map<String, List<String>> coreLockContent(File lockFile) {
        lockFile.readLines().findAll {!it.startsWith("#")}.collectEntries {
            it.split('=').toList()
        }.collectEntries {[it.key ,it.value.split(',')]}
    }

    private static Set<String> findUnlockedDependencies(Configuration conf) {
        def unlockedDependencies = new HashSet<String>()

        if (!ConfigurationFilters.safelyHasAResolutionAlternative(conf)) {
            try {
                conf.resolvedConfiguration.firstLevelModuleDependencies
            } catch (ResolveException re) {
                re.causes.each {
                    def unlockedDep
                    try {
                        def matcher = it.getMessage() =~ /.*'(.*)'.*/
                        def results = matcher[0] as List
                        unlockedDep = results[1] as String
                    } catch (Exception e) {
                        throw new BuildCancelledException("Error finding unlocked dependency from '${it.getMessage()}'", e)
                    }
                    unlockedDependencies.add(unlockedDep)
                }
            }
        }
        unlockedDependencies
    }
}
