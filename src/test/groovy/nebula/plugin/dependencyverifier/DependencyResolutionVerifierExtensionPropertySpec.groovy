package nebula.plugin.dependencyverifier

import nebula.plugin.BaseIntegrationTestKitSpec

/**
 * Validates that DependencyResolutionVerifierExtension SetProperty fields expose backward-compatible
 * Set&lt;String&gt; getter/setter bridges so build scripts using Groovy = assignment continue to work.
 */
class DependencyResolutionVerifierExtensionPropertySpec extends BaseIntegrationTestKitSpec {

    def 'verifier extension properties use default conventions'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            task checkDefaults {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    println "shouldFailTheBuild: " + ext.shouldFailTheBuild
                    println "configurationsToExclude: " + ext.configurationsToExclude
                    println "missingVersionsMessageAddition: '" + ext.missingVersionsMessageAddition + "'"
                    println "resolvedVersionMsg: '" + ext.resolvedVersionDoesNotEqualLockedVersionMessageAddition + "'"
                    println "tasksToExclude: " + ext.tasksToExclude
                    println "enableLockFileValidation: " + ext.enableLockFileValidation
                }
            }
        """
        when:
        def result = runTasks('checkDefaults')
        then:
        result.output.contains('shouldFailTheBuild: true')
        result.output.contains('configurationsToExclude: []')
        result.output.contains("missingVersionsMessageAddition: ''")
        result.output.contains("resolvedVersionMsg: ''")
        result.output.contains('tasksToExclude: []')
        result.output.contains('enableLockFileValidation: true')
    }

    def 'configurationsToExclude supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyResolutionVerifierExtension {
                configurationsToExclude = ['testRuntimeClasspath', 'testCompileClasspath'] as Set
            }

            task checkConfig {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    println "excluded: " + ext.configurationsToExclude
                }
            }
        """

        when:
        def result = runTasks('checkConfig')

        then:
        result.output.contains('testRuntimeClasspath')
        result.output.contains('testCompileClasspath')
    }

    def 'configurationsToExclude getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.configurationsToExclude
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

    def 'tasksToExclude supports Groovy = assignment (backward compat)'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            dependencyResolutionVerifierExtension {
                tasksToExclude = ['help', 'dependencies'] as Set
            }

            task checkConfig {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    println "excluded: " + ext.tasksToExclude
                }
            }
        """

        when:
        def result = runTasks('checkConfig')

        then:
        result.output.contains('help')
        result.output.contains('dependencies')
    }

    def 'tasksToExclude getter returns Set not SetProperty'() {
        given:
        buildFile << """
            plugins {
                id 'com.netflix.nebula.dependency-lock'
            }

            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.tasksToExclude
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

    def 'shouldFailTheBuild getter returns Boolean not Property (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.shouldFailTheBuild
                    println "isBoolean: " + (value instanceof Boolean)
                }
            }
        """
        when:
        def result = runTasks('checkType')
        then:
        result.output.contains('isBoolean: true')
    }

    def 'shouldFailTheBuild false assignment is respected (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            dependencyResolutionVerifierExtension {
                shouldFailTheBuild = false
            }
            task checkValue {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    println "value: " + ext.shouldFailTheBuild
                }
            }
        """
        when:
        def result = runTasks('checkValue')
        then:
        result.output.contains('value: false')
    }

    def 'enableLockFileValidation getter returns Boolean not Property (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.enableLockFileValidation
                    println "isBoolean: " + (value instanceof Boolean)
                }
            }
        """
        when:
        def result = runTasks('checkType')
        then:
        result.output.contains('isBoolean: true')
    }

    def 'missingVersionsMessageAddition getter returns String not Property (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.missingVersionsMessageAddition
                    println "isString: " + (value instanceof String)
                }
            }
        """
        when:
        def result = runTasks('checkType')
        then:
        result.output.contains('isString: true')
    }

    def 'missingVersionsMessageAddition assignment is reflected in getter (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            dependencyResolutionVerifierExtension {
                missingVersionsMessageAddition = 'See go/deps for help.'
            }
            task checkValue {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    println "msg: " + ext.missingVersionsMessageAddition
                }
            }
        """
        when:
        def result = runTasks('checkValue')
        then:
        result.output.contains('msg: See go/deps for help.')
    }

    def 'resolvedVersionDoesNotEqualLockedVersionMessageAddition getter returns String not Property (backward compat)'() {
        given:
        buildFile << """
            plugins { id 'com.netflix.nebula.dependency-lock' }
            task checkType {
                def ext = dependencyResolutionVerifierExtension
                doLast {
                    def value = ext.resolvedVersionDoesNotEqualLockedVersionMessageAddition
                    println "isString: " + (value instanceof String)
                }
            }
        """
        when:
        def result = runTasks('checkType')
        then:
        result.output.contains('isString: true')
    }

}
