/*
 * Copyright 2014-2019 Netflix, Inc.
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
package nebula.plugin.dependencylock

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal

/**
 * Extension for configuring commit behavior for dependency lock files.
 * Uses Gradle's Property API for lazy configuration and configuration cache compatibility.
 */
abstract class DependencyLockCommitExtension {
    /**
     * Commit message to use when committing lock files.
     * Default: 'Committing dependency lock files'
     */
    abstract Property<String> getMessage()
    
    /**
     * Whether to create a git tag when committing.
     * Default: false
     */
    abstract Property<Boolean> getShouldCreateTag()
    
    /**
     * Tag name to use when creating a tag.
     * Default: 'LockCommit-{timestamp}'
     * Note: The default is evaluated at construction time for configuration cache compatibility.
     */
    abstract Property<String> getTag()
    
    /**
     * Number of times to retry remote operations.
     * Default: 3
     */
    abstract Property<Integer> getRemoteRetries()

    /**
     * Absolute child save-task paths to order before the root commit in isolated-projects mode.
     * Ordering does not request these tasks; consumers still select saves explicitly.
     */
    abstract ListProperty<String> getSaveTaskPaths()
    
    private final Closure<String> defaultTagClosure = { "LockCommit-${new Date().format('yyyyMMddHHmmss')}".toString() }

    /**
     * Legacy Closure-based tag generator.
     * Kept for backward compatibility but marked as @Internal since Closures are not configuration cache compatible.
     * Users should migrate to using the tag Property directly.
     * @deprecated Use {@link #getTag()} instead
     */
    @Internal
    @Deprecated
    Closure<String> tagClosure = defaultTagClosure

    /** Internal serializable customization marker; the callback is never invoked by commit tasks. */
    @Internal
    abstract Property<Boolean> getTagClosureCustomized()

    void setTagClosure(Closure<String> callback) {
        this.tagClosure = callback
        tagClosureCustomized.set(!defaultTagClosure.is(callback))
    }
    
    /**
     * Constructor sets default conventions for all properties.
     */
    DependencyLockCommitExtension() {
        tagClosureCustomized.convention(false)
        message.convention('Committing dependency lock files')
        shouldCreateTag.convention(false)
        // Set default tag with timestamp evaluated at construction time
        tag.convention("LockCommit-${new Date().format('yyyyMMddHHmmss')}")
        remoteRetries.convention(3)
        saveTaskPaths.convention([])
    }
}
