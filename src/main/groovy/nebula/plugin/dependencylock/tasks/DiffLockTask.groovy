package nebula.plugin.dependencylock.tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import nebula.dependencies.comparison.*
import nebula.plugin.dependencylock.diff.DiffReportGenerator
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.deprecation.DeprecationLogger
import org.gradle.work.DisableCachingByDefault

import java.nio.charset.StandardCharsets

@DisableCachingByDefault
class DiffLockTask extends AbstractLockTask {
    @Internal
    String description = 'Diff existing lock and generated lock file'

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    File existingLockFile

    @Internal
    File updatedLockFile

    @OutputDirectory
    File outputDir = new File(project.layout.buildDirectory.getAsFile().get(), "dependency-lock")

    @OutputFile
    File diffFile = new File(outputDir, "lockdiff.${this.diffFileExtension()}")

    private String diffFileExtension() {
        DependencyLockingFeatureFlags.isPathAwareDependencyDiffEnabled() ? "json" : "txt"
    }

    @TaskAction
    def diffLocks() {
        //TODO: address Invocation of Task.project at execution time has been deprecated.
        DeprecationLogger.whileDisabled {
            ConfigurationsSet existingLock = readLocks(existingLockFile)
            ConfigurationsSet newLock = readLocks(updatedLockFile)
            if (DependencyLockingFeatureFlags.isPathAwareDependencyDiffEnabled()) {
                Map<String, List<DependencyDiff>> diffByConfiguration = new DependenciesComparison().performDiffByConfiguration(existingLock, newLock)
                DiffReportGenerator generator = Class.forName("nebula.plugin.dependencylock.diff.PathAwareDiffReportGenerator").newInstance() as DiffReportGenerator
                def lockDiff = generator.generateDiffReport(project, diffByConfiguration)
                outputDir.mkdirs()
                diffFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(lockDiff))
            } else {
                if (newLock.isEmpty()) {
                    outputDir.mkdirs()
                    diffFile.withPrintWriter(StandardCharsets.UTF_8.displayName()) { writer ->
                        writer.println('--no updated locks to diff--')
                    }
                } else {
                    List<DependencyDiff> diff = new DependenciesComparison().performDiff(existingLock, newLock)
                    writeDiff(diff)
                }
            }
        }
    }

    ConfigurationsSet readLocks(File file) {
        if (!(file?.exists())) {
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

    void writeDiff(List<DependencyDiff> diff) {
        outputDir.mkdirs()

        diffFile.withPrintWriter(StandardCharsets.UTF_8.displayName()) { writer ->
            def newDeps = diff.findAll { it.isNew() }
            if (!newDeps.isEmpty()) {
                writer.println('new:')
                newDeps.each { newDiff ->
                    writer.println(newDiff.newDiffString())
                }
            }

            def removedDeps = diff.findAll { it.isRemoved() }
            if (!removedDeps.isEmpty()) {
                writer.println('removed:')
                removedDeps.each { removedDiff ->
                    writer.println(removedDiff.removedDiffString())
                }
            }

            def updatedDeps = diff.findAll { it.isUpdated() }
            if (!updatedDeps.isEmpty()) {
                writer.println('updated:')
                updatedDeps.each { updatedDiff ->
                    writer.println(updatedDiff.updatedDiffString())
                }
            }

            def inconsistentDeps = diff.findAll { it.isInconsistent() }
            if (!inconsistentDeps.isEmpty()) {
                writer.println('inconsistent:')
                inconsistentDeps.each { inconsistentDiff ->
                    inconsistentDiff.inconsistentDiffList().each { item ->
                        writer.println(item)
                    }
                }
            }
        }
    }
}
