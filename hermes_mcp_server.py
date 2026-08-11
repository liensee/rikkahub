#!/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/python3
"""
hermes_mcp_server.py — Stdio→StreamableHTTP bridge for Hermes MCP + Skills.

Wraps `hermes mcp serve` (stdio transport) as an HTTP server on :6789.
RikkaHub connects via StreamableHTTP transport to discover Hermes tools
AND Hermes Skills (injected as MCP tools).

Architecture:
  RikkaHub (StreamableHTTP) → :6789/mcp → stdio → hermes mcp serve
                                              ↘ skills/ filesystem
"""

import asyncio
import json
import logging
import os
import re
import sys
from pathlib import Path
from aiohttp import web

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [hermes-mcp] %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger("hermes-mcp-server")

HERMES_BIN = os.path.expanduser("~/.hermes/hermes-agent/venv/bin/hermes")
SKILLS_DIR = os.path.expanduser("~/.hermes/skills")
LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 6789

hermes_process: asyncio.subprocess.Process | None = None
process_lock = asyncio.Lock()
stdio_lock = asyncio.Lock()  # serialize all stdio read/write

# Cache discovered skills at startup
_cached_skills: list[dict] = []


# ─── Skill Discovery ────────────────────────────────────────────────

def _parse_skill_frontmatter(path: str) -> dict | None:
    """Parse YAML frontmatter from SKILL.md, return {name, description, category}."""
    try:
        with open(path) as f:
            content = f.read(4096)
    except Exception:
        return None

    m = re.match(r'^---\s*\n(.*?)\n---', content, re.DOTALL)
    if not m:
        return None

    fm = {}
    for line in m.group(1).split('\n'):
        kv = re.match(r'^(\w[\w-]*):\s*(.*)', line)
        if kv:
            fm[kv.group(1)] = kv.group(2).strip().strip('"').strip("'")

    # Derive category from path
    rel = os.path.relpath(path, SKILLS_DIR)
    parts = rel.split(os.sep)
    category = parts[0] if len(parts) > 2 else "general"

    return {
        "name": fm.get("name", Path(path).parent.name),
        "description": fm.get("description", "No description"),
        "category": category,
        "path": path,
    }


def discover_skills() -> list[dict]:
    """Scan SKILLS_DIR and return list of skill metadata dicts."""
    skills = []
    if not os.path.isdir(SKILLS_DIR):
        return skills

    for root, dirs, files in os.walk(SKILLS_DIR):
        # Skip hidden dirs
        dirs[:] = [d for d in dirs if not d.startswith('.')]
        if "SKILL.md" in files:
            skill_path = os.path.join(root, "SKILL.md")
            meta = _parse_skill_frontmatter(skill_path)
            if meta:
                skills.append(meta)

    skills.sort(key=lambda s: (s["category"], s["name"]))
    return skills


