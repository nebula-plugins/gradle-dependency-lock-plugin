package nebula.plugin.dependencylock.model

import groovy.transform.Canonical

@Canonical
class GradleDependency {
    String group
    String name
    String version

    static GradleDependency fromConstant(Object expr) {
        def matcher = expr =~ /(?<group>[^:]+)?(:(?<name>[^:]+))(:(?<version>[^@:]+))/
        if (matcher.matches()) {
            return new GradleDependency(
                    matcher.group('group'),
                    matcher.group('name'),
                    matcher.group('version')
            )
        }
        return null
    }
}