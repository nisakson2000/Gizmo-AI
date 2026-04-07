"""Recitation detection and retrieval pipeline.

Intercepts requests to recite known texts (poems, speeches, lyrics) and
fetches the authoritative content from the web before the LLM sees the message.
"""

import asyncio
import logging
import re

logger = logging.getLogger(__name__)

# Patterns that indicate a recitation request.
# Each yields the subject as group "subject" or the remainder of the message.
_RECITE_PATTERNS: list[re.Pattern] = [
    # "recite X", "recite the X"
    re.compile(r"(?:^|\b)recite\s+(?:the\s+)?(?P<subject>.+)", re.I),
    # "full text of X"
    re.compile(r"(?:^|\b)full\s+text\s+of\s+(?P<subject>.+)", re.I),
    # "lyrics to/of X"
    re.compile(r"(?:^|\b)lyrics\s+(?:to|of)\s+(?P<subject>.+)", re.I),
    # "words to X"
    re.compile(r"(?:^|\b)words\s+to\s+(?P<subject>.+)", re.I),
    # "how does X go" — the subject is between "how does" and "go"
    re.compile(r"(?:^|\b)how\s+does\s+(?P<subject>.+?)\s+go\b", re.I),
    # "the Nth amendment" — constitutional amendments
    re.compile(r"(?:^|\b)the\s+(?P<subject>(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|twenty[- ]?(?:first|second|third|fourth|fifth|sixth|seventh)|\d+(?:st|nd|rd|th))\s+amendment)\b", re.I),
    # "quote from X" — must have "from" to avoid casual use
    re.compile(r"(?:^|\b)quote\s+from\s+(?P<subject>.+)", re.I),
    # "word for word" or "verbatim" — subject is the rest of the message
    re.compile(r"\b(?:word\s+for\s+word|verbatim)\b.*?(?P<subject>.{10,})", re.I),
]


def is_recitation_request(message: str) -> tuple[bool, str]:
    """Detect whether a message is a recitation request.

    Returns (True, subject) or (False, "").
    Conservative: prefer false negatives over false positives.
    """
    msg = message.strip()
    if not msg:
        return False, ""

    for pattern in _RECITE_PATTERNS:
        m = pattern.search(msg)
        if m:
            subject = m.group("subject").strip().rstrip("?.!")
            if subject and len(subject) > 2:
                return True, subject

    return False, ""


async def fetch_recitation_content(subject: str) -> tuple[str, str]:
    """Search the web for the subject and fetch the best full-text result.

    Returns (content, source_url) or ("", "") on failure.
    """
    from search import web_search
    from web_fetch import fetch_page

    results = await web_search(f'"{subject}" full text', num_results=5)

    # Filter out error results
    valid = [r for r in results if "error" not in r and r.get("url")]
    if not valid:
        logger.info("Recitation: no search results for '%s'", subject)
        return "", ""

    # Fetch top 3 pages concurrently, pick the longest meaningful result
    top = valid[:3]
    texts = await asyncio.gather(*(fetch_page(r["url"]) for r in top), return_exceptions=True)

    best_content = ""
    best_url = ""
    for r, text in zip(top, texts):
        if isinstance(text, str) and len(text) > 200 and len(text) > len(best_content):
            best_content = text
            best_url = r["url"]

    if best_content:
        logger.info("Recitation: fetched %d chars from %s", len(best_content), best_url)
    else:
        logger.info("Recitation: all fetches too short or failed for '%s'", subject)

    return best_content, best_url


def build_recitation_context(content: str, source_url: str, subject: str) -> str:
    """Build the XML-tagged recitation context block for system prompt injection."""
    return f"""<recitation-content>
The user has asked you to recite or present the following known text.
The complete, authoritative text is provided below. Present it EXACTLY as shown.
Do not paraphrase, abbreviate, rewrite, or fill in gaps from memory.
Preserve line breaks for poems and paragraph structure for prose.
You may add a brief one-sentence introduction acknowledging their request.
If the text appears incomplete, say so rather than completing it from memory.

Source: {source_url}
Subject: {subject}

--- BEGIN AUTHORITATIVE TEXT ---
{content}
--- END AUTHORITATIVE TEXT ---
</recitation-content>"""
