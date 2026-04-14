"""Gizmo Code Chat — isolated AI assistant for the Code Playground."""

import json
import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from llm import stream_chat
from prompt_loader import load_prompt
from sandbox import run_code

logger = logging.getLogger("gizmo.error")
conv_log = logging.getLogger("gizmo.conversations")

CODE_PROMPT_PATH = Path("/app/config/code-prompt.txt")

router = APIRouter()

# --- Prompt ---

def _load_code_prompt() -> str:
    return load_prompt(CODE_PROMPT_PATH, "You are a programming assistant. Help the user write, debug, and improve code.")


# --- Tool definitions (run_code only) ---

CODE_CHAT_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "run_code",
            "description": "Execute code in a sandboxed container. Supported languages: python (with numpy, pandas, matplotlib, sympy, scipy, reportlab, openpyxl, python-docx, python-pptx), javascript (Node.js), bash, c, cpp, go, lua. No network access. To generate downloadable files, write them to /tmp/output/.",
            "parameters": {
                "type": "object",
                "properties": {
                    "code": {
                        "type": "string",
                        "description": "Source code to execute",
                    },
                    "language": {
                        "type": "string",
                        "description": "Programming language (default: python)",
                        "enum": ["python", "javascript", "bash", "c", "cpp", "go", "lua"],
                    },
                    "timeout": {
                        "type": "integer",
                        "description": "Execution timeout in seconds (default 10, max 30)",
                        "minimum": 1,
                        "maximum": 30,
                    },
                },
                "required": ["code"],
            },
        },
    },
]


async def _execute_code_tool(arguments: dict) -> str:
    """Execute the run_code tool and return formatted result."""
    result = await run_code(
        arguments["code"],
        arguments.get("language", "python"),
        arguments.get("timeout", 10),
    )
    parts = []
    if result["timed_out"]:
        parts.append(f"[TIMED OUT after {arguments.get('timeout', 10)}s]")
    if result["stdout"]:
        parts.append(f"stdout:\n{result['stdout']}")
    if result["stderr"]:
        parts.append(f"stderr:\n{result['stderr']}")
    if result.get("output_files"):
        file_lines = ["Generated files:"]
        for f in result["output_files"]:
            file_lines.append(f"- {f['filename']}: {f['url']}")
        parts.append("\n".join(file_lines))
    if not parts:
        parts.append(f"(no output, exit code {result['exit_code']})")
    return "\n".join(parts)


# --- WebSocket endpoint ---


@router.websocket("/ws/code-chat")
async def ws_code_chat(ws: WebSocket):
    """WebSocket for the Code Playground AI chat. Isolated from main chat and memory."""
    from origins import check_ws_origin
    if not check_ws_origin(ws):
        await ws.close(code=4003, reason="Origin not allowed")
        return
    await ws.accept()
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

            # Inject current code as context if provided
            code_context = msg.get("code", "")
            code_language = msg.get("language", "python")

            trace_id = f"code-{uuid.uuid4().hex[:8]}"
            await ws.send_json({"type": "trace_id", "trace_id": trace_id})
            conv_log.info("[%s] CODE USER: %s", trace_id, user_text[:500])

            # Build system prompt with code context
            system_prompt = _load_code_prompt()
            if code_context:
                system_prompt += f"\n\n--- User's Current Code ({code_language}) ---\n```{code_language}\n{code_context[:4000]}\n```"

            message_history.append({"role": "user", "content": user_text})
            messages = [{"role": "system", "content": system_prompt}] + message_history

            # Stream response with multi-round tool calling
            full_response = ""
            tool_calls_pending = []
            max_tool_rounds = 5
            tool_round = 0
            stream_errored = False

            async for event in stream_chat(messages, tools=CODE_CHAT_TOOLS):
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

                for tc in tool_calls_pending:
                    conv_log.info(
                        "[%s] CODE_TOOL [round %d]: %s(%s)",
                        trace_id, tool_round, tc["name"], tc["arguments"][:200],
                    )
                    try:
                        args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                        result = await _execute_code_tool(args)
                    except Exception as e:
                        result = f"Tool error: {str(e)}"
                        logger.error("[%s] Code tool failed: %s", trace_id, e, exc_info=True)

                    conv_log.info("[%s] CODE_RESULT: %s", trace_id, result[:300])
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

                tool_calls_pending = []
                full_response = ""
                async for event in stream_chat(messages, tools=CODE_CHAT_TOOLS):
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
                        logger.error("[%s] Stream error (round %d): %s", trace_id, tool_round, event["error"])
                        await ws.send_json({
                            "type": "error",
                            "error": event["error"],
                            "trace_id": trace_id,
                        })
                        tool_calls_pending = []
                        break

            if full_response and not stream_errored:
                message_history.append({"role": "assistant", "content": full_response})

            await ws.send_json({"type": "done", "trace_id": trace_id})

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error("Code chat WS error: %s", e, exc_info=True)
