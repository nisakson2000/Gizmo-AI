"""Tool definitions and dispatch for the tracker module (tasks + notes)."""

import json
from typing import Any

from tracker_db import (
    create_task,
    update_task,
    complete_task,
    delete_task,
    list_tasks,
    create_note,
    update_note,
    delete_note,
    list_notes,
)

# OpenAI function-calling format tool definitions
TRACKER_TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "create_task",
            "description": "Create a new task. Use when the user wants to add a to-do, reminder, or action item.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "Short title for the task",
                    },
                    "description": {
                        "type": "string",
                        "description": "Detailed description (optional)",
                    },
                    "priority": {
                        "type": "string",
                        "enum": ["low", "medium", "high"],
                        "description": "Task priority (default: medium)",
                    },
                    "due_date": {
                        "type": "string",
                        "description": "Due date in ISO 8601 format, e.g. '2026-04-01' (optional)",
                    },
                    "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Tags for categorization (optional)",
                    },
                    "parent_id": {
                        "type": "string",
                        "description": "ID of a parent task to create this as a subtask (optional)",
                    },
                    "recurrence": {
                        "type": "string",
                        "enum": ["none", "daily", "weekly", "biweekly", "monthly", "yearly"],
                        "description": "Recurrence schedule (default: none)",
                    },
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_task",
            "description": "Update an existing task's fields. Use when the user wants to change a task's title, description, priority, due date, tags, or status.",
            "parameters": {
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "The task ID to update",
                    },
                    "title": {
                        "type": "string",
                        "description": "New title (optional)",
                    },
                    "description": {
                        "type": "string",
                        "description": "New description (optional)",
                    },
                    "status": {
                        "type": "string",
                        "enum": ["todo", "in_progress", "done"],
                        "description": "New status (optional)",
                    },
                    "priority": {
                        "type": "string",
                        "enum": ["low", "medium", "high"],
                        "description": "New priority (optional)",
                    },
                    "due_date": {
                        "type": "string",
                        "description": "New due date in ISO 8601 format (optional)",
                    },
                    "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "New tags (optional, replaces existing)",
                    },
                    "recurrence": {
                        "type": "string",
                        "enum": ["none", "daily", "weekly", "biweekly", "monthly", "yearly"],
                        "description": "New recurrence schedule (optional)",
                    },
                },
                "required": ["task_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "complete_task",
            "description": "Mark a task as completed. If the task has a recurrence schedule, a new task will be created for the next occurrence automatically.",
            "parameters": {
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "The task ID to complete",
                    },
                },
                "required": ["task_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_task",
            "description": "Permanently delete a task and its subtasks. Use only when the user explicitly wants to remove a task.",
            "parameters": {
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "The task ID to delete",
                    },
                },
                "required": ["task_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_tasks",
            "description": "List tasks with optional filters. Use when the user wants to see their tasks, to-dos, or action items.",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["todo", "in_progress", "done"],
                        "description": "Filter by status (optional)",
                    },
                    "priority": {
                        "type": "string",
                        "enum": ["low", "medium", "high"],
                        "description": "Filter by priority (optional)",
                    },
                    "tag": {
                        "type": "string",
                        "description": "Filter by tag (optional)",
                    },
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_note",
            "description": "Create a new note. Use when the user wants to save a note, idea, or piece of information.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "Note title",
                    },
                    "content": {
                        "type": "string",
                        "description": "Note body content (optional)",
                    },
                    "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Tags for categorization (optional)",
                    },
                    "pinned": {
                        "type": "boolean",
                        "description": "Whether to pin this note (default: false)",
                    },
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_note",
            "description": "Update an existing note's fields.",
            "parameters": {
                "type": "object",
                "properties": {
                    "note_id": {
                        "type": "string",
                        "description": "The note ID to update",
                    },
                    "title": {
                        "type": "string",
                        "description": "New title (optional)",
                    },
                    "content": {
                        "type": "string",
                        "description": "New content (optional)",
                    },
                    "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "New tags (optional, replaces existing)",
                    },
                    "pinned": {
                        "type": "boolean",
                        "description": "Whether to pin this note (optional)",
                    },
                },
                "required": ["note_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_note",
            "description": "Permanently delete a note. Use only when the user explicitly wants to remove a note.",
            "parameters": {
                "type": "object",
                "properties": {
                    "note_id": {
                        "type": "string",
                        "description": "The note ID to delete",
                    },
                },
                "required": ["note_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_notes",
            "description": "List notes with optional filters. Use when the user wants to see their saved notes.",
            "parameters": {
                "type": "object",
                "properties": {
                    "tag": {
                        "type": "string",
                        "description": "Filter by tag (optional)",
                    },
                    "search": {
                        "type": "string",
                        "description": "Search term to filter by title or content (optional)",
                    },
                    "pinned_only": {
                        "type": "boolean",
                        "description": "Only return pinned notes (default: false)",
                    },
                },
                "required": [],
            },
        },
    },
]


