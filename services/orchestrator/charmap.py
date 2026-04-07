"""Character analysis injection for spelling and counting tasks."""

import re
from collections import Counter

_CHARMAP_PATTERNS = [
    # "how many X in Y" / "how many X's in Y" / "how many X are in Y"
    # Handles: "in strawberry", "in 'coffee'", "in the word 'coffee'"
    re.compile(r"how\s+many\s+(?P<letter>[a-zA-Z])(?:'?s)?\s+(?:are\s+)?in\s+(?:the\s+word\s+)?[\"']?(?P<word>\w+)[\"']?\s*\??$", re.I),
    # "count the letters in X" / "count the characters in X"
    re.compile(r"count\s+(?:the\s+)?(?:letters?|characters?)\s+in\s+(?:the\s+word\s+)?[\"']?(?P<word>\w+)[\"']?\s*\??$", re.I),
    # "spell X" / "spell out X"
    re.compile(r"spell\s+(?:out\s+)?[\"']?(?P<word>\w+)[\"']?\s*\??$", re.I),
    # "what letters are in X"
    re.compile(r"what\s+letters?\s+(?:are\s+)?in\s+(?:the\s+word\s+)?[\"']?(?P<word>\w+)[\"']?\s*\??$", re.I),
]


def is_charmap_request(message: str) -> tuple[bool, str]:
    """Detect character-level questions. Returns (True, word) or (False, "")."""
    msg = message.strip()
    for pattern in _CHARMAP_PATTERNS:
        m = pattern.search(msg)
        if m:
            word = m.group("word")
            if word and len(word) >= 2:
                return True, word
    return False, ""


def build_charmap(word: str) -> str:
    """Build a character analysis block for injection into the system prompt."""
    lower = word.lower()
    positions = " ".join(f"{ch}({i+1})" for i, ch in enumerate(lower))
    counts = Counter(lower)
    count_str = ", ".join(f"{ch}={n}" for ch, n in sorted(counts.items()))

    return f"""<character-analysis>
Character map for "{word}":
Position: {positions}
Total characters: {len(lower)}
Letter counts: {count_str}
Use this data to answer the user's question accurately.
</character-analysis>"""
