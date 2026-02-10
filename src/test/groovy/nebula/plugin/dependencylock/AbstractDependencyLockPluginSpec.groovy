package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
class AbstractDependencyLockPluginSpec extends BaseIntegrationTestKitSpec {
    def expectedLocks = [
            'annotationProcessor',
            'compileClasspath',
            'runtimeClasspath',
            'testAnnotationProcessor',
            'testCompileClasspath',
            'testRuntimeClasspath'
    ] as String[]
    def mavenrepo
    def projectName

    def setup() {
        new File(projectDir, 'gradle.properties').text = '''
            org.gradle.configuration-cache=true
            systemProp.nebula.features.coreLockingSupport=true
            org.gradle.warning.mode=fail
            '''.stripIndent()

        projectName = getProjectDir().getName().replaceAll(/_\d+/, '')
        settingsFile << """\
            rootProject.name = '${projectName}'
        """.stripIndent()

        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.1.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:1.1.0')
                .addModule('test.nebula:d:1.0.0')
                .addModule('test.nebula:d:1.1.0')
                .addModule(new ModuleBuilder('test.nebula:c:1.0.0').addDependency('test.nebula:d:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:c:1.1.0').addDependency('test.nebula:d:1.1.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()
    }

    def setupScalaProject(String conf) {
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """
            plugins {
                id 'scala'
                id 'com.netflix.nebula.dependency-lock'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                $conf 'org.scala-lang:scala-library:2.12.7'
            
                test${conf.capitalize()} 'junit:junit:4.12'
                test${conf.capitalize()} 'org.scalatest:scalatest_2.12:3.0.5'
            
                testRuntimeOnly 'org.scala-lang.modules:scala-xml_2.12:1.1.1'
            }
            """.stripIndent()

        def scalaFile = createFile("src/main/scala/Library.scala")
        scalaFile << """
            class Library {
              def someLibraryMethod(): Boolean = true
            }
            """.stripIndent()

        def scalaTest = createFile("src/test/scala/LibrarySuite.scala")
        scalaTest << """
            import org.scalatest.FunSuite
            import org.junit.runner.RunWith
            import org.scalatest.junit.JUnitRunner
            
            @RunWith(classOf[JUnitRunner])
            class LibrarySuite extends FunSuite {
              test("someLibraryMethod is always true") {
                def library = new Library()
                assert(library.someLibraryMethod)
              }
            }
            """.stripIndent()
    }

    Map<String, String> coreLockContent(File lockFile) {
        lockFile.readLines().findAll {!it.startsWith("#")}.collectEntries {
            it.split('=').toList()
        }
    }

    List<String> lockedConfigurations(Map<String,String> lockFileContent) {
        lockFileContent.values().collectMany { it.toString().split(',').toList() }.unique()
    }

    def getKotlinExpectedLocks() {
        expectedLocks
    }
}