def _format_task(task: dict) -> str:
    """Format a single task for display."""
    parts = [f"[{task['id']}] {task['title']}"]
    parts.append(f"  Status: {task['status']} | Priority: {task['priority']}")
    if task.get("description"):
        parts.append(f"  Description: {task['description']}")
    if task.get("due_date"):
        parts.append(f"  Due: {task['due_date']}")
    if task.get("tags"):
        parts.append(f"  Tags: {', '.join(task['tags'])}")
    if task.get("recurrence") and task["recurrence"] != "none":
        parts.append(f"  Recurrence: {task['recurrence']}")
    if task.get("parent_id"):
        parts.append(f"  Parent: {task['parent_id']}")
    if task.get("subtasks"):
        for sub in task["subtasks"]:
            status_mark = "x" if sub["status"] == "done" else " "
            parts.append(f"  [{status_mark}] {sub['id']}: {sub['title']}")
    return "\n".join(parts)


def _format_note(note: dict) -> str:
    """Format a single note for display."""
    pin = " (pinned)" if note.get("pinned") else ""
    parts = [f"[{note['id']}]{pin} {note['title']}"]
    if note.get("content"):
        # Show first 200 chars of content in list view
        preview = note["content"][:200]
        if len(note["content"]) > 200:
            preview += "..."
        parts.append(f"  {preview}")
    if note.get("tags"):
        parts.append(f"  Tags: {', '.join(note['tags'])}")
    return "\n".join(parts)


async def execute_tracker_tool(name: str, arguments: dict[str, Any]) -> str:
    """Execute a tracker tool by name and return a formatted string result."""

    if name == "create_task":
        task = create_task(
            title=arguments["title"],
            description=arguments.get("description", ""),
            priority=arguments.get("priority", "medium"),
            due_date=arguments.get("due_date"),
            tags=arguments.get("tags"),
            parent_id=arguments.get("parent_id"),
            recurrence=arguments.get("recurrence", "none"),
        )
        return f"Task created:\n{_format_task(task)}"

    elif name == "update_task":
        task_id = arguments.get("task_id")
        fields = {k: v for k, v in arguments.items() if k != "task_id"}
        task = update_task(task_id, **fields)
        if not task:
            return f"Task '{task_id}' not found."
        return f"Task updated:\n{_format_task(task)}"

    elif name == "complete_task":
        result = complete_task(arguments["task_id"])
        if not result:
            return f"Task '{arguments['task_id']}' not found."
        parts = [f"Task completed:\n{_format_task(result['completed'])}"]
        if "next" in result:
            parts.append(f"\nNext occurrence created:\n{_format_task(result['next'])}")
        return "\n".join(parts)

    elif name == "delete_task":
        deleted = delete_task(arguments["task_id"])
        if not deleted:
            return f"Task '{arguments['task_id']}' not found."
        return f"Task '{arguments['task_id']}' deleted."

    elif name == "list_tasks":
        tasks = list_tasks(
            status=arguments.get("status"),
            priority=arguments.get("priority"),
            tag=arguments.get("tag"),
        )
        if not tasks:
            return "No tasks found."
        lines = [f"{len(tasks)} task(s):\n"]
        for t in tasks:
            lines.append(_format_task(t))
            lines.append("")
        return "\n".join(lines)

    elif name == "create_note":
        note = create_note(
            title=arguments["title"],
            content=arguments.get("content", ""),
            tags=arguments.get("tags"),
            pinned=arguments.get("pinned", False),
        )
        return f"Note created:\n{_format_note(note)}"

    elif name == "update_note":
        note_id = arguments.get("note_id")
        fields = {k: v for k, v in arguments.items() if k != "note_id"}
        note = update_note(note_id, **fields)
        if not note:
            return f"Note '{note_id}' not found."
        return f"Note updated:\n{_format_note(note)}"

    elif name == "delete_note":
        deleted = delete_note(arguments["note_id"])
        if not deleted:
            return f"Note '{arguments['note_id']}' not found."
        return f"Note '{arguments['note_id']}' deleted."

    elif name == "list_notes":
        notes = list_notes(
            tag=arguments.get("tag"),
            search=arguments.get("search"),
            pinned_only=arguments.get("pinned_only", False),
        )
        if not notes:
            return "No notes found."
        lines = [f"{len(notes)} note(s):\n"]
        for n in notes:
            lines.append(_format_note(n))
            lines.append("")
        return "\n".join(lines)

    else:
        return f"Unknown tracker tool: {name}"
