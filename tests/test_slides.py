"""The slide deck - a deliverable of the challenge (5 to 7 pages).

The PDF is committed with the repository; the generator is committed too, so
the deck can be rebuilt (it needs `reportlab`, which is not a runtime
dependency of the package).
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SLIDES = ROOT / "slides"
DECK = SLIDES / "signed-orders.pdf"
GENERATOR = SLIDES / "build_slides.py"


def _page_count() -> int:
    """The number of pages of the deck, pymupdf if it is installed."""
    try:
        import pymupdf  # type: ignore
    except ImportError:
        pymupdf = None
    if pymupdf is not None:
        with pymupdf.open(DECK) as document:
            return document.page_count
    # fallback: count the page objects of the (uncompressed) PDF
    data = DECK.read_bytes()
    return len(re.findall(rb"/Type\s*/Page[^s]", data))


def test_the_deck_is_committed():
    assert DECK.exists(), "the PDF deck must be committed: it is a deliverable"
    assert DECK.stat().st_size > 5000


def test_the_deck_is_a_pdf():
    assert DECK.read_bytes()[:5] == b"%PDF-"


def test_the_deck_has_between_five_and_seven_pages():
    pages = _page_count()
    assert 5 <= pages <= 7, f"the rules ask for a 5-7 page deck, got {pages}"


def test_the_generator_is_committed():
    assert GENERATOR.exists()
    source = GENERATOR.read_text(encoding="utf-8")
    assert source.startswith("#!/usr/bin/env python3")


def test_the_generator_covers_the_required_topics():
    source = GENERATOR.read_text(encoding="utf-8")
    for topic in ("problem", "idea", "control plan", "isolation", "journal",
                  "demo", "submission"):
        assert topic in source.lower(), f"the deck must cover: {topic}"


def test_the_deck_mentions_vultr():
    """The rules ask the deck to explain the Vultr services used."""
    source = GENERATOR.read_text(encoding="utf-8")
    assert "Serverless Inference" in source
    assert "Cloud Compute" in source
    assert "signedorders serve" in source
