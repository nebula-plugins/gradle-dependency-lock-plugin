package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.MigrateLockedDepsToCoreLocksTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.test.IntegrationTestKitSpec

class DependencyLockConfigurationAvoidanceSpec extends IntegrationTestKitSpec {
    def 'task configuration avoidance'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
        """.stripIndent()

        when:
        def result = runTasks('-Dorg.gradle.internal.tasks.stats', 'help')

        then:
        result.output.contains('class org.gradle.configuration.Help 1')
//        !result.output.contains("class ${SaveLockTask.class.name}")
//        !result.output.contains("class ${GenerateLockTask.class.name}")
//        !result.output.contains("class ${UpdateLockTask.class.name}")
        !result.output.contains("class ${MigrateToCoreLocksTask.class.name}")
        !result.output.contains("class ${MigrateLockedDepsToCoreLocksTask.class.name}")
        !result.output.contains("class ${CommitLockTask.class.name}")
    }

    def 'task configuration avoidance - commitLock is registered with scm plugin'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'nebula.gradle-scm'
            }
        """.stripIndent()

        when:
        def result = runTasks('-Dorg.gradle.internal.tasks.stats', 'help')

        then:
        result.output.contains('class org.gradle.configuration.Help 1')
//        !result.output.contains("class ${SaveLockTask.class.name}")
//        !result.output.contains("class ${GenerateLockTask.class.name}")
//        !result.output.contains("class ${UpdateLockTask.class.name}")
        !result.output.contains("class ${MigrateToCoreLocksTask.class.name}")
        !result.output.contains("class ${MigrateLockedDepsToCoreLocksTask.class.name}")
        !result.output.contains("class ${CommitLockTask.class.name}")
    }
}
