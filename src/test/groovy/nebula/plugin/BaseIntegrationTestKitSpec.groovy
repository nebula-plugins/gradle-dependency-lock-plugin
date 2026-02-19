package nebula.plugin

import nebula.test.IntegrationTestKitSpec

abstract class BaseIntegrationTestKitSpec extends IntegrationTestKitSpec {
    def setup() {
        // Enable configuration cache to make sure we don't break builds
        new File(projectDir, 'gradle.properties') << '''
            org.gradle.configuration-cache=true
            org.gradle.warning.mode=fail
            '''.stripIndent()
    }

    void disableConfigurationCache() {
        def propertiesFile = new File(projectDir, 'gradle.properties')
        if(propertiesFile.exists()) {
            propertiesFile.delete()
        }
        propertiesFile.createNewFile()
        propertiesFile << '''org.gradle.configuration-cache=false'''.stripIndent()
    }
}
