package nebula.plugin.dependencylock.wayback

import groovy.transform.TupleConstructor
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.util.GUtil

@TupleConstructor
class WaybackProviderFactory {
    Project project
    ClassLoader classLoader

    private String findProviderClassName(String providerId) {
        URL propertiesFileUrl = classLoader.getResource(String.format("META-INF/dependency-wayback-provider/%s.properties", providerId))
        return propertiesFileUrl ? GUtil.loadProperties(propertiesFileUrl).getProperty('implementation-class') : null
    }

    WaybackProvider build(String providerId) {
        def providerClassName = findProviderClassName(providerId)
        if (!providerClassName)
            throw new GradleException(String.format("No implementation class or includes specified for provider '%s' in %s.", providerId, providerClassName))

        try {
            return classLoader.loadClass(providerClassName).newInstance(project) as WaybackProvider
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new GradleException(String.format("Could not find or load implementation class '%s' for rule '%s'.",
                    providerClassName, providerId), e)
        }
    }
}
