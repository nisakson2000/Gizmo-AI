"""Tool definitions and dispatch for LLM function calling."""

import json
from typing import Any

from memory import write_memory, read_memory, list_memories
from search import web_search, format_search_results

# OpenAI function-calling format tool definitions
TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Search the web for current information. Use when the user asks about recent events, news, or anything that may not be in your training data.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search query",
                    }
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_memory",
            "description": "Read a previously saved memory file. Use when the user asks about something you may have remembered.",
            "parameters": {
                "type": "object",
                "properties": {
                    "filename": {
                        "type": "string",
                        "description": "The memory filename to read",
                    },
                    "subdir": {
                        "type": "string",
                        "enum": ["facts", "conversations", "notes"],
                        "description": "Memory subdirectory (default: facts)",
                    },
                },
                "required": ["filename"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_memory",
            "description": "Save information to persistent memory. Use when the user asks you to remember something.",
            "parameters": {
                "type": "object",
                "properties": {
                    "filename": {
                        "type": "string",
                        "description": "Filename for the memory (e.g. 'user_name.txt')",
                    },
                    "content": {
                        "type": "string",
                        "description": "The content to save",
                    },
                    "subdir": {
                        "type": "string",
                        "enum": ["facts", "conversations", "notes"],
                        "description": "Memory subdirectory (default: facts)",
                    },
                },
                "required": ["filename", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_memories",
            "description": "List all saved memory files. Use when the user asks what you remember.",
            "parameters": {
                "type": "object",
                "properties": {
                    "subdir": {
                        "type": "string",
                        "enum": ["facts", "conversations", "notes"],
                        "description": "Filter to specific subdirectory (optional)",
                    },
                },
            },
        },
    },
]


async def execute_tool(name: str, arguments: dict[str, Any]) -> str:
    """Execute a tool by name and return the result as a string."""
    if name == "web_search":
        results = await web_search(arguments["query"])
        return format_search_results(results)

    elif name == "read_memory":
        return read_memory(
            arguments["filename"],
            arguments.get("subdir", "facts"),
        )

    elif name == "write_memory":
        return write_memory(
            arguments["filename"],
            arguments["content"],
            arguments.get("subdir", "facts"),
        )

    elif name == "list_memories":
        memories = list_memories(arguments.get("subdir"))
        if not memories:
            return "No memories saved yet."
        lines = ["Saved memories:\n"]
        for m in memories:
            lines.append(f"- {m['subdir']}/{m['filename']} ({m['size']} bytes)")
        return "\n".join(lines)

    else:
        return f"Unknown tool: {name}"


def parse_tool_calls(response_text: str) -> list[dict] | None:
    """Parse tool calls from model response if present.

    llama.cpp with function calling outputs structured JSON. This parser
    handles both the native tool_calls format from the API and manual
    JSON extraction from the response text as a fallback.
    """
    # This is used as a fallback if the API doesn't return structured tool_calls
    try:
        # Look for JSON block that looks like a function call
        if '{"name":' in response_text or '{"function":' in response_text:
            # Try to find and parse JSON
            start = response_text.find("{")
            end = response_text.rfind("}") + 1
            if start >= 0 and end > start:
                data = json.loads(response_text[start:end])
                if "name" in data and "arguments" in data:
                    return [data]
    except (json.JSONDecodeError, KeyError):
        pass
    return None
