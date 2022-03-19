package nebula.plugin.dependencylock.validation

import nebula.plugin.dependencylock.exceptions.DependencyLockException
import spock.lang.Specification

class UpdateDependenciesValidatorSpec extends Specification {

    def 'should not fail if valid coordinates'() {
        when:
        Set<String> modules = ["netflix:my-module", "netflix:my-module-2"]
        UpdateDependenciesValidator.validate(modules,true)

        then:
        notThrown(DependencyLockException)
    }

    def 'should fail if invalid coordinates'() {
        when:
        Set<String> modules = ["netflix", "netflix:my-module:1.+", "netflix:my-module-2:latest.release", "netflix:my-module;2:latest.release"]
        UpdateDependenciesValidator.validate(modules,true)

        then:
        def exception = thrown(DependencyLockException)
        exception.message.contains("netflix does not contain groupId:module. Only has one element")
        exception.message.contains("netflix:my-module:1.+ contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module-2:latest.release contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module;2:latest.release contains more elements than groupId:module. Version and classifiers are not supported")
        exception.message.contains("netflix:my-module;2:latest.release contains ; which is invalid")
    }
}
