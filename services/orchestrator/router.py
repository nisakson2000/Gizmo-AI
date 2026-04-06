"""Request router — selects tools and patterns based on user intent."""

import logging
import re
from typing import Optional

from patterns import match_pattern
from tools import get_default_tools, get_tool_schemas, has_tool

logger = logging.getLogger(__name__)


# Regex → tool names. Only high-confidence, unambiguous intent.
KEYWORD_ROUTES: list[tuple[re.Pattern, list[str]]] = [
    # Document generation
    (re.compile(r"\b(create|generate|make|write|build)\b.{0,20}\b(pdf|document|docx|spreadsheet|xlsx|pptx|csv|report)\b", re.I),
     ["generate_document"]),

    # ── Future routes (uncomment as capabilities are added) ──
    # Image generation
    # (re.compile(r"\b(draw|paint|sketch|generate|create|make|design|render)\b.{0,20}\b(image|picture|art|illustration|portrait|photo|icon|logo)\b", re.I),
    #  ["generate_image"]),

    # Video generation
    # (re.compile(r"\b(generate|create|make)\b.{0,15}\b(video|clip|animation)\b", re.I),
    #  ["generate_video"]),

    # Music generation
    # (re.compile(r"\b(compose|create|make|generate|play)\b.{0,15}\b(music|song|beat|track|audio|melody)\b", re.I),
    #  ["generate_music"]),
]


class RouteResult:
    """Result of routing a user message."""

    def __init__(self):
        self.tool_names: list[str] = []
        self.tool_schemas: list[dict] = []
        self.pattern: Optional[dict] = None
        self.cleaned_message: str = ""  # message with [pattern:name] prefix stripped
        self.source: str = "default"

    def __repr__(self):
        tools = ", ".join(self.tool_names)
        pattern = self.pattern["name"] if self.pattern else "none"
        return f"RouteResult(source={self.source}, pattern={pattern}, tools=[{tools}])"


def route(user_message: str) -> RouteResult:
    """Route a user message to the appropriate tools and pattern.

    Priority:
    1. Keyword pre-routing (regex match → specific tools)
    2. Pattern matching (keyword → pattern with scoped tools)
    3. Default (core tool set)
    """
    result = RouteResult()
    result.cleaned_message = user_message
    default_tools = get_default_tools()

    # ── Step 1: Keyword pre-routing ──
    keyword_tools = set()
    for pattern_re, tool_names in KEYWORD_ROUTES:
        if pattern_re.search(user_message):
            for t in tool_names:
                if has_tool(t):
                    keyword_tools.add(t)

    if keyword_tools:
        result.tool_names = list(set(default_tools) | keyword_tools)
        result.tool_schemas = get_tool_schemas(result.tool_names)
        result.source = "keyword"
        logger.info("Keyword route: %s → %s", user_message[:50], list(keyword_tools))

    # ── Step 2: Pattern matching ──
    matched_pattern, cleaned = match_pattern(user_message)
    result.cleaned_message = cleaned
    if matched_pattern:
        result.pattern = matched_pattern
        result.source = "pattern" if not keyword_tools else "keyword+pattern"
        logger.info("Pattern match: %s → '%s'", user_message[:50], matched_pattern["name"])

        # If pattern specifies tools, use those + always-available tools
        if matched_pattern["tools"]:
            pattern_tools = [t for t in matched_pattern["tools"] if has_tool(t)]
            result.tool_names = list(set(default_tools) | set(pattern_tools) | keyword_tools)
            result.tool_schemas = get_tool_schemas(result.tool_names)
            return result

    # ── Step 3: Default fallback ──
    if not result.tool_names:
        result.tool_names = default_tools
        result.tool_schemas = get_tool_schemas(default_tools)
        result.source = result.source or "default"

    return result
