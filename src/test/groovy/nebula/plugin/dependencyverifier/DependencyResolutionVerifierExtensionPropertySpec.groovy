package nebula.plugin.dependencyverifier

import nebula.plugin.BaseIntegrationTestKitSpec

/**
 * Validates that DependencyResolutionVerifierExtension SetProperty fields expose backward-compatible
 * Set&lt;String&gt; getter/setter bridges so build scripts using Groovy = assignment continue to work.
 */
class DependencyResolutionVerifierExtensionPropertySpec extends BaseIntegrationTestKitSpec {

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

}
