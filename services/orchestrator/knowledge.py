"""Temporal knowledge graph — automatic fact extraction, entity tracking, and invalidation.

Extracts structured facts from conversation exchanges via the local LLM,
maintains validity windows (valid_from/valid_to), and injects current facts
into the system prompt. Entity names are normalized for consistent lookup.

All LLM calls are local (orchestrator → gizmo-llama within Podman network).
"""

import json
import logging
import os
import re
import sqlite3
from pathlib import Path

import httpx

logger = logging.getLogger("gizmo.conversations")

DB_PATH = Path("/app/memory/conversations.db")
LLAMA_HOST = os.getenv("LLAMA_HOST", "gizmo-llama")
LLAMA_PORT = os.getenv("LLAMA_PORT", "8080")
LLAMA_URL = f"http://{LLAMA_HOST}:{LLAMA_PORT}"

KNOWLEDGE_EXTRACTION_ENABLED = os.getenv("KNOWLEDGE_EXTRACTION_ENABLED", "true").lower() == "true"

FACT_EXTRACTION_PROMPT = """Extract key facts from this conversation exchange as a JSON array.
Focus on:
- User preferences ("I prefer X", "I use Y", "I like Z")
- Decisions ("we decided", "let's go with", "I chose")
- Technical choices ("using X for Y", "switched from X to Y")
- People mentioned (names, roles, relationships)
- Project details (names, status, goals)
- Tools and technologies mentioned by name

Format: [{"subject": "entity_name", "predicate": "relationship", "object": "value", "confidence": 0.5-1.0}]

Rules:
- Max 5 facts per exchange
- Only extract CLEARLY STATED facts, not inferences
- Use simple predicates: "uses", "prefers", "is", "works_on", "decided", "knows", "discussed"
- Confidence 0.9 for explicit statements ("I use Python")
- Confidence 0.7 for strong implications ("we went with FastAPI" → decided to use)
- Confidence 0.5 for weak implications
- If no clear facts, return []"""

_TITLE_RE = re.compile(r"^(dr|mr|mrs|ms|prof)\.?\s+", re.I)


def normalize_entity(name: str) -> str:
    """Normalize entity names for consistent storage and lookup."""
    name = name.strip().lower()
    name = _TITLE_RE.sub("", name)
    name = re.sub(r"\s+", "_", name)
    name = re.sub(r"[^\w]", "", name)
    return name


async def maybe_extract_facts(
    conversation_id: str,
    user_text: str,
    assistant_text: str,
    user_msg_index: int,
) -> None:
    """Extract facts from a conversation exchange if enabled.

    Fire-and-forget — called via asyncio.create_task after response save.
    """
    if not KNOWLEDGE_EXTRACTION_ENABLED:
        return
    if len(user_text.strip()) < 20:
        return

    try:
        exchange = f"User: {user_text[:1000]}\nAssistant: {assistant_text[:1000]}"
        facts_json = await _call_llm_for_facts(exchange)
        if not facts_json:
            return

        try:
            facts = json.loads(facts_json)
        except json.JSONDecodeError:
            # Try to extract JSON array from surrounding text
            match = re.search(r"\[.*\]", facts_json, re.DOTALL)
            if match:
                try:
                    facts = json.loads(match.group())
                except json.JSONDecodeError:
                    logger.warning("Fact extraction JSON parse failed for conv=%s", conversation_id)
                    return
            else:
                logger.warning("Fact extraction JSON parse failed for conv=%s", conversation_id)
                return

        if not isinstance(facts, list):
            return

        conn = sqlite3.connect(str(DB_PATH))
        try:
            for fact in facts[:5]:  # enforce max 5
                if not isinstance(fact, dict):
                    continue
                subject = normalize_entity(fact.get("subject", ""))
                predicate = fact.get("predicate", "").strip().lower()
                obj = normalize_entity(fact.get("object", ""))
                confidence = float(fact.get("confidence", 0.7))

                if not subject or not predicate or not obj:
                    continue
                if confidence < 0.5:
                    continue

                # Check for contradictions — same subject+predicate, different object
                existing = conn.execute(
                    """SELECT id FROM knowledge_facts
                       WHERE subject = ? AND predicate = ? AND valid_to IS NULL AND object != ?""",
                    (subject, predicate, obj),
                ).fetchall()

                for row in existing:
                    conn.execute(
                        "UPDATE knowledge_facts SET valid_to = datetime('now') WHERE id = ?",
                        (row[0],),
                    )

                # Skip if exact same fact already exists and is current
                dupe = conn.execute(
                    """SELECT id FROM knowledge_facts
                       WHERE subject = ? AND predicate = ? AND object = ? AND valid_to IS NULL""",
                    (subject, predicate, obj),
                ).fetchone()
                if dupe:
                    continue

                conn.execute(
                    """INSERT INTO knowledge_facts
                       (subject, predicate, object, valid_from, source_conversation_id,
                        source_message_index, confidence, created_at)
                       VALUES (?, ?, ?, datetime('now'), ?, ?, ?, datetime('now'))""",
                    (subject, predicate, obj, conversation_id, user_msg_index, confidence),
                )

            conn.commit()
            logger.info("Fact extraction: conv=%s extracted %d facts", conversation_id, len(facts))
        finally:
            conn.close()

    except Exception as e:
        logger.warning("Fact extraction failed for %s: %s", conversation_id, e)


