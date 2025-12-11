package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec

/**
 * Tests to validate that DependencyLockExtension uses Property API correctly.
 */
class DependencyLockExtensionPropertySpec extends BaseIntegrationTestKitSpec {
    
    def 'extension properties use default conventions'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            task checkDefaults {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "lockFile: " + ext.lockFile.get()
                    println "globalLockFile: " + ext.globalLockFile.get()
                    println "includeTransitives: " + ext.includeTransitives.get()
                    println "lockAfterEvaluating: " + ext.lockAfterEvaluating.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkDefaults')
        
        then:
        result.output.contains('lockFile: dependencies.lock')
        result.output.contains('globalLockFile: global.lock')
        result.output.contains('includeTransitives: false')
        result.output.contains('lockAfterEvaluating: true')
    }
    
    def 'extension properties can be configured via set()'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            dependencyLock {
                lockFile.set('custom.lock')
                includeTransitives.set(true)
                lockAfterEvaluating.set(false)
            }
            
            task checkConfig {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "lockFile: " + ext.lockFile.get()
                    println "includeTransitives: " + ext.includeTransitives.get()
                    println "lockAfterEvaluating: " + ext.lockAfterEvaluating.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkConfig')
        
        then:
        result.output.contains('lockFile: custom.lock')
        result.output.contains('includeTransitives: true')
        result.output.contains('lockAfterEvaluating: false')
    }
    
    def 'extension properties can be configured via Groovy shorthand'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            dependencyLock {
                lockFile = 'shorthand.lock'  // Groovy property shorthand
                includeTransitives = true
            }
            
            task checkConfig {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "lockFile: " + ext.lockFile.get()
                    println "includeTransitives: " + ext.includeTransitives.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkConfig')
        
        then:
        result.output.contains('lockFile: shorthand.lock')
        result.output.contains('includeTransitives: true')
    }
    
    def 'set properties work with lazy evaluation'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            dependencyLock {
                configurationNames.add('runtimeClasspath')
                configurationNames.add('compileClasspath')
                skippedDependencies.add('com.example:skip-me')
            }
            
            task checkConfig {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "configs: " + ext.configurationNames.get()
                    println "skipped: " + ext.skippedDependencies.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkConfig')
        
        then:
        result.output.contains('runtimeClasspath')
        result.output.contains('compileClasspath')
        result.output.contains('skip-me')
    }
    
    def 'commit extension properties use default conventions'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            task checkDefaults {
                def ext = commitDependencyLock  // Capture during configuration
                doLast {
                    println "message: " + ext.message.get()
                    println "shouldCreateTag: " + ext.shouldCreateTag.get()
                    println "remoteRetries: " + ext.remoteRetries.get()
                    println "tag: " + ext.tag.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkDefaults')
        
        then:
        result.output.contains('message: Committing dependency lock files')
        result.output.contains('shouldCreateTag: false')
        result.output.contains('remoteRetries: 3')
        result.output.contains('tag: LockCommit-')
    }
    
    def 'commit extension properties can be configured'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }
            
            commitDependencyLock {
                message = 'Custom commit message'
                shouldCreateTag = true
                tag = 'v1.0.0'
                remoteRetries = 5
            }
            
            task checkConfig {
                def ext = commitDependencyLock  // Capture during configuration
                doLast {
                    println "message: " + ext.message.get()
                    println "shouldCreateTag: " + ext.shouldCreateTag.get()
                    println "tag: " + ext.tag.get()
                    println "remoteRetries: " + ext.remoteRetries.get()
                }
            }
        """
        
        when:
        def result = runTasks('checkConfig')
        
        then:
        result.output.contains('message: Custom commit message')
        result.output.contains('shouldCreateTag: true')
        result.output.contains('tag: v1.0.0')
        result.output.contains('remoteRetries: 5')
    }
}

