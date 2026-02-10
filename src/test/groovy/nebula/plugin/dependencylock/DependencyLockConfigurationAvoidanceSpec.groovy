package nebula.plugin.dependencylock

import spock.lang.Ignore
import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.GlobalLockDeprecations
import nebula.plugin.dependencylock.tasks.CommitLockTask
import nebula.plugin.dependencylock.tasks.DiffLockTask
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.tasks.MigrateLockedDepsToCoreLocksTask
import nebula.plugin.dependencylock.tasks.MigrateToCoreLocksTask
import nebula.plugin.dependencylock.tasks.SaveLockTask
import nebula.plugin.dependencylock.tasks.UpdateLockTask
import org.junit.Rule
import org.junit.contrib.java.lang.system.ProvideSystemProperty


class DependencyLockConfigurationAvoidanceSpec extends BaseIntegrationTestKitSpec implements GlobalLockDeprecations {
    @Rule
    public final ProvideSystemProperty ignoreGlobalLockDeprecations = globalLockDeprecationRule()
    def 'running help does not configure lock plugin tasks'() {
        given:
        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java'
            }
        """.stripIndent()

        when: 'only help runs; task stats show which task types were configured'
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
    
    def 'generateLock works with configuration cache'() {
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
        
        when: 'first run stores configuration cache'
        def firstRun = runTasks('--configuration-cache', 'generateLock')
        
        then: 'no configuration cache problems'
        !firstRun.output.contains('configuration cache problems')
        firstRun.output.contains('Configuration cache entry stored')
        
        when: 'second run reuses configuration cache'
        def secondRun = runTasks('--configuration-cache', 'generateLock')
        
        then: 'configuration cache is reused'
        secondRun.output.contains('Reusing configuration cache') ||
            secondRun.output.contains('Configuration cache entry reused')
        !secondRun.output.contains('configuration cache problems')
    }
    
    def 'updateLock works with configuration cache'() {
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
        
        and: 'create initial lock file'
        def lockFile = new File(projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
                "compileClasspath": {
                    "com.google.guava:guava": {
                        "locked": "30.0-jre"
                    }
                },
                "runtimeClasspath": {
                    "com.google.guava:guava": {
                        "locked": "30.0-jre"
                    }
                }
            }
        '''.stripIndent()
        
        when: 'run updateLock with configuration cache'
        def firstRun = runTasks('--configuration-cache', 'updateLock', '-PdependencyLock.updateDependencies=com.google.guava:guava')
        
        then: 'no configuration cache problems'
        !firstRun.output.contains('configuration cache problems')
        
        when: 'second run reuses configuration cache'
        def secondRun = runTasks('--configuration-cache', 'updateLock', '-PdependencyLock.updateDependencies=com.google.guava:guava')
        
        then: 'configuration cache is reused'
        secondRun.output.contains('Reusing configuration cache') ||
            secondRun.output.contains('Configuration cache entry reused')
    }
    
    def 'saveLock works with configuration cache'() {
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
        
        when: 'generate lock first'
        runTasks('generateLock')
        
        and: 'run saveLock with configuration cache'
        def result = runTasks('--configuration-cache', 'saveLock')
        
        then: 'no configuration cache problems'
        !result.output.contains('configuration cache problems')
    }
    
    def 'diffLock works with configuration cache'() {
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
        
        when: 'generate and save lock'
        runTasks('generateLock', 'saveLock')
        
        and: 'update dependencies and generate new lock'
        buildFile << """
            dependencies {
                implementation 'com.google.guava:guava:31.0-jre'
            }
        """
        runTasks('generateLock')
        
        and: 'first run with configuration cache'
        def firstRun = runTasks('--configuration-cache', 'diffLock')
        
        then: 'no configuration cache problems'
        !firstRun.output.contains('configuration cache problems')
        
        when: 'second run reuses configuration cache'
        def secondRun = runTasks('--configuration-cache', 'diffLock')
        
        then: 'configuration cache is reused'
        secondRun.output.contains('Reusing configuration cache') ||
            secondRun.output.contains('Configuration cache entry reused')
        !secondRun.output.contains('configuration cache problems')
    }
    
    def 'commitLock works with configuration cache'() {
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
        
        and: 'initialize git repo properly'
        "git init".execute(null, projectDir).waitFor()
        "git config user.email test@example.com".execute(null, projectDir).waitFor()
        "git config user.name Test".execute(null, projectDir).waitFor()
        new File(projectDir, 'build.gradle').text  // Ensure file exists
        "git add .".execute(null, projectDir).waitFor()
        "git commit -m initial".execute(null, projectDir).waitFor()
        
        when: 'generate and save lock'
        runTasks('generateLock', 'saveLock')
        
        and: 'run with configuration cache'
        def result = runTasks('--configuration-cache', 'commitLock')
        
        then: 'no configuration cache problems'
        !result.output.contains('configuration cache problems')
    }
    
    def 'migrateToCoreLocks is NOT configuration cache compatible (documented limitation)'() {
        given:
        def gradleProperties = new File(projectDir, 'gradle.properties')
        gradleProperties.text = '''
            systemProp.nebula.features.coreLockingSupport=true
            org.gradle.configuration-cache=true
        '''.stripIndent()
        
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
        
        and: 'create a dependencies.lock file'
        new File(projectDir, 'dependencies.lock').text = '''\
            {
                "compileClasspath": {
                    "com.google.guava:guava": {
                        "locked": "30.0-jre"
                    }
                }
            }
        '''.stripIndent()
        
        when: 'run with configuration cache - expecting failure due to config cache incompatibility'
        def result = runTasksAndFail('migrateToCoreLocks')
        
        then: 'task is marked not compatible'
        result.output.contains('not compatible with the configuration cache') ||
            result.output.contains("invocation of 'Task.project' at execution time is unsupported")
    }
    
    def 'migrateLockedDepsToCoreLocks is NOT configuration cache compatible (documented limitation)'() {
        given:
        def gradleProperties = new File(projectDir, 'gradle.properties')
        gradleProperties.text = '''
            systemProp.nebula.features.coreLockingSupport=true
            org.gradle.configuration-cache=true
        '''.stripIndent()
        
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
        
        when: 'run with configuration cache - expecting failure due to config cache incompatibility'
        def result = runTasksAndFail('migrateLockeDepsToCoreLocks')
        
        then: 'task is marked not compatible'
        result.output.contains('not compatible with the configuration cache') ||
            result.output.contains("invocation of 'Task.project' at execution time is unsupported") ||
            result.output.contains("no lockfile at expected location")
    }
    
    def 'tasks --all with configuration cache lists lock plugin tasks without config cache failures'() {
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
        
        when: 'run tasks --all with configuration cache (tasks --all configures all tasks by design)'
        def result = runTasks(
            '--configuration-cache',
            '--configuration-cache-problems=fail',
            'tasks',
            '--all'
        )
        
        then: 'no configuration cache problems'
        !result.output.contains('configuration cache problems found')
        !result.output.contains('configuration cache problems')
        
        and: 'lock plugin task names appear in the task list'
        result.output.contains('generateLock')
        result.output.contains('updateLock')
        result.output.contains('saveLock')
        result.output.contains('diffLock')
    }
    
    def 'generateGlobalLock is NOT configuration cache compatible (documented limitation)'() {
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
        
        when: 'run with configuration cache'
        def result = runTasks('--configuration-cache', 'generateGlobalLock')
        
        then: 'task runs but is marked not compatible'
        result.output.contains('not compatible with the configuration cache') ||
            result.output.contains('Calculating task graph')
    }
    
    def 'updateGlobalLock is NOT configuration cache compatible (documented limitation)'() {
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
        
        and: 'create initial global lock file'
        def lockFile = new File(projectDir, 'global.lock')
        lockFile.text = '''\
            {
                "_global_": {
                    "com.google.guava:guava": {
                        "locked": "30.0-jre"
                    }
                }
            }
        '''.stripIndent()
        
        when: 'run with configuration cache'
        def result = runTasks('--configuration-cache', 'updateGlobalLock', '-PdependencyLock.updateDependencies=com.google.guava:guava')
        
        then: 'task runs but is marked not compatible'
        result.output.contains('not compatible with the configuration cache') ||
            result.output.contains('Calculating task graph')
    }
    
    def 'saveGlobalLock is NOT configuration cache compatible (documented limitation)'() {
        given:
        disableConfigurationCache()  // Global lock tasks don't work with config cache
        
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
        
        when: 'generate global lock first'
        runTasks('generateGlobalLock')
        
        and: 'run with configuration cache to document incompatibility - expecting failure'
        def result = runTasksAndFail('--configuration-cache', 'saveGlobalLock')
        
        then: 'task is marked not compatible'
        result.output.contains('not compatible with the configuration cache') ||
            result.output.contains("invocation of 'Task.project' at execution time is unsupported")
    }
}
