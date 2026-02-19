package nebula.plugin

/**
 * Shared output assertions for dependency resolution / verifier tests.
 * Accepts multiple output formats (verifier vs Gradle, config cache) so tests stay stable.
 */
abstract class VerifierOutputAssertionsBase {

    protected static final String COULD_NOT_RESOLVE_ALL_FILES = 'Could not resolve all files for configuration'
    protected static final String COULD_NOT_FIND = 'Could not find '
    protected static final String FAILED_RESOLVE_PREFIX = "Failed to resolve '"
    protected static final String FAILED_RESOLVE_FOLLOWING = 'Failed to resolve the following dependencies:'
    protected static final String FAILED_SUFFIX = ' FAILED'
    protected static final List<String> RESOLUTION_FAILURE_MARKERS = [
            COULD_NOT_RESOLVE_ALL_FILES,
            COULD_NOT_FIND,
            ('verifyDependencyResolution' + FAILED_SUFFIX),
            FAILED_RESOLVE_FOLLOWING
    ]
    protected static final List<String> CONFIG_CACHE_STORING_ERROR_MESSAGES = [
            "Configuration cache state could not be cached",
            "problem was found storing the configuration cache"
    ]

    static void assertResolutionFailureMessage(String resultsOutput) {
        assert RESOLUTION_FAILURE_MARKERS.any { resultsOutput.contains(it) },
                'Expected to see a message about failure to resolve dependencies'
    }

    static void assertNoResolutionFailureMessage(String resultsOutput) {
        assert RESOLUTION_FAILURE_MARKERS.every { !resultsOutput.contains(it) },
                'Expected to _not_ see a message about failure to resolve dependencies'
    }

    static void assertResolutionFailureForDependency(String resultsOutput, String dependency) {
        assertResolutionFailureForDependency(resultsOutput, dependency, 1)
    }

    static void assertResolutionFailureForDependency(String resultsOutput, String dependency, int _) {
        assert hasResolutionFailureForDependency(resultsOutput, dependency),
                "Expected to see a message about failure to resolve a specific dependency"
    }

    static void assertResolutionFailureForDependencyForProject(String resultsOutput, String dependency, String projectName) {
        assertResolutionFailureForDependency(resultsOutput, dependency)
        assert hasProjectContextInOutput(resultsOutput, projectName),
                "Expected to see a message about failure to resolve a specific dependency for a specific project"
    }

    static void assertExecutionFailedForTask(String resultsOutput) {
        List<String> taskFailureMarkers = [
                'Execution failed for task',
                'FAILURE: Build failed with an exception',
                'BUILD FAILED',
                FAILED_SUFFIX
        ]
        boolean fromMarkers = taskFailureMarkers.any { resultsOutput.contains(it) }
        boolean fromBuildOutcome = resultsOutput.contains('Build completed with') && resultsOutput.contains('failure')
        assert fromMarkers || fromBuildOutcome, 'Expected to see a message about a failure'
    }

    static void assertOutputMentionsProjects(String resultsOutput, List<String> projectNames) {
        projectNames.each { name ->
            assert hasProjectContextInOutput(resultsOutput, name),
                "Expected output to mention project '$name'"
        }
    }

    static void assertConfigurationCacheStateCouldNotBeStored(String resultsOutput) {
        assert CONFIG_CACHE_STORING_ERROR_MESSAGES.any { resultsOutput.contains(it) },
                "Expected configuration cache state could not be stored. Looked for but did not find: '${CONFIG_CACHE_STORING_ERROR_MESSAGES.join("', '")}'"
    }

    static void assertNoConfigurationCacheStoringIssues(String resultsOutput) {
        assert CONFIG_CACHE_STORING_ERROR_MESSAGES.every { !resultsOutput.contains(it) },
                "Expected _not_ to see configuration cache storing issues, but found: '${CONFIG_CACHE_STORING_ERROR_MESSAGES.join("', '")}'"

        def entryStoredMessage = "Configuration cache entry stored."
        assert resultsOutput.contains(entryStoredMessage),
                "Expected to see configuration cache entry stored, but found: '${entryStoredMessage}'"
    }

    static void assertConfigurationCachingIsNotMentioned(String resultsOutput) {
        assert CONFIG_CACHE_STORING_ERROR_MESSAGES.every { !resultsOutput.contains(it) },
                "Expected configuration caching to _not_ be mentioned, but found: '${CONFIG_CACHE_STORING_ERROR_MESSAGES.join("', '")}'"
    }

    protected static boolean hasResolutionFailureForDependency(String resultsOutput, String dependency) {
        List<String> patterns = [
                COULD_NOT_FIND + dependency,
                FAILED_RESOLVE_PREFIX + dependency + "' for project",
                FAILED_RESOLVE_PREFIX + dependency + "'",
                dependency + FAILED_SUFFIX
        ]
        return patterns.any { resultsOutput.contains(it) } ||
                (resultsOutput.contains('missing a version') && resultsOutput.contains(dependency))
    }

    protected static boolean hasProjectContextInOutput(String resultsOutput, String projectName) {
        List<String> projectPatterns = [
                "for project '" + projectName + "'",
                ":" + projectName + ":",
                "Project ':" + projectName + "'",
                "'" + projectName + "'"
        ]
        List<String> requiredByProjectMarkers = [
                'Required by:',
                "project '" + projectName + "'"
        ]
        return (requiredByProjectMarkers.every { resultsOutput.contains(it) }) ||
                projectPatterns.any { resultsOutput.contains(it) }
    }
}
