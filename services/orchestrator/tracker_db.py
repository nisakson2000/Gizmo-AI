"""Database operations for the Gizmo-AI task/note tracker."""

import json
import logging
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

logger = logging.getLogger("gizmo.error")

DB_PATH = Path("/app/tracker/tracker.db")


def _get_conn():
    """Create a new database connection with Row factory."""
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def _new_id() -> str:
    return uuid.uuid4().hex[:12]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# --- Schema ---


def init_tracker_db():
    """Create tasks and notes tables if they don't exist."""
    conn = _get_conn()
    try:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT DEFAULT '',
                status TEXT DEFAULT 'todo',
                priority TEXT DEFAULT 'medium',
                due_date TEXT,
                tags TEXT DEFAULT '[]',
                parent_id TEXT,
                recurrence TEXT DEFAULT 'none',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT,
                FOREIGN KEY (parent_id) REFERENCES tasks(id)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS notes (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT DEFAULT '',
                tags TEXT DEFAULT '[]',
                pinned INTEGER DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        conn.commit()
    finally:
        conn.close()


# --- Tasks ---


def create_task(
    title: str,
    description: str = "",
    priority: str = "medium",
    due_date: str | None = None,
    tags: list[str] | None = None,
    parent_id: str | None = None,
    recurrence: str = "none",
) -> dict:
    """Create a new task and return it as a dict."""
    task_id = _new_id()
    now = _now_iso()
    tags_json = json.dumps(tags or [])

    conn = _get_conn()
    try:
        conn.execute(
            """INSERT INTO tasks (id, title, description, status, priority, due_date,
               tags, parent_id, recurrence, created_at, updated_at)
               VALUES (?, ?, ?, 'todo', ?, ?, ?, ?, ?, ?, ?)""",
            (task_id, title, description, priority, due_date, tags_json,
             parent_id, recurrence, now, now),
        )
        conn.commit()
        return _task_to_dict(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())
    finally:
        conn.close()


def update_task(task_id: str, **fields) -> dict | None:
    """Update a task's fields. Returns the updated task or None if not found."""
    conn = _get_conn()
    try:
        existing = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not existing:
            return None

        allowed = {"title", "description", "status", "priority", "due_date", "tags", "recurrence", "parent_id"}
        updates = {}
        for key, value in fields.items():
            if key in allowed:
                if key == "tags" and isinstance(value, list):
                    updates[key] = json.dumps(value)
                else:
                    updates[key] = value

        if not updates:
            return _task_to_dict(existing)

        updates["updated_at"] = _now_iso()
        set_clause = ", ".join(f"{k} = ?" for k in updates)
        values = list(updates.values()) + [task_id]
        conn.execute(f"UPDATE tasks SET {set_clause} WHERE id = ?", values)
        conn.commit()
        return _task_to_dict(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())
    finally:
        conn.close()


def complete_task(task_id: str) -> dict | None:
    """Mark a task as done. If it has recurrence, create the next occurrence.

    Returns a dict with 'completed' (the finished task) and optionally 'next'
    (the newly created recurring task).
    """
    conn = _get_conn()
    try:
        row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not row:
            return None

        now = _now_iso()
        conn.execute(
            "UPDATE tasks SET status = 'done', completed_at = ?, updated_at = ? WHERE id = ?",
            (now, now, task_id),
        )

        # Handle recurrence — create next occurrence before committing
        next_id = None
        recurrence = row["recurrence"]
        if recurrence and recurrence != "none" and row["due_date"]:
            next_due = _calc_next_due(row["due_date"], recurrence)
            if next_due:
                next_id = _new_id()
                next_now = _now_iso()
                tags_json = row["tags"]
                conn.execute(
                    """INSERT INTO tasks (id, title, description, status, priority, due_date,
                       tags, parent_id, recurrence, created_at, updated_at)
                       VALUES (?, ?, ?, 'todo', ?, ?, ?, ?, ?, ?, ?)""",
                    (next_id, row["title"], row["description"], row["priority"],
                     next_due, tags_json, row["parent_id"], recurrence, next_now, next_now),
                )

        # Single atomic commit for both UPDATE + INSERT
        conn.commit()

        completed = _task_to_dict(conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone())
        result: dict = {"completed": completed}
        if next_id:
            result["next"] = _task_to_dict(
                conn.execute("SELECT * FROM tasks WHERE id = ?", (next_id,)).fetchone()
            )

        return result
    finally:
        conn.close()


def delete_task(task_id: str) -> bool:
    """Delete a task and all descendant subtasks recursively. Returns True if the task existed."""
    conn = _get_conn()
    try:
        existing = conn.execute("SELECT id FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not existing:
            return False
        # Recursive CTE to find all descendants
        conn.execute("""
            DELETE FROM tasks WHERE id IN (
                WITH RECURSIVE descendants(id) AS (
                    SELECT id FROM tasks WHERE parent_id = ?
                    UNION ALL
                    SELECT t.id FROM tasks t JOIN descendants d ON t.parent_id = d.id
                )
                SELECT id FROM descendants
            )
        """, (task_id,))
        conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
        conn.commit()
        return True
    finally:
        conn.close()


def list_tasks(
    status: str | None = None,
    priority: str | None = None,
    tag: str | None = None,
    parent_id: str | None = None,
    include_subtasks: bool = True,
) -> list[dict]:
    """List tasks with optional filters."""
    conn = _get_conn()
    try:
        query = "SELECT * FROM tasks WHERE 1=1"
        params: list = []

        if status:
            query += " AND status = ?"
            params.append(status)
        if priority:
            query += " AND priority = ?"
            params.append(priority)
        if parent_id is not None:
            query += " AND parent_id = ?"
            params.append(parent_id)
        elif not include_subtasks:
            query += " AND parent_id IS NULL"
        if tag:
            query += " AND EXISTS (SELECT 1 FROM json_each(tags) WHERE value = ?)"
            params.append(tag)

        query += " ORDER BY CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 WHEN 'low' THEN 2 END, created_at DESC"

        rows = conn.execute(query, params).fetchall()
        return [_task_to_dict(r) for r in rows]
    finally:
        conn.close()


def get_task(task_id: str) -> dict | None:
    """Get a single task with its subtasks."""
    conn = _get_conn()
    try:
        row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if not row:
            return None
        task = _task_to_dict(row)
        subtasks = conn.execute(
            "SELECT * FROM tasks WHERE parent_id = ? ORDER BY created_at", (task_id,)
        ).fetchall()
        task["subtasks"] = [_task_to_dict(s) for s in subtasks]
        return task
    finally:
        conn.close()


# --- Notes ---


def create_note(
    title: str,
    content: str = "",
    tags: list[str] | None = None,
    pinned: bool = False,
) -> dict:
    """Create a new note and return it as a dict."""
    note_id = _new_id()
    now = _now_iso()
    tags_json = json.dumps(tags or [])

    conn = _get_conn()
    try:
        conn.execute(
            """INSERT INTO notes (id, title, content, tags, pinned, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (note_id, title, content, tags_json, int(pinned), now, now),
        )
        conn.commit()
        return _note_to_dict(conn.execute("SELECT * FROM notes WHERE id = ?", (note_id,)).fetchone())
    finally:
        conn.close()


def update_note(note_id: str, **fields) -> dict | None:
    """Update a note's fields. Returns the updated note or None if not found."""
    conn = _get_conn()
    try:
        existing = conn.execute("SELECT * FROM notes WHERE id = ?", (note_id,)).fetchone()
        if not existing:
            return None

        allowed = {"title", "content", "tags", "pinned"}
        updates = {}
        for key, value in fields.items():
            if key in allowed:
                if key == "tags" and isinstance(value, list):
                    updates[key] = json.dumps(value)
                elif key == "pinned":
                    updates[key] = int(value)
                else:
                    updates[key] = value

        if not updates:
            return _note_to_dict(existing)

        updates["updated_at"] = _now_iso()
        set_clause = ", ".join(f"{k} = ?" for k in updates)
        values = list(updates.values()) + [note_id]
        conn.execute(f"UPDATE notes SET {set_clause} WHERE id = ?", values)
        conn.commit()
        return _note_to_dict(conn.execute("SELECT * FROM notes WHERE id = ?", (note_id,)).fetchone())
    finally:
        conn.close()


def delete_note(note_id: str) -> bool:
    """Delete a note. Returns True if it existed."""
    conn = _get_conn()
    try:
        existing = conn.execute("SELECT id FROM notes WHERE id = ?", (note_id,)).fetchone()
        if not existing:
            return False
        conn.execute("DELETE FROM notes WHERE id = ?", (note_id,))
        conn.commit()
        return True
    finally:
        conn.close()


def list_notes(
    tag: str | None = None,
    search: str | None = None,
    pinned_only: bool = False,
    limit: int | None = None,
) -> list[dict]:
    """List notes with optional filters."""
    conn = _get_conn()
    try:
        query = "SELECT * FROM notes WHERE 1=1"
        params: list = []

        if pinned_only:
            query += " AND pinned = 1"
        if tag:
            query += " AND EXISTS (SELECT 1 FROM json_each(tags) WHERE value = ?)"
            params.append(tag)
        if search:
            query += " AND (title LIKE ? OR content LIKE ?)"
            params.extend([f"%{search}%", f"%{search}%"])

        query += " ORDER BY pinned DESC, updated_at DESC"
        if limit:
            query += f" LIMIT {int(limit)}"

        rows = conn.execute(query, params).fetchall()
        return [_note_to_dict(r) for r in rows]
    finally:
        conn.close()


def get_note(note_id: str) -> dict | None:
    """Get a single note by ID."""
    conn = _get_conn()
    try:
        row = conn.execute("SELECT * FROM notes WHERE id = ?", (note_id,)).fetchone()
        if not row:
            return None
        return _note_to_dict(row)
    finally:
        conn.close()


# --- Tags ---


def list_tags() -> list[str]:
    """Return all unique tags used across tasks and notes."""
    conn = _get_conn()
    try:
        all_tags = set()
        for row in conn.execute("SELECT tags FROM tasks").fetchall():
            for t in json.loads(row["tags"]):
                all_tags.add(t)
        for row in conn.execute("SELECT tags FROM notes").fetchall():
            for t in json.loads(row["tags"]):
                all_tags.add(t)
        return sorted(all_tags)
    finally:
        conn.close()


# --- Helpers ---


def _task_to_dict(row: sqlite3.Row) -> dict:
    """Convert a sqlite3.Row to a plain dict with parsed tags."""
    d = dict(row)
    d["tags"] = json.loads(d["tags"]) if d["tags"] else []
    return d


def _note_to_dict(row: sqlite3.Row) -> dict:
    """Convert a sqlite3.Row to a plain dict with parsed tags and bool pinned."""
    d = dict(row)
    d["tags"] = json.loads(d["tags"]) if d["tags"] else []
    d["pinned"] = bool(d["pinned"])
    return d


def _calc_next_due(due_date_str: str, recurrence: str) -> str | None:
    """Calculate the next due date based on recurrence type.

    Supported recurrence values: daily, weekly, biweekly, monthly, yearly.
    """
    try:
        due = datetime.fromisoformat(due_date_str)
    except ValueError:
        return None

    if recurrence == "daily":
        due += timedelta(days=1)
    elif recurrence == "weekly":
        due += timedelta(weeks=1)
    elif recurrence == "biweekly":
        due += timedelta(weeks=2)
    elif recurrence == "monthly":
        # Advance by one month, handle month-end edge cases
        month = due.month % 12 + 1
        year = due.year + (1 if due.month == 12 else 0)
        day = min(due.day, _days_in_month(year, month))
        due = due.replace(year=year, month=month, day=day)
    elif recurrence == "yearly":
        year = due.year + 1
        day = min(due.day, _days_in_month(year, due.month))
        due = due.replace(year=year, day=day)
    else:
        return None

    return due.isoformat()


def _days_in_month(year: int, month: int) -> int:
    """Return the number of days in a given month."""
    if month == 12:
        return (datetime(year + 1, 1, 1) - datetime(year, 12, 1)).days
    return (datetime(year, month + 1, 1) - datetime(year, month, 1)).days
