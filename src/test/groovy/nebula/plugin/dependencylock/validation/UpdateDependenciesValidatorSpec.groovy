package nebula.plugin.dependencylock.validation

import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import nebula.test.ProjectSpec

class UpdateDependenciesValidatorSpec extends ProjectSpec {
    private static final HashMap<Object, Object> emptyMap = new HashMap<Object, Object>()
    private static final String pluginName = 'com.netflix.nebula.dependency-lock'
    DependencyLockExtension extension

    def setup() {
        project.apply plugin: pluginName
        extension = project.extensions.getByType(DependencyLockExtension)
    }

    def 'should not fail if valid coordinates'() {
        when:
        Set<String> modules = ["netflix:my-module", "netflix:my-module-2"]
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false, 
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should fail if invalid coordinates'() {
        when:
        Set<String> modules = ["netflix", "netflix:my-module:1.+", "netflix:my-module-2:latest.release", "netflix:my-module;2:latest.release"]
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        def exception = thrown(DependencyLockException)
        exception.message.contains("netflix does not contain groupId:module. Only has one element")
        exception.message.contains("netflix:my-module:1.+ contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module-2:latest.release contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module;2:latest.release contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module;2:latest.release contains ; which is invalid")
    }

    def 'should not fail if invalid coordinates but disabled validation'() {
        when:
        project.ext.set('dependencyLock.updateDependenciesFailOnInvalidCoordinates', false)
        Set<String> modules = ["netflix", "netflix:my-module:1.+", "netflix:my-module-2:latest.release", "netflix:my-module;2:latest.release"]
        // Use findProperty to check both gradle properties and ext (check for null, not truthiness)
        def validateCoordinatesProp = project.findProperty('dependencyLock.updateDependenciesFailOnInvalidCoordinates')
        def validateCoordinates = validateCoordinatesProp != null ? validateCoordinatesProp as boolean : extension.updateDependenciesFailOnInvalidCoordinates.get()
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false,
            validateCoordinates,
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should not fail when calling one locking generation command - generateLock'() {
        when:
        Set<String> modules = []
        UpdateDependenciesValidator.validate(modules, emptyMap, false, true,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should not fail when calling one locking generation command - updateLock'() {
        when:
        Set<String> modules = ["netflix:my-module", "netflix:my-module-2"]
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should fail when calling generateLock and updateLock together'() {
        when:
        Set<String> modules = ["netflix:my-module", "netflix:my-module-2"]
        UpdateDependenciesValidator.validate(modules, emptyMap, true, true,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        def exception = thrown(DependencyLockException)
        exception.message.contains("Using `generateLock` and `updateLock` in the same build will result in re-resolving all locked dependencies.")
        exception.message.contains("Please invoke only one of these tasks at a time.")
    }

    def 'should not fail when calling generateLock and updateLock together but disabled validation'() {
        when:
        project.ext.set('dependencyLock.updateDependenciesFailOnSimultaneousTaskUsage', false)
        Set<String> modules = ["netflix:my-module", "netflix:my-module-2"]
        // Use findProperty to check both gradle properties and ext (check for null, not truthiness)
        def validateSimultaneousProp = project.findProperty('dependencyLock.updateDependenciesFailOnSimultaneousTaskUsage')
        def validateSimultaneous = validateSimultaneousProp != null ? validateSimultaneousProp as boolean : extension.updateDependenciesFailOnSimultaneousTaskUsage.get()
        UpdateDependenciesValidator.validate(modules, emptyMap, true, true,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            validateSimultaneous,
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should not fail when only overrides are requested'() {
        when:
        Set<String> modules = []
        Map<String, String> overrides = new HashMap<>()
        overrides.put("netflix:my-module", "1.0.0")
        overrides.put("netflix:my-module-2", "1.0.0")
        UpdateDependenciesValidator.validate(modules, overrides, true, false,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        notThrown(DependencyLockException)
    }

    def 'should fail when no modules to update and no overrides are passed in'() {
        when:
        Set<String> modules = []
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get())

        then:
        def exception = thrown(DependencyLockException)
        exception.message.contains("Please specify dependencies to update")
    }

    def 'should not fail when no modules to update and no overrides are passed in but disabled validation'() {
        when:
        project.ext.set('dependencyLock.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate', false)
        Set<String> modules = []
        // Use findProperty to check both gradle properties and ext (check for null, not truthiness)
        def validateSpecifiedProp = project.findProperty('dependencyLock.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate')
        def validateSpecified = validateSpecifiedProp != null ? validateSpecifiedProp as boolean : extension.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.get()
        UpdateDependenciesValidator.validate(modules, emptyMap, true, false,
            extension.updateDependenciesFailOnInvalidCoordinates.get(),
            extension.updateDependenciesFailOnSimultaneousTaskUsage.get(),
            validateSpecified)

        then:
        notThrown(DependencyLockException)
    }
}