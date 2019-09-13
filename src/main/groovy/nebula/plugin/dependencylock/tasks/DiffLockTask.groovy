package nebula.plugin.dependencylock.tasks

import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.Sortable
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets

class DiffLockTask extends AbstractLockTask {
    @Internal
    String description = 'Diff existing lock and generated lock file'

    @InputFile
    @Optional
    File existingLockFile

    @InputFile
    @Optional
    File updatedLockFile

    @OutputDirectory
    File outputDir = new File(project.buildDir, "dependency-lock")

    @OutputFile
    File diffFile = new File(outputDir, "lockdiff.txt")

    @TaskAction
    def diffLocks() {
        LockFile existingLock = readLocks(existingLockFile)
        LockFile newLock = readLocks(updatedLockFile)
        if (newLock.isEmpty()) {
            outputDir.mkdirs()
            diffFile.withPrintWriter(StandardCharsets.UTF_8.displayName()) { writer ->
                writer.println('--no updated locks to diff--')
            }
        } else {
            List<DependencyDiff> diff = performDiff(existingLock, newLock)
            writeDiff(diff)
        }
    }

    LockFile readLocks(File file) {
        if (!(file?.exists())) {
            return new LockFile([:])
        }
        def contents = new JsonSlurper().parse(file)

        Map<String, DependencyLocks> lock = contents.collectEntries { configuration, dependencies ->
            [(configuration): new DependencyLocks(dependencies.collectEntries { dependency, props ->
                [(dependency): props.locked]
            })]
        }

        return new LockFile(lock)
    }

    List<DependencyDiff> performDiff(LockFile old, LockFile updated) {
        def memory = [:].withDefault { String dependency -> new DependencyDiff(dependency) }
        Set<String> configurations = old.configurations() + updated.configurations()
        configurations.forEach { configuration ->
            DependencyLocks oldLocks = old.locksForConfiguration(configuration)
            DependencyLocks updatedLocks = updated.locksForConfiguration(configuration)

            Set<String> allDependencies = oldLocks.allDependencies() + updatedLocks.allDependencies()
            allDependencies.forEach { dependency ->
                String oldVersion = oldLocks.lockedVersion(dependency)
                String updatedVersion = updatedLocks.lockedVersion(dependency)

                if (oldVersion != updatedVersion) {
                    DependencyDiff diff = memory.get(dependency)
                    diff.addDiff(oldVersion, updatedVersion, configuration)
                }
            }
        }

        memory.values().toSorted()
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

    @Canonical
    private static class DependencyLocks {
        Map<String, String> locks

        String lockedVersion(String dependency) {
            locks.getOrDefault(dependency, '')
        }

        Set<String> allDependencies() {
            locks.keySet()
        }
    }

    @Canonical
    private static class LockFile {
        Map<String, DependencyLocks> locks

        DependencyLocks locksForConfiguration(String configuration) {
            locks.getOrDefault(configuration, new DependencyLocks([:]))
        }

        Set<String> configurations() {
            locks.keySet()
        }

        Boolean isEmpty() {
            locks.isEmpty()
        }
    }

    @Canonical
    @Sortable(includes = ['dependency'])
    private static class DependencyDiff {
        String dependency
        Map<VersionDiff, DiffInfo> diff = [:].withDefault { versionDiff -> new DiffInfo(versionDiff) }

        void addDiff(String oldVersion, String updatedVersion, String configuration) {
            diff.get(new VersionDiff(oldVersion, updatedVersion)).addConfiguration(configuration)
        }

        Boolean isNew() {
            diff.size() == 1 && diff.values()[0].oldVersion == '' && diff.values()[0].updatedVersion != ''
        }

        String newDiffString() {
            "  $dependency: ${diff.values()[0].updatedVersion}"
        }

        Boolean isRemoved() {
            diff.size() == 1 && diff.values()[0].oldVersion != '' && diff.values()[0].updatedVersion == ''
        }

        String removedDiffString() {
            "  $dependency"
        }

        Boolean isUpdated() {
            diff.size() == 1 && diff.values()[0].oldVersion != '' && diff.values()[0].updatedVersion != ''
        }

        String updatedDiffString() {
            "  $dependency: ${diff.values()[0].oldVersion} -> ${diff.values()[0].updatedVersion}"
        }

        Boolean isInconsistent() {
            diff.size() > 1
        }

        List<String> inconsistentDiffList() {
            diff.values().sort().inject(["  $dependency:"]) { list, diffInfo ->
                list << "    ${diffInfo.oldVersion} -> ${diffInfo.updatedVersion} [${diffInfo.configurations.sort().join(',')}]"
            }
        }
    }

    @Canonical
    @Sortable
    private static class VersionDiff {
        String oldVersion
        String updatedVersion
    }

    @Canonical
    @Sortable(includes = ['versionDiff'])
    private static class DiffInfo {
        @Delegate VersionDiff versionDiff
        Set<String> configurations = [] as Set

        void addConfiguration(String configuration) {
            configurations.add(configuration)
        }
    }
}
