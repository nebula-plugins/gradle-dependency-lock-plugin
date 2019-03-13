/**
 *
 *  Copyright 2018 Netflix, Inc.
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

import nebula.plugin.dependencylock.ConfigurationsToLockFinder
import nebula.plugin.dependencylock.DependencyLockReader
import nebula.plugin.dependencylock.utils.CoreLocking
import org.gradle.api.BuildCancelledException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

class MigrateToCoreLocksTask extends AbstractLockTask {
    String description = 'Migrates Nebula locks to core Gradle locks'
    private static final Logger LOGGER = Logging.getLogger(MigrateToCoreLocksTask)

    @InputFile
    File inputLockFile

    @OutputDirectory
    File outputLocksDirectory

    @TaskAction
    void migrate() {
        if (CoreLocking.isCoreLockingEnabled()) {
            def configurationsToLock = new ConfigurationsToLockFinder(project.configurations).findConfigurationsToLock()
            project.configurations.each {
                if (configurationsToLock.contains(it.name)) {
                    it.resolutionStrategy.activateDependencyLocking()
                }
            }

            if (getInputLockFile().exists()) {
                LOGGER.warn("Migrating legacy locks to core Gradle locking. This will remove legacy locks.\n" +
                        " - Legacy lock: ${getInputLockFile().absolutePath}\n" +
                        " - Core Gradle locks: ${getOutputLocksDirectory().absoluteFile}")
                if (!getOutputLocksDirectory().exists()) {
                    createOutputLocksDirectory()
                }

                def lockReader = new DependencyLockReader(project)

                project.configurations.forEach { conf ->
                    def dependenciesForConf = new ArrayList()
                    def locks = lockReader.readLocks(conf, getInputLockFile()) // as Map<String, Map<String, String>>

                    if (locks != null) {
                        for (Map.Entry<String, ArrayList<String>> entry : locks.entrySet()) {
                            def groupAndName = entry.key as String
                            def entryLockedValue = (entry.value as Map<String, String>)["locked"]
                            if (entryLockedValue != null) {
                                def lockedVersion = entryLockedValue as String
                                dependenciesForConf.add("$groupAndName:$lockedVersion")
                            } else {
                                LOGGER.info("No locked version for '$groupAndName' to migrate in $conf")
                            }
                        }
                    }

                    def configLockFile = new File(getOutputLocksDirectory(), "/${conf.name}.lockfile")
                    if (!configLockFile.exists()) {
                        configLockFile.createNewFile()
                        configLockFile.write("# This is a file for dependency locking, migrated from Nebula locks.\n")

                        dependenciesForConf.sort()

                        dependenciesForConf.forEach { depAndVersion ->
                            configLockFile.append("$depAndVersion\n")
                        }
                    }
                }

                deleteInputLockFile()
            }
        }
    }

    private void deleteInputLockFile() {
        def failureToDeleteInputLockFileMessage = "Failed to delete legacy locks.\n" +
                "Please remove the legacy lockfile manually.\n" +
                " - Legacy lock: ${getInputLockFile().absolutePath}"
        try {
            if (!getInputLockFile().delete()) {
                throw new BuildCancelledException(failureToDeleteInputLockFileMessage)
            }
        } catch (Exception e) {
            throw new BuildCancelledException(failureToDeleteInputLockFileMessage, e)
        }
    }

    private void createOutputLocksDirectory() {
        def failureToDeleteOutputLocksDirectoryMessage = "Failed to create core lock directory. Check your permissions.\n" +
                " - Core lock directory: ${getOutputLocksDirectory().absolutePath}"
        try {
            if (!getOutputLocksDirectory().mkdirs()) {
                throw new BuildCancelledException(failureToDeleteOutputLocksDirectoryMessage)
            }
        } catch (Exception e) {
            throw new BuildCancelledException(failureToDeleteOutputLocksDirectoryMessage, e)
        }
    }
}
