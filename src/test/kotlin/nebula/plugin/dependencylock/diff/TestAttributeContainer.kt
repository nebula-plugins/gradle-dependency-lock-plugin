package nebula.plugin.dependencylock.diff

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.provider.Provider

class TestAttributeContainer(val attributes: MutableMap<Attribute<*>, Any>) : AttributeContainer {
    override fun keySet(): Set<Attribute<*>> {
        return attributes.keys
    }

    override fun <T : Any> attribute(
        key: Attribute<T>,
        value: T
    ): AttributeContainer {
        attributes[key] = value
        return this
    }

    override fun <T : Any> attributeProvider(
        key: Attribute<T>,
        provider: Provider<out T>
    ): AttributeContainer {
        TODO("Not yet implemented")
    }

    override fun addAllLater(other: AttributeContainer): AttributeContainer {
        TODO("Not yet implemented")
    }

    override fun <T : Any> getAttribute(key: Attribute<T>): T? {
        return attributes.get(key) as T?
    }

    override fun isEmpty(): Boolean {
        return attributes.isEmpty()
    }

    override fun contains(key: Attribute<*>): Boolean {
        return attributes.containsKey(key)
    }

    override fun getAttributes(): AttributeContainer {
        return this
    }
}