def _skill_to_tool(skill: dict) -> dict:
    """Convert a skill metadata dict to an MCP Tool schema."""
    safe_name = re.sub(r'[^a-z0-9_-]', '_', skill["name"].lower())
    return {
        "name": f"skill__{safe_name}",
        "description": (
            f"[Skill: {skill['category']}] {skill['description']}\n"
            f"Call this tool to read the full SKILL.md content and usage instructions."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    }


SKILL_TOOLS = []  # populated at import time


def _read_skill_content(skill_name: str) -> str:
    """Read the full SKILL.md content for a given skill name."""
    clean = skill_name.removeprefix("skill__")
    for skill in _cached_skills:
        safe = re.sub(r'[^a-z0-9_-]', '_', skill["name"].lower())
        if safe == clean:
            try:
                with open(skill["path"]) as f:
                    return f.read()
            except Exception as e:
                return f"Error reading skill: {e}"
    return f"Skill '{clean}' not found."


# ─── Hermes Subprocess Management ───────────────────────────────────

async def ensure_hermes_process():
    global hermes_process
    async with process_lock:
        if hermes_process is not None and hermes_process.returncode is None:
            return
        if hermes_process is not None:
            log.warning("Hermes process dead (exit=%s), restarting...",
                        hermes_process.returncode)
            try:
                hermes_process.kill()
            except Exception:
                pass

        log.info("Starting: %s mcp serve", HERMES_BIN)
        hermes_process = await asyncio.create_subprocess_exec(
            HERMES_BIN, "mcp", "serve",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        log.info("Hermes MCP process started (pid=%s)", hermes_process.pid)
        asyncio.create_task(_drain_stderr(hermes_process))


async def _drain_stderr(proc: asyncio.subprocess.Process):
    try:
        while True:
            line = await proc.stderr.readline()
            if not line:
                break
            text = line.decode("utf-8", errors="replace").rstrip()
            if text:
                log.info("[hermes] %s", text)
    except Exception as e:
        log.debug("stderr drain done: %s", e)


async def _forward_to_hermes(body: dict) -> dict:
    """Send JSON-RPC request to Hermes via stdio and return response."""
    global hermes_process
    payload = json.dumps(body).encode("utf-8") + b"\n"

    async with stdio_lock:
        try:
            hermes_process.stdin.write(payload)
            await hermes_process.stdin.drain()
        except (BrokenPipeError, ConnectionResetError) as e:
            log.error("Write failed: %s", e)
            hermes_process = None
            raise RuntimeError(f"Hermes connection lost: {e}")

        try:
            response_line = await asyncio.wait_for(
                hermes_process.stdout.readline(), timeout=120
            )
        except asyncio.TimeoutError:
            raise RuntimeError("Timeout waiting for Hermes response")

        if not response_line:
            log.error("Hermes stdout closed (exit=%s)", hermes_process.returncode)
            hermes_process = None
            raise RuntimeError("Hermes process exited")

    try:
        return json.loads(response_line.decode("utf-8"))
    except json.JSONDecodeError:
        raw = response_line.decode("utf-8", errors="replace")[:200]
        raise RuntimeError(f"Invalid JSON from Hermes: {raw}")


# ─── HTTP Handlers ──────────────────────────────────────────────────

async def mcp_handler(request: web.Request) -> web.Response:
    """POST /mcp — handle JSON-RPC with skill injection."""
    global hermes_process

    try:
        body = await request.json()
    except Exception:
        return web.json_response(
            {"jsonrpc": "2.0", "id": None,
             "error": {"code": -32700, "message": "Parse error"}},
            status=400,
        )

    method = body.get("method", "")
    msg_id = body.get("id")
    log.info("→ %s (id=%s)", method, msg_id)

    # ── Handle skill-tool calls locally ──
    if method == "tools/call":
        params = body.get("params", {})
        tool_name = params.get("name", "")
        if tool_name.startswith("skill__"):
            content = _read_skill_content(tool_name)
            return web.json_response({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "content": [{"type": "text", "text": content}],
                },
            })

    # ── Forward to Hermes ──
    try:
        await ensure_hermes_process()
    except Exception as e:
        log.error("Failed to start Hermes: %s", e)
        return web.json_response(
            {"jsonrpc": "2.0", "id": msg_id,
             "error": {"code": -32603, "message": f"Hermes unavailable: {e}"}},
            status=503,
        )

    try:
        response_data = await _forward_to_hermes(body)
    except RuntimeError as e:
        return web.json_response(
            {"jsonrpc": "2.0", "id": msg_id,
             "error": {"code": -32603, "message": str(e)}},
            status=503 if "lost" in str(e).lower() or "exited" in str(e).lower() else 500,
        )

    # ── Inject skills into tools/list response ──
    if method == "tools/list" and "result" in response_data:
        tools = response_data["result"].get("tools", [])
        for st in SKILL_TOOLS:
            if not any(t.get("name") == st["name"] for t in tools):
                tools.append(st)
        response_data["result"]["tools"] = tools

    return web.json_response(response_data)


async def health_handler(request: web.Request) -> web.Response:
    alive = hermes_process is not None and hermes_process.returncode is None
    return web.json_response({
        "status": "ok" if alive else "starting",
        "hermes_pid": hermes_process.pid if alive else None,
        "skills_loaded": len(SKILL_TOOLS),
    })


async def on_shutdown(app: web.Application):
    global hermes_process
    if hermes_process and hermes_process.returncode is None:
        log.info("Terminating Hermes (pid=%s)...", hermes_process.pid)
        hermes_process.terminate()
        try:
            await asyncio.wait_for(hermes_process.wait(), timeout=5)
        except asyncio.TimeoutError:
            hermes_process.kill()
            await hermes_process.wait()
        log.info("Hermes terminated.")


def main():
    global SKILL_TOOLS, _cached_skills

    # Discover skills at startup
    _cached_skills = discover_skills()
    SKILL_TOOLS = [_skill_to_tool(s) for s in _cached_skills]
    log.info("Discovered %d skills → %d MCP tools", len(_cached_skills), len(SKILL_TOOLS))

    app = web.Application()
    app.router.add_get("/", health_handler)
    app.router.add_post("/mcp", mcp_handler)
    app.on_shutdown.append(on_shutdown)

    log.info("Starting Hermes MCP bridge on %s:%s", LISTEN_HOST, LISTEN_PORT)
    web.run_app(app, host=LISTEN_HOST, port=LISTEN_PORT, print=log.info)


if __name__ == "__main__":
    main()
