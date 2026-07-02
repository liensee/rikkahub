package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.io.File

/**
 * Workflows stored as JSON files in context.filesDir/workflows/
 */
fun Route.workflowsRoutes(context: Context) {
    route("/workflows") {
        // GET /api/workflows — List all workflows
        get {
            val workflowsDir = getWorkflowsDir(context)
            if (!workflowsDir.exists()) {
                call.respond(HttpStatusCode.OK, WorkflowListResponse(workflows = emptyList()))
                return@get
            }

            val workflows = workflowsDir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.map { file ->
                    WorkflowSummary(
                        name = file.nameWithoutExtension,
                        updatedAt = file.lastModified(),
                    )
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()

            call.respond(HttpStatusCode.OK, WorkflowListResponse(workflows = workflows))
        }

        // GET /api/workflows/{name} — Get single workflow as raw JSON
        get("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing workflow name")

            val workflowFile = getWorkflowFile(context, name)
            if (!workflowFile.exists()) {
                throw NotFoundException("Workflow not found: $name")
            }

            call.response.header("Content-Type", ContentType.Application.Json.toString())
            call.respondText(workflowFile.readText())
        }

        // POST /api/workflows/{name} — Create or update workflow
        post("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing workflow name")

            // Accept raw JSON body
            val body = call.receive<String>()
            if (body.isBlank()) {
                throw BadRequestException("Workflow content cannot be empty")
            }

            val workflowsDir = getWorkflowsDir(context)
            workflowsDir.mkdirs()

            val sanitizedName = sanitizeWorkflowName(name)
            val workflowFile = File(workflowsDir, "$sanitizedName.json")
            workflowFile.writeText(body)

            call.respond(HttpStatusCode.OK, mapOf("status" to "saved", "name" to sanitizedName))
        }

        // DELETE /api/workflows/{name} — Delete workflow
        delete("/{name}") {
            val name = call.pathParameters["name"]
                ?: throw BadRequestException("Missing workflow name")

            val workflowFile = getWorkflowFile(context, name)
            if (!workflowFile.exists()) {
                throw NotFoundException("Workflow not found: $name")
            }

            workflowFile.delete()
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }
    }
}

@Serializable
private data class WorkflowSummary(
    val name: String,
    val updatedAt: Long,
)

@Serializable
private data class WorkflowListResponse(
    val workflows: List<WorkflowSummary>,
)

private fun getWorkflowsDir(context: Context): File {
    return File(context.filesDir, "workflows")
}

private fun getWorkflowFile(context: Context, name: String): File {
    val sanitized = sanitizeWorkflowName(name)
    return File(getWorkflowsDir(context), "$sanitized.json")
}

private fun sanitizeWorkflowName(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
}
