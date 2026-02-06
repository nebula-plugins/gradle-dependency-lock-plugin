package nebula.plugin.dependencylock

import spock.lang.Ignore
import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.DiffLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.MigrateLockedDepsToCoreLocksTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask


class DependencyLockConfigurationAvoidanceSpec extends BaseIntegrationTestKitSpec {
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
    
    def 'plugin loads with configuration cache enabled'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
        """
        
        when: 'first run stores configuration cache'
        def firstRun = runTasks('--configuration-cache', 'help')
        
        then:
        !firstRun.output.contains('configuration cache problems')
        
        when: 'second run reuses configuration cache'
        def secondRun = runTasks('--configuration-cache', 'help')
        
        then:
        secondRun.output.contains('Reusing configuration cache') ||
            secondRun.output.contains('Configuration cache entry reused')
    }
    
    @Ignore
    def 'debug configuration cache problems'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java'
            }
            
            repositories { mavenCentral() }
            
            dependencies {
                implementation 'com.google.guava:guava:30.0-jre'
            }
        """
        
        when:
        def result = runTasks(
            '--configuration-cache',
            '--configuration-cache-problems=warn',  // Don't fail, just warn
            '--stacktrace',
            'help'
        )
        
        then:
        assert result.success
        // Check detailed report at build/reports/configuration-cache/<hash>/
        println "Configuration cache report location: ${projectDir}/build/reports/configuration-cache/"
        
        // For now, we expect configuration cache problems since plugin isn't fully compatible yet
        // This test helps us see what problems exist during modernization
    }
}
