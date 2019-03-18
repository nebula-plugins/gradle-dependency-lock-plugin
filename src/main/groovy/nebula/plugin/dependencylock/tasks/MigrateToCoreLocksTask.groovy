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

import nebula.plugin.dependencylock.utils.CoreLocking
import org.gradle.api.BuildCancelledException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.tasks.TaskAction

class MigrateToCoreLocksTask extends AbstractMigrateToCoreLocksTask {
    String description = 'Migrates all dependencies to use core Gradle locks'

    @TaskAction
    void migrateUnlockedDependencies() {
        if (CoreLocking.isCoreLockingEnabled()) {
            lockSelectedConfigurations()

            lockableConfigurations().forEach { conf ->
                HashSet<String> unlockedDependencies = findUnlockedDependencies(conf)

                if (unlockedDependencies.size() > 0) {
                    List<String> sortableDeps = unlockedDependencies.toList()
                    sortableDeps.sort()

                    def configLockFile = new File(getOutputLocksDirectory(), "/${conf.name}.lockfile")

                    writeDependenciesIntoLockFile(conf, sortableDeps, configLockFile)
                }
            }
        }
    }

    static def writeDependenciesIntoLockFile(Configuration conf, List<String> sortedDeps, File configLockFile) {
        try {
            List<String> lockfileContents = configLockFile.readLines()

            List<String> previouslyUnlockedDeps = sortedDeps.findAll {
                !lockfileContents.contains(it)
            }

            def comments = new ArrayList()
            def lockedDeps = new ArrayList()
            lockfileContents.each {
                if (it.startsWith('#')) {
                    comments.add(it)
                } else if (it.matches(/\w*/)) {
                    // do nothing
                } else {
                    lockedDeps.add(it)
                }
            }

            def allDeps = lockedDeps + previouslyUnlockedDeps
            allDeps.sort()

            configLockFile.text =
                    comments.join('\n') +
                            '\n' +
                            allDeps.join('\n')

        } catch (Exception e) {
            throw new BuildCancelledException("Failed to update the ${conf.name} core lock file." +
                    " - Core lock file location: ${configLockFile.absolutePath}", e)
        }
    }

    private static Set<String> findUnlockedDependencies(Configuration conf) {
        def unlockedDependencies = new HashSet<String>()
        try {
            conf.resolvedConfiguration.firstLevelModuleDependencies
        } catch (ResolveException re) {
            re.causes.each {
                def missingDep
                try {
                    def matcher = it.getMessage() =~ /.*'(.*)'.*/
                    def results = matcher[0]
                    missingDep = results[1] as String
                } catch (Exception e) {
                    throw new BuildCancelledException("Error finding unlocked dependencies", e)
                }
                unlockedDependencies.add(missingDep)
            }
        }
        unlockedDependencies
    }
}
