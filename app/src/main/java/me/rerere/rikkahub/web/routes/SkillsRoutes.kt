package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.SkillCreateRequest
import me.rerere.rikkahub.web.dto.SkillDetailDto
import me.rerere.rikkahub.web.dto.SkillFileDto
import me.rerere.rikkahub.web.dto.SkillFileListDto
import me.rerere.rikkahub.web.dto.SkillMetadataDto

fun Route.skillsRoutes(skillManager: SkillManager) {
    route("/skills") {
        // GET /api/skills — List all skill metadata
        get {
            val skills = skillManager.listSkills()
                .filter { !it.skillDir.name.startsWith(".") }
                .map { it.toDto() }
            call.respond(HttpStatusCode.OK, skills)
        }

        // GET /api/skills/{name} — Get single skill detail
        get("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing skill name")

            val metadata = skillManager.listSkills()
                .find { it.name == name }
                ?: throw NotFoundException("Skill not found: $name")

            val content = skillManager.readSkillContent(name)
                ?: throw NotFoundException("Skill content not found: $name")

            call.respond(
                HttpStatusCode.OK,
                SkillDetailDto(
                    metadata = metadata.toDto(),
                    content = content,
                )
            )
        }

        // GET /api/skills/{name}/files — List files in skill directory
        get("/{name}/files") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing skill name")

            val skillDir = skillManager.getSkillDir(name)
                ?: throw NotFoundException("Skill not found: $name")

            val files = skillDir.listFiles()
                ?.filter { it.isFile && it.name != "SKILL.md" }
                ?.map { it.name }
                ?: emptyList()

            call.respond(HttpStatusCode.OK, SkillFileListDto(files = files))
        }

        // GET /api/skills/{name}/files/{path...} — Get skill reference file content
        get("/{name}/files/{path...}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing skill name")
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing file path")

            // Prevent directory traversal attacks
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                throw BadRequestException("Invalid file path")
            }

            val file = skillManager.resolveSkillFile(name, relativePath)
                ?: throw NotFoundException("Skill file not found: $relativePath")

            if (!file.exists() || !file.isFile) {
                throw NotFoundException("Skill file not found: $relativePath")
            }

            // Ensure the resolved file is within the skill directory
            val skillDir = skillManager.getSkillDir(name)
                ?: throw NotFoundException("Skill not found: $name")
            if (!file.canonicalPath.startsWith(skillDir.canonicalPath)) {
                throw BadRequestException("Invalid file path")
            }

            val content = file.readText()
            call.respond(
                HttpStatusCode.OK,
                SkillFileDto(
                    path = relativePath,
                    content = content,
                )
            )
        }

        // POST /api/skills/{name} — Create or update skill
        post("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing skill name")

            val request = call.receive<SkillCreateRequest>()
            if (request.content.isEmpty()) {
                throw BadRequestException("Skill content cannot be empty")
            }

            val result = skillManager.saveSkill(name, request.content)
            if (result == null) {
                throw BadRequestException("Failed to save skill: $name")
            }

            call.respond(HttpStatusCode.OK, result.toDto())
        }

        // DELETE /api/skills/{name} — Delete skill
        delete("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing skill name")

            val deleted = skillManager.deleteSkill(name)
            if (!deleted) {
                throw NotFoundException("Skill not found: $name")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }
    }
}

private fun SkillMetadata.toDto() = SkillMetadataDto(
    name = name,
    description = description,
    compatibility = compatibility,
    allowedTools = allowedTools,
)
