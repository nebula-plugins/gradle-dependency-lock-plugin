package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.DiffLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.MigrateLockedDepsToCoreLocksTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import nebula.test.IntegrationTestKitSpec


class DependencyLockConfigurationAvoidanceSpec extends IntegrationTestKitSpec {
    def 'task configuration avoidance'() {
        given:
        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java'
            }
        """.stripIndent()

        when:
        def result = runTasks('-Dorg.gradle.internal.tasks.stats', 'help')

        then:
        result.output.contains('class org.gradle.configuration.Help 1')
        !result.output.contains("class ${SaveLockTask.class.name}")
        !result.output.contains("class ${GenerateLockTask.class.name}")
        !result.output.contains("class ${UpdateLockTask.class.name}")
        !result.output.contains("class ${MigrateToCoreLocksTask.class.name}")
        !result.output.contains("class ${MigrateLockedDepsToCoreLocksTask.class.name}")
        !result.output.contains("class ${CommitLockTask.class.name}")
        !result.output.contains("class ${DiffLockTask.class.name}")
    }
}
