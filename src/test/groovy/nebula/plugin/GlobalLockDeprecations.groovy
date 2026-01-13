package nebula.plugin

import org.junit.contrib.java.lang.system.ProvideSystemProperty

/**
 * Trait for tests that need to suppress global lock deprecation warnings.
 * 
 * Global lock tasks intentionally use the OLD API (conventionMapping) which accesses
 * project state at execution time. This triggers Gradle's "Invocation of Task.project 
 * at execution time has been deprecated" warning.
 * 
 * Global lock is not configuration cache compatible and will be addressed in future work.
 * 
 * Usage in test specs:
 * <pre>
 * class MyGlobalLockSpec extends BaseIntegrationTestKitSpec implements GlobalLockDeprecations {
 *     @Rule
 *     public final ProvideSystemProperty ignoreGlobalLockDeprecations = globalLockDeprecationRule()
 *     
 *     // ... tests
 * }
 * </pre>
 */
trait GlobalLockDeprecations {
    
    /**
     * Creates a JUnit rule that suppresses "Invocation of Task.project at execution time"
     * deprecation warnings from global lock tasks.
     */
    ProvideSystemProperty globalLockDeprecationRule() {
        return new ProvideSystemProperty("ignoreDeprecations", "true")
    }
}
