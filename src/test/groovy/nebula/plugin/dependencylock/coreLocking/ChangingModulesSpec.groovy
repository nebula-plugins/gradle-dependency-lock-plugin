package nebula.plugin.dependencylock.coreLocking

import nebula.plugin.dependencylock.AbstractDependencyLockPluginSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder

class ChangingModulesSpec extends AbstractDependencyLockPluginSpec {
    def 'when resolving and generating locks then dependencies should have remove cache for changing modules'() {
        // verifying functionality usually set by `cacheChangingModulesFor`

        //mavenLocal will be a local file repository, and thus will bypass the dependency cache

        given:
        setupChangingDependency()
        setupBuildFile()

        buildFile << """\
            configurations.all {
                resolutionStrategy.cacheChangingModulesFor 10, 'minutes' // default is 24 hours
                resolutionStrategy.cacheDynamicVersionsFor 10, 'minutes' // default is 24 hours
            }
            task retrieve(type: Sync) {
              from configurations.compileClasspath
              into 'libs'
            }
            """.stripIndent()
        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath')

        then:
        result.output.contains('\\--- test.nebula:a:1.0.0')

        when:
        updateChangingDependency()

        def updatedLockedResults = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath') // should cache for 0 seconds

        then:
        updatedLockedResults.output.contains('\\--- test.nebula:a:1.1.0')
    }

    def 'changing modules with updated transitive dependencies cause resolution failure until dependencies are updated'() {
        given:
        setupChangingDependency()
        setupBuildFile()

        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath')

        then:
        result.output.contains('\\--- test.nebula:a:1.0.0')

        when:
        updateChangingDependency()

        def dependenciesResult = runTasksAndFail('dependencies', '--configuration', 'compileClasspath')

        then:
        dependenciesResult.output.contains("""
Execution failed for task ':dependencies'.
> Failed to resolve the following dependencies:
    1. Failed to resolve 'test.nebula:a:1.1.0' for project '$projectName\'
    2. Failed to resolve 'test.nebula:a:{strictly 1.0.0}' for project '$projectName\'
""")
    }

    private void setupBuildFile() {
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.changing:z:1.0.0-SNAPSHOT'
            }
            """.stripIndent()
    }

    void setupChangingDependency() {
        DependencyGraph graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.changing:z:1.0.0-SNAPSHOT').addDependency('test.nebula:a:1.0.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        validateChangingDependencyMetadata('1.0.0')
    }

    void updateChangingDependency() {
        DependencyGraph updatedGraph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.changing:z:1.0.0-SNAPSHOT').addDependency('test.nebula:a:1.1.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(updatedGraph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        validateChangingDependencyMetadata('1.1.0')
    }

    void validateChangingDependencyMetadata(String transitiveVersion) {
        File z100Dir = new File(projectDir, 'testrepogen/mavenrepo/test/changing/z/1.0.0-SNAPSHOT')
        assert z100Dir.exists()

        File z100Metadata = z100Dir.listFiles().findAll { it.name.endsWith('.pom') }.first()
        assert z100Metadata.exists()

        assert z100Metadata.text.contains('<groupId>test.nebula</groupId>')
        assert z100Metadata.text.contains('<artifactId>a</artifactId>')
        assert z100Metadata.text.contains("<version>$transitiveVersion</version>")
    }

    void configureChangingModule() {
        // from https://github.com/gradle/gradle/blob/v6.0.0-RC1/subprojects/dependency-management/src/integTest/groovy/org/gradle/integtests/resolve/MetadataArtifactResolveTestFixture.groovy
        buildFile << """
class ChangingRule implements ComponentMetadataRule {
    @Override
    void execute(ComponentMetadataContext context) {
        context.details.changing = true
    }
}

dependencies {
    components {
        all(ChangingRule)
    }
}

if (project.hasProperty('nocache')) {
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor 0, 'seconds'
    }
}
"""
    }
}
