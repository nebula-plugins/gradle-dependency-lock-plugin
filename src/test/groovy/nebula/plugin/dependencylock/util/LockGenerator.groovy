/*
 * Copyright 2016-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.util

import nebula.plugin.dependencylock.utils.GradleVersionUtils

class LockGenerator {
    static final Collection<String> DEFAULT_CONFIG_NAMES = GradleVersionUtils.currentGradleVersionIsLessThan("6.0")
            ? ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath', 'testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath']
            : ['compileClasspath', 'default', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath']
    static final Collection<String> DEFAULT_CONFIG_NAMES_POPULATED_BY_IMPLEMENTATION_SCOPE =
            ['compileClasspath', 'default', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath']

    /**
     * Helper to copy the exact same lock block multiple times into different configurations
     * @param deps the String of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param configs configurations to duplicate into, defaults to the 4 standard java DEFAULT_CONFIG_NAMES
     * @return the String to put into the file
     */
    static String duplicateIntoConfigs(String deps, Collection<String> configs = DEFAULT_CONFIG_NAMES) {
        def indentedDeps = deps.readLines().collect { "|        $it" }.join('\n')

        def pieces = configs.collect { config ->
            """\
            |    "${config}": {
            ${indentedDeps}
            |    }"""
        }

        def builder = new StringBuilder()
        builder.append('|{\n')
        builder.append(pieces.join(',\n'))
        builder.append('\n|}')

        return builder.toString().stripMargin()
    }

    /**
     * Helper to copy the exact same lock block multiple times into different configurations
     * and used when dependencies are defined on the implementation configuration only, so that it aligns with
     * the dependency configurations from https://docs.gradle.org/current/userguide/java_plugin.html
     * @param deps the String of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param configs configurations to duplicate into, defaults to the 4 standard java DEFAULT_CONFIG_NAMES
     * @return the String to put into the file
     */
    static String duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(String deps, Collection<String> configs = DEFAULT_CONFIG_NAMES_POPULATED_BY_IMPLEMENTATION_SCOPE) {
        def indentedDeps = deps.readLines().collect { "|        $it" }.join('\n')

        def pieces = configs.collect { config ->
            """\
            |    "${config}": {
            ${indentedDeps}
            |    }"""
        }

        def builder = new StringBuilder()
        builder.append('|{\n')
        builder.append(pieces.join(',\n'))
        builder.append('\n|}')

        return builder.toString().stripMargin()
    }

    /**
     * Helper to copy the same lock block multiple times into different configurations, that takes two sets of dependencies
     * and two sets of configurations. The dependency blocks will be sorted by configuration name.
     * @param firstDeps the String of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param firstConfigs configurations to duplicate into
     * @param secondDeps the String of the second set of dependencies, indentation should be what you want if that was the only thing going in the file
     * @param secondConfigs configurations to duplicate the second set of dependencies into
     * @return the String to put into the file
     */
    static String duplicateIntoConfigs(String firstDeps, Collection<String> firstConfigs, String secondDeps, Collection<String> secondConfigs) {
        Map<String, String> configNameToDependencyContents = new HashMap<String, String>()

        firstConfigs.each { configName ->
            configNameToDependencyContents.put(configName, firstDeps)
        }
        secondConfigs.each { configName ->
            configNameToDependencyContents.put(configName, secondDeps)
        }
        def sortedConfigNameToDependencyContents = configNameToDependencyContents.sort()

        List<String> configBlocks = new ArrayList<String>()
        sortedConfigNameToDependencyContents.each { configName, deps ->
            def indentedDeps = deps.readLines().collect { "|        $it" }.join('\n')
            def configAndDep = """\
            |    "${configName}": {
            ${indentedDeps}
            |    }"""
            configBlocks.add(configAndDep.toString())
        }

        StringBuilder builder = new StringBuilder()
        builder.append('|{\n')
        builder.append(configBlocks.join(',\n'))
        builder.append('\n|}')

        return builder.toString().stripMargin()
    }
}
