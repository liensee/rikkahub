package me.rerere.rikkahub.data.files

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.mcp.McpTool

sealed class UnifiedSkill {
    abstract val name: String
    abstract val description: String?
    abstract val compatibility: String?
    abstract val sourceIcon: String
    abstract val sourceLabel: String

    /**
     * Type-qualified key for use in LazyColumn / list diffing.
     * Prevents collisions between [Local] skills and [MCP] tools
     * that happen to share the same [name].
     */
    val key: String
        get() = "${typePrefix}_${name}"

    /** Short discriminator: "local" or "mcp:<serverId>" */
    protected abstract val typePrefix: String

    data class Local(val skill: SkillMetadata) : UnifiedSkill() {
        override val name get() = skill.name
        override val description get() = skill.description
        override val compatibility get() = skill.compatibility
        override val sourceIcon = "📦"
        override val sourceLabel = "本地"
        override val typePrefix = "local"
    }

    data class MCP(
        val serverId: Uuid,
        val tool: McpTool,
        val serverName: String,
    ) : UnifiedSkill() {
        override val name get() = tool.name
        override val description get() = tool.description
        override val compatibility = null
        override val sourceIcon = "🔌"
        override val sourceLabel = "MCP: $serverName"
        override val typePrefix get() = "mcp_${serverId}"
    }
}
