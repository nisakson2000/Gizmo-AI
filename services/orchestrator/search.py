"""SearXNG web search proxy."""

import os
import httpx
from typing import Optional

SEARXNG_HOST = os.getenv("SEARXNG_HOST", "gizmo-searxng")
SEARXNG_PORT = os.getenv("SEARXNG_PORT", "8080")
SEARXNG_URL = f"http://{SEARXNG_HOST}:{SEARXNG_PORT}/search"


async def web_search(query: str, num_results: int = 5) -> list[dict]:
    """Query SearXNG and return top results."""
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(SEARXNG_URL, params={
                "q": query,
                "format": "json",
                "categories": "general",
            })
            resp.raise_for_status()
            data = resp.json()

        results = []
        for item in data.get("results", [])[:num_results]:
            results.append({
                "title": item.get("title", ""),
                "url": item.get("url", ""),
                "snippet": item.get("content", ""),
            })
        return results

    except httpx.ConnectError:
        return [{"error": "SearXNG is not available. Search service may be down."}]
    except Exception as e:
        return [{"error": f"Search failed: {str(e)}"}]


def format_search_results(results: list[dict]) -> str:
    """Format search results for LLM context injection."""
    if not results:
        return "No search results found."
    if "error" in results[0]:
        return results[0]["error"]

    lines = ["Web search results:\n"]
    for i, r in enumerate(results, 1):
        lines.append(f"{i}. **{r['title']}**")
        lines.append(f"   URL: {r['url']}")
        if r.get("snippet"):
            lines.append(f"   {r['snippet']}")
        lines.append("")
    return "\n".join(lines)
