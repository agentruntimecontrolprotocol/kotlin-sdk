package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.messages.AgentDescriptor
import dev.arcp.messages.AgentRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime registry for versioned agents advertised in session capabilities.
 *
 * Each agent's version map is held as an immutable [Map] updated through
 * [ConcurrentHashMap.compute], so [resolve] and [descriptors] never
 * observe a half-written entry while [register] is in progress.
 */
public class AgentRegistry {
    private val versions: ConcurrentHashMap<String, Map<String, Boolean>> = ConcurrentHashMap()

    /** Registers an agent version and optionally marks it as the default. */
    public fun register(
        name: String,
        version: String,
        default: Boolean = false,
    ) {
        val ref = AgentRef(name, version)
        val versionKey = ref.version ?: version
        versions.compute(ref.name) { _, existing ->
            val cleared =
                if (default) {
                    existing?.mapValues { false } ?: emptyMap()
                } else {
                    existing ?: emptyMap()
                }
            // Preserve insertion order via LinkedHashMap so descriptors() is stable.
            val next = LinkedHashMap(cleared)
            next[versionKey] = default
            next
        }
    }

    /** Resolves a bare or pinned reference to an available concrete version. */
    public fun resolve(ref: AgentRef): AgentRef {
        val registered =
            versions[ref.name] ?: throw ARCPException.NotFound("agent ${ref.name} is not available")
        ref.version?.let { version ->
            if (registered.containsKey(version)) return AgentRef(ref.name, version)
            throw ARCPException.AgentVersionNotAvailable(ref.name, version)
        }
        val default = registered.entries.firstOrNull { it.value }?.key
        return AgentRef(ref.name, default ?: registered.keys.first())
    }

    /** Returns descriptors advertised in session capabilities. */
    public fun descriptors(): List<AgentDescriptor> = versions.entries
        .sortedBy { it.key }
        .map { (name, registered) ->
            AgentDescriptor(
                name = name,
                versions = registered.keys.toList(),
                default = registered.entries.firstOrNull { it.value }?.key,
            )
        }
}
