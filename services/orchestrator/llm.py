"""Shared LLM streaming logic for the Gizmo-AI orchestrator."""

import asyncio
import json
import logging
import os

import httpx

logger = logging.getLogger("gizmo.error")

LLAMA_HOST = os.getenv("LLAMA_HOST", "gizmo-llama")
LLAMA_PORT = os.getenv("LLAMA_PORT", "8080")
LLAMA_URL = f"http://{LLAMA_HOST}:{LLAMA_PORT}"

MAX_RESPONSE_TOKENS = 8192


async def stream_chat(
    messages: list[dict],
    tools: list | None = None,
    thinking_enabled: bool = False,
    max_tokens: int = 8192,
    temperature: float = 0.7,
    top_p: float = 0.9,
):
    """Stream chat completion from llama.cpp, yielding parsed events.

    Uses llama.cpp's native enable_thinking API which separates thinking
    into reasoning_content field in the streaming delta.

    Args:
        messages: Chat messages in OpenAI format.
        tools: Optional list of tool definitions (OpenAI function-calling format).
               None means no tools.
        thinking_enabled: Whether to enable the thinking/reasoning mode.
        max_tokens: Maximum response tokens (default 8192).
        temperature: Sampling temperature (default 0.7, use 0.2 for recitation).
        top_p: Nucleus sampling threshold (default 0.9).
    """
    payload = {
        "model": "gizmo",
        "messages": messages,
        "stream": True,
        "max_tokens": max_tokens,
        "temperature": temperature,
        "top_p": top_p,
        "chat_template_kwargs": {"enable_thinking": thinking_enabled},
    }

    if tools:
        payload["tools"] = tools

    async with httpx.AsyncClient(timeout=300.0) as client:
        async with client.stream(
            "POST",
            f"{LLAMA_URL}/v1/chat/completions",
            json=payload,
        ) as resp:
            if resp.status_code != 200:
                body = await resp.aread()
                yield {
                    "type": "error",
                    "error": f"llama.cpp error {resp.status_code}: {body.decode()}",
                }
                return

            tool_calls_accum = {}
            line_iter = resp.aiter_lines().__aiter__()

            while True:
                try:
                    async with asyncio.timeout(60):
                        line = await line_iter.__anext__()
                except StopAsyncIteration:
                    break
                except TimeoutError:
                    yield {
                        "type": "error",
                        "error": "Model response timed out (no activity for 60s). Try again.",
                    }
                    return

                if not line.startswith("data: "):
                    continue
                data_str = line[6:]
                if data_str.strip() == "[DONE]":
                    break

                try:
                    data = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                choice = data.get("choices", [{}])[0]
                delta = choice.get("delta", {})
                finish = choice.get("finish_reason")

                # Handle tool calls from structured API response
                if "tool_calls" in delta:
                    for tc in delta["tool_calls"]:
                        idx = tc.get("index", 0)
                        if idx not in tool_calls_accum:
                            tool_calls_accum[idx] = {
                                "id": tc.get("id", f"call_{idx}"),
                                "name": "",
                                "arguments": "",
                            }
                        if "function" in tc:
                            if "name" in tc["function"]:
                                tool_calls_accum[idx]["name"] = tc["function"]["name"]
                            if "arguments" in tc["function"]:
                                tool_calls_accum[idx]["arguments"] += tc["function"]["arguments"]
                    continue

                # Handle reasoning_content (thinking tokens)
                reasoning = delta.get("reasoning_content")
                if reasoning:
                    yield {"type": "thinking", "content": reasoning}

                # Handle content (response tokens)
                content = delta.get("content")
                if content:
                    yield {"type": "token", "content": content}

                # Handle finish with tool calls
                if finish == "tool_calls" and tool_calls_accum:
                    for idx in sorted(tool_calls_accum.keys()):
                        tc = tool_calls_accum[idx]
                        yield {
                            "type": "tool_call",
                            "id": tc["id"],
                            "name": tc["name"],
                            "arguments": tc["arguments"],
                        }
