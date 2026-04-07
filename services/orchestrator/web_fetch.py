"""Fetch a web page and extract clean text content."""

import logging

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

MAX_CHARS = 12_000


async def fetch_page(url: str, timeout: float = 15.0) -> str:
    """Fetch a URL and return cleaned text content (up to 12k chars).

    Returns empty string on any failure.
    """
    try:
        async with httpx.AsyncClient(
            timeout=timeout,
            follow_redirects=True,
            headers={"User-Agent": "Mozilla/5.0 (compatible; Gizmo/1.0)"},
        ) as client:
            resp = await client.get(url)
            resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        for tag in soup.find_all(["script", "style", "nav", "footer", "header"]):
            tag.decompose()

        text = soup.get_text(separator="\n", strip=True)
        return text[:MAX_CHARS]

    except Exception as e:
        logger.debug("fetch_page failed for %s: %s", url, e)
        return ""
