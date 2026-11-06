"""Wrapper for free text typed by humans."""

from __future__ import annotations

import json


class UntrustedText:
    """Free text typed (or dictated) by a human in the notific.me app.

    It is **untrusted input**: never feed it to a shell, SQL, ``eval``, an HTML
    page or an LLM prompt as instructions. Read the raw string with ``.text`` on
    purpose, or use ``.quoted()`` to embed it as a JSON string literal (e.g.
    inside a prompt, clearly marked as data).

    ``str(x)`` returns the raw text.
    """

    __slots__ = ("_text",)
    _text: str

    def __init__(self, text: str) -> None:
        object.__setattr__(self, "_text", text)

    def __setattr__(self, name: str, value: object) -> None:
        raise AttributeError("UntrustedText is immutable")

    @property
    def text(self) -> str:
        """The raw text exactly as the human wrote it. Treat it as data only."""
        return self._text

    def quoted(self) -> str:
        """The text as a JSON string literal, e.g. ``"Only after 10 pm"``."""
        return json.dumps(self._text, ensure_ascii=False)

    def __str__(self) -> str:
        return self._text

    def __repr__(self) -> str:
        return f"UntrustedText({self.quoted()})"

    def __len__(self) -> int:
        return len(self._text)

    def __eq__(self, other: object) -> bool:
        if isinstance(other, UntrustedText):
            return self._text == other._text
        return NotImplemented

    def __hash__(self) -> int:
        return hash(("UntrustedText", self._text))
