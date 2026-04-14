"""Gizmo Tracker module — task/note management REST API + embedded AI chat."""

import asyncio
import json
import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from llm import stream_chat
from prompt_loader import load_prompt
from tracker_db import (
    create_task, update_task, complete_task, delete_task,
    list_tasks, get_task, create_note, update_note,
    delete_note, list_notes, get_note, list_tags,
)
from tracker_tools import TRACKER_TOOL_DEFINITIONS, execute_tracker_tool

logger = logging.getLogger("gizmo.error")
conv_log = logging.getLogger("gizmo.conversations")

TRACKER_PROMPT_PATH = Path("/app/config/tracker-prompt.txt")

router = APIRouter()

# --- Helpers ---

def _load_tracker_prompt() -> str:
    return load_prompt(TRACKER_PROMPT_PATH, "You are a task and note tracker assistant.")


def _build_context_summary_sync(max_tokens: int = 500) -> str:
    """Build a compact summary of open tasks and recent notes for injection.

    Aims for roughly max_tokens worth of text (~4 chars/token heuristic).
    """
    char_budget = max_tokens * 4
    parts: list[str] = []

    # Open tasks
    open_tasks = list_tasks(status="todo")
    if open_tasks:
        parts.append("## Open Tasks")
        for t in open_tasks:
            line = f"- id={t['id']} | [{t['priority']}] \"{t['title']}\""
            if t.get("due_date"):
                line += f" (due: {t['due_date']})"
            if t.get("tags"):
                line += f" [{', '.join(t['tags'])}]"
            parts.append(line)

    # In-progress tasks
    progress_tasks = list_tasks(status="in_progress")
    if progress_tasks:
        parts.append("\n## In Progress")
        for t in progress_tasks:
            line = f"- id={t['id']} | [{t['priority']}] \"{t['title']}\""
            if t.get("due_date"):
                line += f" (due: {t['due_date']})"
            parts.append(line)

    # Recent notes (last 5 via SQL LIMIT)
    recent_notes = list_notes(limit=5)
    if recent_notes:
        parts.append("\n## Recent Notes")
        for n in recent_notes:
            line = f"- id={n['id']} | \"{n['title']}\""
            if n.get("pinned"):
                line += " [pinned]"
            if n.get("tags"):
                line += f" [{', '.join(n['tags'])}]"
            parts.append(line)

    summary = "\n".join(parts)
    if len(summary) > char_budget:
        # Truncate at line boundary
        truncated = summary[:char_budget].rsplit("\n", 1)[0]
        summary = truncated + "\n..."
    return summary


async def _build_context_summary(max_tokens: int = 500) -> str:
    """Async wrapper — runs sync DB queries in a thread to avoid blocking the event loop."""
    return await asyncio.to_thread(_build_context_summary_sync, max_tokens)


# --- REST: Tasks ---