async def _call_llm_for_facts(exchange_text: str) -> str:
    """Call the local LLM to extract facts from a conversation exchange."""
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{LLAMA_URL}/v1/chat/completions",
                json={
                    "model": "gizmo",
                    "messages": [
                        {"role": "system", "content": FACT_EXTRACTION_PROMPT},
                        {"role": "user", "content": exchange_text},
                    ],
                    "stream": False,
                    "max_tokens": 300,
                    "temperature": 0.2,
                    "chat_template_kwargs": {"enable_thinking": False},
                },
            )
            if resp.status_code != 200:
                logger.warning("Fact extraction LLM returned %d", resp.status_code)
                return ""
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()
    except Exception as e:
        logger.warning("Fact extraction LLM call failed: %s", e)
        return ""


def get_relevant_facts(query: str, max_facts: int = 8) -> list[dict]:
    """Retrieve current (valid_to IS NULL) facts relevant to the query.

    Scores facts by keyword overlap with the query. Always includes
    high-confidence user facts. Returns up to max_facts results.
    """
    try:
        conn = sqlite3.connect(str(DB_PATH))
        conn.row_factory = sqlite3.Row
        try:
            rows = conn.execute(
                """SELECT subject, predicate, object, confidence
                   FROM knowledge_facts
                   WHERE valid_to IS NULL AND confidence >= 0.6
                   ORDER BY created_at DESC""",
            ).fetchall()
        finally:
            conn.close()

        if not rows:
            return []

        query_words = set(re.findall(r"\w+", query.lower()))
        scored = []
        for row in rows:
            fact_words = set(f"{row['subject']} {row['predicate']} {row['object']}".split("_"))
            fact_words.update(f"{row['subject']} {row['predicate']} {row['object']}".split())
            overlap = len(query_words & fact_words)
            scored.append((overlap, dict(row)))

        scored.sort(key=lambda x: x[0], reverse=True)
        return [row for _, row in scored[:max_facts]]

    except Exception as e:
        logger.warning("get_relevant_facts failed: %s", e)
        return []


def format_knowledge_facts(facts: list[dict]) -> str:
    """Format knowledge facts as XML block for system prompt injection."""
    if not facts:
        return ""

    lines = [
        "<knowledge-facts>",
        "Known facts about the user and context:",
    ]
    for f in facts:
        lines.append(f"- {f['subject']} {f['predicate']} {f['object']}")

    lines.append("</knowledge-facts>")
    return "\n".join(lines)
