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
                    println "lockFile: " + ext.lockFile
                    println "globalLockFile: " + ext.globalLockFile
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
                lockFileProperty.set('custom.lock')
                includeTransitives.set(true)
                lockAfterEvaluating.set(false)
            }

            task checkConfig {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "lockFile: " + ext.lockFile
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
                    println "lockFile: " + ext.lockFile
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
                configurationNamesProperty.add('runtimeClasspath')
                configurationNamesProperty.add('compileClasspath')
                skippedDependenciesProperty.add('com.example:skip-me')
            }

            task checkConfig {
                def ext = dependencyLock  // Capture during configuration
                doLast {
                    println "configs: " + ext.configurationNames
                    println "skipped: " + ext.skippedDependencies
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

    def 'skippedDependencies supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                skippedDependencies = ['com.example:foo', 'com.example:bar'] as Set
            }

            task checkSkipped {
                def ext = dependencyLock
                doLast {
                    println "skipped: " + ext.skippedDependencies
                }
            }
        """

        when:
        def result = runTasks('checkSkipped')

        then:
        result.output.contains('com.example:foo')
        result.output.contains('com.example:bar')
    }

    def 'skippedDependencies getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.skippedDependencies
                    println "isSet: " + (value instanceof Set)
                    println "isSetProperty: " + value.getClass().name.contains('SetProperty')
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isSet: true')
        result.output.contains('isSetProperty: false')
    }

    def 'skippedDependencies supports += operator (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                skippedDependencies = ['com.example:existing'] as Set
            }
            dependencyLock.skippedDependencies += 'com.example:added'

            task checkSkipped {
                def ext = dependencyLock
                doLast {
                    println "skipped: " + ext.skippedDependencies
                }
            }
        """

        when:
        def result = runTasks('checkSkipped')

        then:
        result.output.contains('com.example:existing')
        result.output.contains('com.example:added')
    }

    def 'skippedConfigurationNamesPrefixes supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                skippedConfigurationNamesPrefixes = ['test', 'compile'] as Set
            }

            task checkSkipped {
                def ext = dependencyLock
                doLast {
                    println "skipped: " + ext.skippedConfigurationNamesPrefixes
                }
            }
        """

        when:
        def result = runTasks('checkSkipped')

        then:
        result.output.contains('test')
        result.output.contains('compile')
    }

    def 'skippedConfigurationNamesPrefixes getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.skippedConfigurationNamesPrefixes
                    println "isSet: " + (value instanceof Set)
                    println "isSetProperty: " + value.getClass().name.contains('SetProperty')
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isSet: true')
        result.output.contains('isSetProperty: false')
    }

    def 'skippedConfigurationNamesPrefixes supports += operator (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                skippedConfigurationNamesPrefixes = ['test'] as Set
            }
            dependencyLock.skippedConfigurationNamesPrefixes += 'runtime'

            task checkSkipped {
                def ext = dependencyLock
                doLast {
                    println "skipped: " + ext.skippedConfigurationNamesPrefixes
                }
            }
        """

        when:
        def result = runTasks('checkSkipped')

        then:
        result.output.contains('test')
        result.output.contains('runtime')
    }

    def 'configurationNames supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                configurationNames = ['runtimeClasspath', 'compileClasspath'] as Set
            }

            task checkConfig {
                def ext = dependencyLock
                doLast {
                    println "configs: " + ext.configurationNames
                }
            }
        """

        when:
        def result = runTasks('checkConfig')

        then:
        result.output.contains('runtimeClasspath')
        result.output.contains('compileClasspath')
    }

    def 'configurationNames getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.configurationNames
                    println "isSet: " + (value instanceof Set)
                    println "isSetProperty: " + value.getClass().name.contains('SetProperty')
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isSet: true')
        result.output.contains('isSetProperty: false')
    }

    def 'updateDependencies supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                updateDependencies = ['com.example:foo', 'com.example:bar'] as Set
            }

            task checkUpdate {
                def ext = dependencyLock
                doLast {
                    println "updates: " + ext.updateDependencies
                }
            }
        """

        when:
        def result = runTasks('checkUpdate')

        then:
        result.output.contains('com.example:foo')
        result.output.contains('com.example:bar')
    }

    def 'updateDependencies getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.updateDependencies
                    println "isSet: " + (value instanceof Set)
                    println "isSetProperty: " + value.getClass().name.contains('SetProperty')
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isSet: true')
        result.output.contains('isSetProperty: false')
    }

    def 'additionalConfigurationsToLock supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                additionalConfigurationsToLock = ['annotationProcessor'] as Set
            }

            task checkAdditional {
                def ext = dependencyLock
                doLast {
                    println "additional: " + ext.additionalConfigurationsToLock
                }
            }
        """

        when:
        def result = runTasks('checkAdditional')

        then:
        result.output.contains('annotationProcessor')
    }

    def 'additionalConfigurationsToLock getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.additionalConfigurationsToLock
                    println "isSet: " + (value instanceof Set)
                    println "isSetProperty: " + value.getClass().name.contains('SetProperty')
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isSet: true')
        result.output.contains('isSetProperty: false')
    }

    def 'lockFile getter returns String not Property (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.lockFile
                    println "isString: " + (value instanceof String)
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isString: true')
    }

    def 'lockFile supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                lockFile = 'custom.lock'
            }

            task checkConfig {
                def ext = dependencyLock
                doLast {
                    println "lockFile: " + ext.lockFile
                }
            }
        """

        when:
        def result = runTasks('checkConfig')

        then:
        result.output.contains('lockFile: custom.lock')
    }

    def 'globalLockFile getter returns String not Property (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyLock
                doLast {
                    def value = ext.globalLockFile
                    println "isString: " + (value instanceof String)
                }
            }
        """

        when:
        def result = runTasks('checkType')

        then:
        result.output.contains('isString: true')
    }

    def 'globalLockFile supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyLock {
                globalLockFile = 'custom-global.lock'
            }

            task checkConfig {
                def ext = dependencyLock
                doLast {
                    println "globalLockFile: " + ext.globalLockFile
                }
            }
        """

        when:
        def result = runTasks('checkConfig')

        then:
        result.output.contains('globalLockFile: custom-global.lock')
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