@router.get("/api/tracker/tasks")
async def api_list_tasks(
    status: str | None = None,
    priority: str | None = None,
    tag: str | None = None,
    parent_id: str | None = None,
):
    """List tasks with optional filters."""
    try:
        tasks = list_tasks(status=status, priority=priority, tag=tag, parent_id=parent_id)
        return {"tasks": tasks}
    except Exception as e:
        logger.error("Failed to list tasks: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


@router.post("/api/tracker/tasks")
async def api_create_task(request: Request):
    """Create a new task."""
    try:
        request_body = await request.json()
        task = create_task(**request_body)
        return {"task": task}
    except Exception as e:
        logger.error("Failed to create task: %s", e, exc_info=True)
        return JSONResponse(status_code=400, content={"error": str(e)})


@router.get("/api/tracker/tasks/{task_id}")
async def api_get_task(task_id: str):
    """Get a task with its subtasks."""
    try:
        task = get_task(task_id)
        if not task:
            return JSONResponse(status_code=404, content={"error": "Task not found"})
        return {"task": task}
    except Exception as e:
        logger.error("Failed to get task %s: %s", task_id, e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


@router.patch("/api/tracker/tasks/{task_id}")
async def api_update_task(task_id: str, request: Request):
    """Update a task."""
    try:
        request_body = await request.json()
        task = update_task(task_id, **request_body)
        if not task:
            return JSONResponse(status_code=404, content={"error": "Task not found"})
        return {"task": task}
    except Exception as e:
        logger.error("Failed to update task %s: %s", task_id, e, exc_info=True)
        return JSONResponse(status_code=400, content={"error": str(e)})


@router.patch("/api/tracker/tasks/{task_id}/complete")
async def api_complete_task(task_id: str):
    """Complete a task. Handles recurrence if configured."""
    try:
        result = complete_task(task_id)
        if not result:
            return JSONResponse(status_code=404, content={"error": "Task not found"})
        return result
    except Exception as e:
        logger.error("Failed to complete task %s: %s", task_id, e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


@router.delete("/api/tracker/tasks/{task_id}")
async def api_delete_task(task_id: str):
    """Delete a task."""
    try:
        success = delete_task(task_id)
        if not success:
            return JSONResponse(status_code=404, content={"error": "Task not found"})
        return {"deleted": True}
    except Exception as e:
        logger.error("Failed to delete task %s: %s", task_id, e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


# --- REST: Notes ---


@router.get("/api/tracker/notes")
async def api_list_notes(
    tag: str | None = None,
    search: str | None = None,
    pinned: bool | None = None,
):
    """List notes with optional filters."""
    try:
        notes = list_notes(tag=tag, search=search, pinned_only=bool(pinned) if pinned is not None else False)
        return {"notes": notes}
    except Exception as e:
        logger.error("Failed to list notes: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


@router.post("/api/tracker/notes")
async def api_create_note(request: Request):
    """Create a new note."""
    try:
        request_body = await request.json()
        note = create_note(**request_body)
        return {"note": note}
    except Exception as e:
        logger.error("Failed to create note: %s", e, exc_info=True)
        return JSONResponse(status_code=400, content={"error": str(e)})


@router.get("/api/tracker/notes/{note_id}")
async def api_get_note(note_id: str):
    """Get a note."""
    try:
        note = get_note(note_id)
        if not note:
            return JSONResponse(status_code=404, content={"error": "Note not found"})
        return {"note": note}
    except Exception as e:
        logger.error("Failed to get note %s: %s", note_id, e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


@router.patch("/api/tracker/notes/{note_id}")
async def api_update_note(note_id: str, request: Request):
    """Update a note."""
    try:
        request_body = await request.json()
        note = update_note(note_id, **request_body)
        if not note:
            return JSONResponse(status_code=404, content={"error": "Note not found"})
        return {"note": note}
    except Exception as e:
        logger.error("Failed to update note %s: %s", note_id, e, exc_info=True)
        return JSONResponse(status_code=400, content={"error": str(e)})


@router.delete("/api/tracker/notes/{note_id}")
async def api_delete_note(note_id: str):
    """Delete a note."""
    try:
        success = delete_note(note_id)
        if not success:
            return JSONResponse(status_code=404, content={"error": "Note not found"})
        return {"deleted": True}
    except Exception as e:
        logger.error("Failed to delete note %s: %s", note_id, e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


# --- REST: Tags ---


@router.get("/api/tracker/tags")
async def api_list_tags():
    """List all unique tags across tasks and notes."""
    try:
        tags = list_tags()
        return {"tags": tags}
    except Exception as e:
        logger.error("Failed to list tags: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})


# --- WebSocket: Tracker AI Chat ---


@router.websocket("/ws/tracker")
async def ws_tracker(ws: WebSocket):
    from origins import check_ws_origin
    if not check_ws_origin(ws):
        await ws.close(code=4003, reason="Origin not allowed")
        return
    await ws.accept()

    # In-memory conversation history for this session (not DB-persisted)
    message_history: list[dict] = []

    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await ws.send_json({"type": "error", "error": "Invalid JSON"})
                continue

            user_text = msg.get("message", "")
            if not user_text:
                await ws.send_json({"type": "error", "error": "Empty message"})
                continue

            trace_id = f"tracker-{uuid.uuid4().hex[:8]}"
            await ws.send_json({"type": "trace_id", "trace_id": trace_id})
            conv_log.info("[%s] TRACKER USER: %s", trace_id, user_text[:500])

            # Build system prompt with context
            system_prompt = _load_tracker_prompt()
            context_summary = await _build_context_summary()
            if context_summary:
                system_prompt += f"\n\n--- Current Tracker State ---\n{context_summary}"

            # Append user message to history
            message_history.append({"role": "user", "content": user_text})

            # Build messages array: system + history
            messages = [{"role": "system", "content": system_prompt}] + message_history

            # Stream response with multi-round tool calling
            full_response = ""
            tool_calls_pending = []
            max_tool_rounds = 5
            tool_round = 0
            stream_errored = False

            async for event in stream_chat(messages, tools=TRACKER_TOOL_DEFINITIONS):
                if event["type"] == "token":
                    full_response += event["content"]
                    await ws.send_json(event)
                elif event["type"] == "thinking":
                    await ws.send_json(event)
                elif event["type"] == "tool_call":
                    tool_calls_pending.append(event)
                    await ws.send_json({
                        "type": "tool_call",
                        "tool": event["name"],
                        "status": "running",
                    })
                elif event["type"] == "error":
                    stream_errored = True
                    logger.error("[%s] Stream error: %s", trace_id, event["error"])
                    await ws.send_json({
                        "type": "error",
                        "error": event["error"],
                        "trace_id": trace_id,
                    })
                    break

            # Multi-round tool execution loop
            while tool_calls_pending and tool_round < max_tool_rounds:
                tool_round += 1

                # Append assistant message with tool calls
                messages.append({
                    "role": "assistant",
                    "content": full_response,
                    "tool_calls": [
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {"name": tc["name"], "arguments": tc["arguments"]},
                        }
                        for tc in tool_calls_pending
                    ],
                })

                # Execute each pending tool call
                for tc in tool_calls_pending:
                    conv_log.info(
                        "[%s] TRACKER_TOOL [round %d]: %s(%s)",
                        trace_id, tool_round, tc["name"], tc["arguments"][:200],
                    )
                    try:
                        args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                        result = await execute_tracker_tool(tc["name"], args)
                    except Exception as e:
                        result = f"Tool error: {str(e)}"
                        logger.error(
                            "[%s] Tracker tool '%s' failed: %s",
                            trace_id, tc["name"], e, exc_info=True,
                        )

                    conv_log.info("[%s] TRACKER_RESULT: %s → %s", trace_id, tc["name"], result[:300])
                    await ws.send_json({
                        "type": "tool_result",
                        "tool": tc["name"],
                        "result": result,
                    })

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc["id"],
                        "content": result,
                    })

                # Continue generation with tool results
                tool_calls_pending = []
                full_response = ""
                async for event in stream_chat(messages, tools=TRACKER_TOOL_DEFINITIONS):
                    if event["type"] == "token":
                        full_response += event["content"]
                        await ws.send_json(event)
                    elif event["type"] == "thinking":
                        await ws.send_json(event)
                    elif event["type"] == "tool_call":
                        tool_calls_pending.append(event)
                        await ws.send_json({
                            "type": "tool_call",
                            "tool": event["name"],
                            "status": "running",
                        })
                    elif event["type"] == "error":
                        stream_errored = True
                        logger.error(
                            "[%s] Stream error (tool round %d): %s",
                            trace_id, tool_round, event["error"],
                        )
                        await ws.send_json({
                            "type": "error",
                            "error": event["error"],
                            "trace_id": trace_id,
                        })
                        tool_calls_pending = []  # Stop looping on error
                        break

            # Persist assistant response in session history (skip on error to avoid corruption)
            if full_response and not stream_errored:
                message_history.append({"role": "assistant", "content": full_response})
                conv_log.info("[%s] TRACKER ASSISTANT: %s", trace_id, full_response[:500])

            await ws.send_json({"type": "done", "trace_id": trace_id})

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error("Tracker WebSocket handler error: %s", e, exc_info=True)
