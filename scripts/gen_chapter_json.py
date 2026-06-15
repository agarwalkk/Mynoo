"""
AaravTutor PDF → Gemini 2.5 Pro → Structured JSON
Requirements: google-genai requests pymupdf
  pip install google-genai requests pymupdf

Usage:
  python gen_chapter_json.py <path/to/chapter.pdf> [--youtube-key YOUR_KEY]
"""

import argparse
import hashlib
import json
import time
import sys
import os
import warnings
import urllib.parse
from pathlib import Path

# Silence PyMuPDF internal canvas telemetry alerts safely across all versions
try:
    import fitz  # PyMuPDF
    _FITZ_AVAILABLE = True
    
    # ── VERSION-AGNOSTIC TELEMETRY MUTING ──
    if hasattr(fitz, "set_logging_level"):
        fitz.set_logging_level(0)
    elif hasattr(fitz, "TOOLS") and hasattr(fitz.TOOLS, "mutes_set_on"):
        fitz.TOOLS.mutes_set_on(True)
    else:
        # Fallback for the newest release variants: route messages into deep void
        import os
        os.environ["MU_LOG_LEVEL"] = "0"
except ImportError:
    _FITZ_AVAILABLE = False

import requests
from google import genai
from google.genai import types

# Suppress native user warning categories
warnings.filterwarnings("ignore", category=UserWarning)

# ── 1. CONFIG & INTERACTIVE INITIALIZATION ─────────────────────────────────────
GEMINI_API_KEY = "AIzaSyDvydapMqIxRXN1dh_RlQvAA8YjURVW998"
DEFAULT_YT_KEY = "AIzaSyBcYTVUJKIvxaVFgfwyEPsq09AEKK9_Rjs"   
MODEL          = "gemini-2.5-pro"

parser = argparse.ArgumentParser(description="Convert a PDF chapter to AaravTutor JSON.")
parser.add_argument("pdf_path", nargs="?", default=None, help="Path to the PDF file to convert")
parser.add_argument("--youtube-key", default="", help="YouTube Data API v3 key (optional)")
parser.add_argument("--skip-images", action="store_true", help="Skip PDF image extraction")
parser.add_argument("--images-only", action="store_true", help="Extract and describe images then stop")
parser.add_argument("--restart", action="store_true", help="Ignore any saved checkpoint and restart completely")
parser.add_argument("--step", choices=["images", "media", "stream", "cleanup"], default=None,
                    help="Force restart the process starting from this specific step onwards.")

args = parser.parse_args()

# ── INTERACTIVE PARAMETER LOGIC ──
PDF_PATH = args.pdf_path
if not PDF_PATH:
    print("═" * 72)
    print("📂 AARAVTUTOR INTERACTIVE INITIALIZER")
    print("═" * 72)
    # List available PDFs in current directory to help the user select quickly
    local_pdfs = list(Path(".").glob("*.pdf"))
    if local_pdfs:
        print("\nAvailable local PDFs detected:")
        for idx, pdf in enumerate(local_pdfs, 1):
            print(f"  [{idx}] {pdf.name}")
        
        choice = input("\nSelect a file number or type target PDF file path explicitly: ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(local_pdfs):
            PDF_PATH = str(local_pdfs[int(choice) - 1])
        else:
            PDF_PATH = choice
    else:
        PDF_PATH = input("\nEnter the path to your target PDF chapter file: ").strip()

# Clean quotes if user dragged and dropped file into terminal window
if PDF_PATH:
    PDF_PATH = PDF_PATH.strip("'\"")

if not PDF_PATH or not Path(PDF_PATH).exists():
    print(f"\n❌ Error: Target PDF path configuration target '{PDF_PATH}' does not exist.")
    sys.exit(1)

# Interactive configuration for pipeline step execution rather than YouTube Key setup
if not args.step and not args.restart:
    print(f"\n🔄 Pipeline Execution Segment Choice:")
    print(f"  [1] Run sequentially (continue from latest cached step)")
    print(f"  [2] Force restart from PDF image extraction ('images')")
    print(f"  [3] Force restart from Media Search Engine ('media')")
    print(f"  [4] Force restart from Gemini Streaming Transformation ('stream')")
    print(f"  [5] Force restart from Structural Cleanup Polishing ('cleanup')")
    
    step_choice = input("\nSelect execution pipeline target [1]: ").strip() or "1"
    if step_choice == "2":
        args.step = "images"
    elif step_choice == "3":
        args.step = "media"
    elif step_choice == "4":
        args.step = "stream"
    elif step_choice == "5":
        args.step = "cleanup"

# Bypass interactive key request and leverage fallback defaults cleanly
YOUTUBE_KEY = args.youtube_key or os.environ.get("YOUTUBE_API_KEY", DEFAULT_YT_KEY)

_client = genai.Client(api_key=GEMINI_API_KEY)


def gemini_generate(model: str, contents, config):
    return _client.models.generate_content(model=model, contents=contents, config=config)

# ── 2. SYSTEM PROMPT ───────────────────────────────────────────────────────────
SYSTEM_PROMPT = """You are **AaravTutor Formatter**, a content-structuring AI for AaravTutor — a school tutoring app for classes 6–12.

Your sole job is to read raw textbook or lesson content provided by the user and convert it into a valid AaravTutor chapter JSON object. You are precise, faithful to the source, and never improvise facts.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  GOLDEN RULES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Output RAW JSON ONLY — no code fences, no prose before or after, no comments.
2. Never invent or assume facts not present in the source material.
3. Never include "_doc", "_guide", "_audio", or "_format_version" fields anywhere.
4. Every paragraph "id" must be unique across the entire chapter.
5. Every sentence "id" must be unique across the entire chapter.
6. Paragraph array order = reading order. Preserve the original flow.
7. Group 2–5 sentences per paragraph. Start a new paragraph at every topic shift.
8. VERBATIM FIDELITY — Copy every sentence from the source exactly as written. Do NOT paraphrase, summarise, shorten, merge, or skip any sentence. Every word in the source must appear in the output. Missing content is a critical error.
9. PRESERVE HEADERS — Every chapter title, section heading, and sub-section heading in the source must appear as a "heading" or "subheading" paragraph in the output. Do not fold headings into prose.
10. PRESERVE FORMATTING — If the source renders a word or phrase in bold or italic, reproduce it as **bold** or *italic* in the "text" field. Retain all such formatting from the original.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  TOP-LEVEL SHAPE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

{
  "title": "Chapter or Lesson Title",
  "paragraphs": [ ...paragraph objects... ]
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  PARAGRAPH TYPES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SILENT types (no audio generated):
  heading      → "text" (required). Chapter or section title. Displayed large + bold.
  subheading   → "text" (required). Sub-section label. Displayed smaller in teal.
  attribution  → "text" (required). Author or source credit. Right-aligned italic.
  table        → "rows" 2-D array (required), "headers" string array (optional), "caption" (optional). No audio.
  media        → "items" array (required). Tappable video/photo cards. No audio.

AUDIO types (each sentence or item → one MP3):
  prose        → "sentences" array (required). Standard narrative text.
  blockquote   → "sentences" array (required). Quoted or extracted passage. Indented italic.
  activity     → "sentences" array (required), "title" string (optional). Exercise box with blue border.
  callout      → "sentences" array (required). Fun-fact highlight box with orange border.
  note         → "sentences" array (required), "title" string (optional). Reference box in yellow. NOT read aloud even though it has sentences.
  list         → "sentences" array (required), "ordered" boolean (optional, default false). Each item = 1 MP3.
  verse        → "text" string (required, use \\n for line breaks), "meaning" string (REQUIRED). Whole stanza = 1 MP3.
  equation     → "text" string (required, use \\n for multi-line). Monospace display. Whole block = 1 MP3.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  SENTENCE OBJECT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

{
  "id":      "p03-s01",
  "text":    "Sentence text.",
  "meaning": "**term** — def"
}

  id      → REQUIRED. Unique across chapter.
  text    → REQUIRED. Use inline Markdown (see formatting section).
  meaning → REQUIRED for every sentence without exception. Must include word-level glosses for key terms AND a full sentence-level meaning on a new \n line.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  MEANING FORMAT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Single term:   "**term** — definition"
Multiple:      "**term1** — def1 | **term2** — def2"
With context:  "**term** — def\\nFull sentence explanation here."

Rules by subject:
  • English, and any english language subject   — define key terms in simple English; add full sentence meaning containing mix of simple hindi words and simple english words in hindi like flow e.g. meaning of this sentence "Taking a clue from their history lesson, they decided to visit Pāțaliputra for their first journey - they knew it was about the same location as modern-day Patna." should be "अपने  history lesson से idea लेकर , उन्होंने अपनी पहली  journey के  लिए पाटलीपुत्र जाने  का  decide  किया  - वे  जानते  थे  कि  यह  almost वही location है  जहाँ आज का पटना city है"\nMeaning of this sentence ""Kautilya had a clear vision of how a kingdom (rājya) should be established, managed and consolidated." should be "कौटिल्य का clear vision था कि कैसे एक राज्य को establish, manage और consolidate करना चाहिए". Avoid using complex (less common) hindi words, instead use simple english words.
  • Hindi     — define in simple Hindi; add English gloss and full sentence meaning similar to rule for english\n.
  • Punjabi   — word meaning in simple Hindi + English; sentence meaning as word-for-word Hindi translation after \n. Prefer English over complex Hindi words where clearer.
  • Science/Maths — include SI unit or formula in the definition; add full sentence meaning after \n.
  • verse "meaning" is ALWAYS REQUIRED — provide a full translation or explanation of the stanza.
  • NEVER omit "meaning" from any sentence. Every sentence must have a meaning field.
  • ALWAYS emit words in thier original script. Hindi words in devnagri, English words in roman and Punjabi words in gurmukhi.

Always bold the defined term: **term**.
Format: "**term1** — def1 | **term2** — def2\nFull plain-English (or plain-Hindi) meaning of the entire sentence."
The sentence-level meaning after \n is MANDATORY for every sentence regardless of subject.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  INLINE FORMATTING  (use in "text" fields only)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**word** → bold, primary colour   (key terms, labels; also use whenever source text is bold)
*word* → italic                 (titles, foreign words, emphasis; also use whenever source text is italic)
`word`      → monospace              (variable names, commands, code)
~~word~~    → strikethrough          (corrections, crossed-out text)
\\n          → hard line break        (verse and equation "text" ONLY)

Formatting fidelity rules:
  • If the PDF renders a word/phrase in bold → wrap it in **…** in the "text" field.
  • If the PDF renders a word/phrase in italic → wrap it in *…* in the "text" field.
  • Do NOT strip formatting from the source. Preserving source bold/italic is REQUIRED.

Do NOT use block Markdown: no #headings, no > blockquotes, no - bullets, no ``` fences.
The "meaning", "title", and "caption" fields are plain strings — no Markdown inside them.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  MEDIA ITEMS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Each item in a media paragraph:
{
  "mediaType": "video" | "photo",
  "url":       "https://...",
  "caption":   "Plain text label",
  "meaning":   "**term** — def"    ← optional
}

  • YouTube URLs (youtube.com / youtu.be) → mediaType "video".
  • Image file URLs (.jpg .png .gif .webp) and Wikimedia URLs → mediaType "photo".
    Wikimedia format: [https://commons.wikimedia.org/wiki/Special:FilePath/Filename.jpg](https://commons.wikimedia.org/wiki/Special:FilePath/Filename.jpg)
  • Group related media into one media paragraph per topic section.
  • Place media paragraph immediately after the prose or activity it illustrates.
  • NEVER put raw URLs inside prose, note, activity, callout, or blockquote sentences.

MEDIA URL RULES — CRITICAL:
  • You MUST supply real, working URLs from your training knowledge. Do NOT invent or guess URLs.
  • For photos: search your knowledge for the exact Wikimedia Commons filename for the subject
    (e.g. [https://commons.wikimedia.org/wiki/Special:FilePath/Mahatma_Gandhi_photograph.jpg](https://commons.wikimedia.org/wiki/Special:FilePath/Mahatma_Gandhi_photograph.jpg)).
    Use the [https://commons.wikimedia.org/wiki/Special:FilePath](https://commons.wikimedia.org/wiki/Special:FilePath) redirect format — it always resolves to the actual file.
  • For videos: find a real YouTube video ID you know exists for the topic
    (e.g. [https://www.youtube.com/watch?v=XXXXXXXXXXX](https://www.youtube.com/watch?v=XXXXXXXXXXX)). Only include if you are confident the video exists.
  • If you cannot find a real, verifiable URL for a topic, OMIT that media item rather than fabricating a URL.
  • Prefer well-known Wikimedia Commons images (maps, diagrams, portraits, photographs) that are
    directly relevant to the textbook content — these are the most reliable source of real URLs.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ID NAMING CONVENTIONS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Paragraph IDs   : p01, p02, p03 … or semantic slugs like "intro", "summary"
Sentences       : {paragraph-id}-s01, {paragraph-id}-s02 …
Activity        : act1-s01, act1-s02 … act2-s01 …
Note            : note1-s01, note2-s01 …
Callout         : call1-s01, call2-s01 …
Media           : media1, media2 …

IDs use letters, digits, and hyphens only. No spaces. No dots. No underscores.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  PRE-OUTPUT QUALITY CHECKLIST
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Before finalising output, verify:
  □ "title" present at root level
  □ All paragraph "id" values are unique
  □ All sentence "id" values are unique
  □ prose / blockquote / activity / callout / note → "sentences" array (never "text")
  □ verse → has both "text" and "meaning"
  □ equation → has "text"
  □ list → "sentences" array (not "items", not "text" at paragraph root)
  □ table → "rows" is 2-D; every row same length as "headers"
  □ heading / subheading / attribution → "text" only (no "sentences")
  □ media → "items" array; each item has mediaType, url, caption
  □ No raw URLs inside prose / note / activity / callout / blockquote sentences
  □ No "_doc", "_guide", "_audio", "_format_version" keys anywhere
  □ Raw JSON only — no code fences, no surrounding text
  □ Meaning terms wrapped in **bold**; text/items use Markdown inline where helpful

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  PDF BOOK IMAGES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

When the user lists extracted PDF images (filename + page number), you MUST include them:

- Match each image to the topic it illustrates using its page number and the surrounding text.
- Create a media paragraph of type "photo" placed immediately AFTER the prose paragraph
  that corresponds to that page. Use the filename as the URL with the pdf-image:// prefix:
    {"mediaType": "photo", "url": "pdf-image://img-p03-00.jpg", "caption": "Diagram of the water cycle"}
- If multiple images appear on the same page, group them in one media paragraph with multiple items.
- PDF images are from the textbook itself and take PRIORITY over Wikimedia/NASA external URLs.
- Write the caption based solely on what the surrounding text says about the image.
- Do NOT use pdf-image:// URLs in any field other than a media item’s "url".
"""

# ── 3. JSON SCHEMAS ────────────────────────────────────────────────────────────
AARAVTUTOR_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "paragraphs": {
            "type": "array",
            "items": {
                "anyOf": [
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["prose", "blockquote", "activity", "callout", "note"]},
                            "title": {"type": "string"},
                            "sentences": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "id": {"type": "string"},
                                        "text": {"type": "string"},
                                        "meaning": {"type": "string"}
                                    },
                                    "required": ["id", "text", "meaning"]
                                }
                            }
                        },
                        "required": ["id", "type", "sentences"]
                    },
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["heading", "subheading", "attribution", "equation"]},
                            "text": {"type": "string"}
                        },
                        "required": ["id", "type", "text"]
                    },
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["list"]},
                            "ordered": {"type": "boolean"},
                            "sentences": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "id": {"type": "string"},
                                        "text": {"type": "string"},
                                        "meaning": {"type": "string"}
                                    },
                                    "required": ["id", "text", "meaning"]
                                }
                            }
                        },
                        "required": ["id", "type", "sentences"]
                    },
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["verse"]},
                            "text": {"type": "string"},
                            "meaning": {"type": "string"}
                        },
                        "required": ["id", "type", "text", "meaning"]
                    },
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["table"]},
                            "caption": {"type": "string"},
                            "headers": {"type": "array", "items": {"type": "string"}},
                            "rows": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}}
                        },
                        "required": ["id", "type", "rows"]
                    },
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "type": {"type": "string", "enum": ["media"]},
                            "caption": {"type": "string"},
                            "items": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "mediaType": {"type": "string", "enum": ["photo", "video"]},
                                        "url": {"type": "string"},
                                        "caption": {"type": "string"},
                                        "meaning": {"type": "string"}
                                    },
                                    "required": ["mediaType", "url"]
                                }
                            }
                        },
                        "required": ["id", "type", "items"]
                    }
                ]
            }
        }
    },
    "required": ["title", "paragraphs"]
}

TOPICS_SCHEMA = {
    "type": "array",
    "items": {
        "type": "object",
        "properties": {
            "query": {"type": "string"},
            "caption": {"type": "string"},
            "type": {"type": "string", "enum": ["photo", "video"]}
        },
        "required": ["query", "caption", "type"]
    }
}

# ── 4. PDF IMAGE EXTRACTION ────────────────────────────────────────────────────
MIN_IMAGE_DIM = 150
MIN_CHARS_PER_PAGE = 200

def _is_text_based_pdf(doc) -> tuple[bool, float]:
    total_chars = 0
    for page in doc:
        total_chars += len(page.get_text("text"))
    avg = total_chars / max(len(doc), 1)
    return avg >= MIN_CHARS_PER_PAGE, avg


def extract_pdf_images(pdf_path: str, pdf_bytes: bytes) -> list[dict]:
    if not _FITZ_AVAILABLE:
        print("      PyMuPDF is not installed. Skipping image extraction.")
        return []

    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    is_text, avg_chars = _is_text_based_pdf(doc)
    if not is_text:
        doc.close()
        print(f"      Skipped — PDF appears to be image-based.")
        return []

    out_dir = Path(pdf_path).with_suffix("") / "images"
    out_dir.mkdir(parents=True, exist_ok=True)

    seen_hashes: set[str] = set()
    results: list[dict] = []

    for page_num in range(len(doc)):
        page = doc[page_num]
        page_idx = 0
        for img_ref in page.get_images(full=True):
            xref = img_ref[0]
            try:
                pix = fitz.Pixmap(doc, xref)
                if pix.colorspace != fitz.csRGB or pix.alpha:
                    pix = fitz.Pixmap(fitz.csRGB, pix)
            except Exception:
                continue
            w, h = pix.width, pix.height
            if w < MIN_IMAGE_DIM or h < MIN_IMAGE_DIM:
                continue
            img_bytes_raw = pix.tobytes("png")
            digest = hashlib.md5(img_bytes_raw).hexdigest()
            if digest in seen_hashes:
                continue
            seen_hashes.add(digest)
            filename = f"img-p{page_num + 1:02d}-{page_idx:02d}.png"
            (out_dir / filename).write_bytes(img_bytes_raw)
            results.append({"filename": filename, "page": page_num + 1, "width": w, "height": h})
            page_idx += 1
    doc.close()
    return results


def describe_pdf_images(pdf_path: str, pdf_images: list[dict]) -> list[dict]:
    img_dir = Path(pdf_path).with_suffix("") / "images"
    
    print(f"      Describing image(s) with Gemini ...")
    for img in pdf_images:
        if img.get("description"):
            continue  
            
        img_path = img_dir / img["filename"]
        if not img_path.exists():
            img["description"] = "(file not found)"
            continue
        img_bytes = img_path.read_bytes()
        mime = "image/png"
        try:
            response = gemini_generate(
                model="gemini-2.5-flash",
                contents=[
                    types.Part.from_bytes(data=img_bytes, mime_type=mime),
                    "Describe what this image shows in one concise sentence (max 20 words) for a school textbook. If decorative, reply with: DECORATIVE"
                ],
                config=types.GenerateContentConfig(max_output_tokens=100, temperature=0.1)
            )
            desc = (response.text or "").strip()
        except Exception as e:
            desc = f"(description failed: {e})"
        img["description"] = desc
        print(f"        {img['filename']}  →  {desc[:50]}")

    return pdf_images

# ── 5. MEDIA SECTOR SEARCH ENGINE ──────────────────────────────────────────────
def extract_topics(pdf_bytes: bytes) -> list[dict]:
    pdf_part = types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
    response = gemini_generate(
        model=MODEL,
        contents=[
            pdf_part,
            "List key educational topics, places, or experiments in this text requiring visual/video media tools. Output a strict JSON array of objects: "
            '[{"query": "search term", "caption": "short label", "type": "photo"|"video"}]. Output raw JSON array only.'
        ],
        config=types.GenerateContentConfig(
            max_output_tokens=4000, 
            temperature=0.1,
            # Enforce JSON output syntax directly at the API level
            response_mime_type="application/json",
            response_schema=TOPICS_SCHEMA
        )
    )
    raw = (response.text or "").strip()
    if raw.startswith("```"):
        raw = raw.split("```")[1]
        if raw.startswith("json"):
            raw = raw[4:]
    return json.loads(raw)


def search_wikimedia(query: str) -> str | None:
    try:
        params = {"action": "query", "generator": "search", "gsrnamespace": "6", "gsrsearch": query, "gsrlimit": "3", "prop": "imageinfo", "iiprop": "url|thumburl|mediatype", "iiurlwidth": "800", "format": "json"}
        r = requests.get("[https://commons.wikimedia.org/w/api.php](https://commons.wikimedia.org/w/api.php)", params=params, timeout=10, headers={"User-Agent": "AaravTutor/1.0"})
        pages = r.json().get("query", {}).get("pages", {})
        for page in pages.values():
            info = page.get("imageinfo", [{}])[0]
            if info.get("mediatype") in ("BITMAP", "DRAWING"):
                return info.get("thumburl") or info.get("url")
    except Exception: pass
    return None

def search_youtube(query: str, api_key: str) -> str | None:
    if not api_key: return None
    try:
        params = {"part": "snippet", "q": query, "type": "video", "maxResults": "1", "safeSearch": "strict", "key": api_key}
        r = requests.get("[https://www.googleapis.com/youtube/v3/search](https://www.googleapis.com/youtube/v3/search)", params=params, timeout=10)
        items = r.json().get("items", [])
        if items: return f"[https://www.youtube.com/watch?v=](https://www.youtube.com/watch?v=){items[0]['id']['videoId']}"
    except Exception: pass
    return None


def find_media_links(pdf_bytes: bytes, cached_topics=None) -> str:
    topics = cached_topics if cached_topics else extract_topics(pdf_bytes)
    media_lines = []
    for t in topics:
        query, caption, kind = t.get("query", ""), t.get("caption", ""), t.get("type", "photo")
        if kind == "video":
            url = search_youtube(query + " educational", YOUTUBE_KEY)
            if url: media_lines.append(f"VIDEO|{url}|{caption}")
        else:
            url = search_wikimedia(query)
            if url: media_lines.append(f"PHOTO|{url}|{caption}")
    return "\n".join(media_lines), topics

# ── 6. PERSISTENT CHECKPOINT ENGINE ──────────────────────────────────────────
def _ckpt_path(pdf_path: str) -> Path:
    return Path(pdf_path).with_suffix(".ckpt.json")

def _load_checkpoint(pdf_path: str, pdf_md5: str) -> dict:
    p = _ckpt_path(pdf_path)
    if not p.exists(): return {}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        if data.get("pdf_md5") == pdf_md5: return data
    except Exception: pass
    return {}

def _save_checkpoint(pdf_path: str, pdf_md5: str, ckpt: dict) -> None:
    payload = {"pdf_md5": pdf_md5, **ckpt}
    _ckpt_path(pdf_path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

# ── 7. MAIN RENDERING RUNNER ──────────────────────────────────────────────────
print(f"[1/8] Analyzing File Target Matrix...")
pdf_bytes = Path(PDF_PATH).read_bytes()
pdf_md5 = hashlib.md5(pdf_bytes).hexdigest()

if args.restart and _ckpt_path(PDF_PATH).exists():
    _ckpt_path(PDF_PATH).unlink()

ckpt = _load_checkpoint(PDF_PATH, pdf_md5)
ckpt.setdefault("completed", [])

if args.step:
    step_levels = {"images": 0, "media": 1, "stream": 2, "cleanup": 3}
    target_level = step_levels.get(args.step, 0)
    if target_level <= 0: ckpt.pop("images", None)
    if target_level <= 1: ckpt.pop("media_hints", None); ckpt.pop("topics", None)
    if target_level <= 2: ckpt.pop("raw_json_stream", None); ckpt.pop("chapter_json", None)

# Step 2: Image Processing Framework
if "images" in ckpt:
    pdf_images = ckpt["images"]
    print(f"[2/8] Loaded extracted images description framework from cache.")
elif args.skip_images or not _FITZ_AVAILABLE:
    pdf_images = []
    print("[2/8] Extraction skipped via configuration instructions.")
else:
    print("[2/8] Extracting images from PDF...")
    pdf_images = extract_pdf_images(PDF_PATH, pdf_bytes)
    if pdf_images:
        pdf_images = describe_pdf_images(PDF_PATH, pdf_images)
    ckpt["images"] = pdf_images
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

# Step 3: Media URL Query Aggregator
if "media_hints" in ckpt:
    media_hints = ckpt["media_hints"]
    print("[3/8] Media elements and queries loaded safely from local checkpoint cache.")
else:
    print("[3/8] Running Media Search Engine (YouTube & Wikimedia)...")
    cached_topics = ckpt.get("topics", None)
    media_hints, topics = find_media_links(pdf_bytes, cached_topics=cached_topics)
    ckpt["topics"] = topics
    ckpt["media_hints"] = media_hints
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)
    print(f"[4/8] Media search engine completed successfully.")

# Step 5: High-Fidelity Streaming Engine Transformation
if "chapter_json" in ckpt:
    chapter_json = ckpt["chapter_json"]
    print("[5/8] Finalized JSON payload loaded directly from checkpoint structure.")
else:
    if "raw_json_stream" in ckpt:
        print("[6/8] Direct Processing: Found raw stream payload inside cache. Re-parsing...")
        raw_stream_text = ckpt["raw_json_stream"]
    else:
        print("[6/8] Direct Processing: Streaming Transformation Phase...")
        pdf_part = types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
        
        usable_images = [img for img in pdf_images if img.get("description") and img["description"] != "DECORATIVE"]
        image_section = "\n".join([f"pdf-image://{i['filename']} | {i['description']}" for i in usable_images])

        collected_chunks = []
        output_chars = 0
        t_start = time.time()  

        for chunk in _client.models.generate_content_stream(
            model=MODEL,
            contents=[
                pdf_part,
                f"Convert the above PDF textbook content into AaravTutor JSON.\nMedia Maps:\n{media_hints}\nPDF Images:\n{image_section}"
            ],
            config=types.GenerateContentConfig(
                system_instruction=SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_schema=AARAVTUTOR_SCHEMA,
                max_output_tokens=100000,
                temperature=0.1
            )
        ):
            if chunk.text:
                collected_chunks.append(chunk.text)
                output_chars += len(chunk.text)
                print(f"\r      Received: {output_chars:,} chars", end="", flush=True)

        elapsed_total = time.time() - t_start
        print()  
        print("─" * 72)
        print(f"      Streaming complete in {elapsed_total:.1f}s — {output_chars:,} chars received")
        
        raw_stream_text = "".join(collected_chunks)
        ckpt["raw_json_stream"] = raw_stream_text
        _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

    print("[7/8] Transforming payload formatting matrices...")
    chapter_json = json.loads(raw_stream_text)
    
    # ── STRUCTURAL POLISHING & ENUM FALLBACK CLEANERS ──
    for p in chapter_json.get("paragraphs", []):
        ptype = p.get("type", "")
        if ptype in ('heading', 'subheading', 'attribution', 'verse', 'equation', 'table', 'media'):
            p.pop("sentences", None)
        if ptype in ('prose', 'blockquote', 'activity', 'callout', 'note', 'list'):
            p.pop("text", None)
            
        if ptype == "media" and "items" in p:
            for item in p["items"]:
                if item.get("mediaType") == "image":
                    item["mediaType"] = "photo"
                    
        if ptype == "list":
            if "items" in p and "sentences" not in p:
                p["sentences"] = []
                for idx, item in enumerate(p["items"]):
                    if isinstance(item, dict):
                        text_val = item.get("text") or item.get("meaning") or ""
                        meaning_val = item.get("meaning") or ""
                    else:
                        text_val = str(item)
                        meaning_val = ""
                    p["sentences"].append({
                        "id": f"{p['id']}-s{idx+1:02d}",
                        "text": text_val,
                        "meaning": meaning_val
                    })
                p.pop("items", None)

    ckpt["chapter_json"] = chapter_json
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

# Final Phase: Write output matrix configurations
print("[8/8] Saving output ...")
output_path = PDF_PATH.replace(".pdf", ".json")
Path(output_path).write_text(json.dumps(chapter_json, ensure_ascii=False, indent=2), encoding="utf-8")

if _ckpt_path(PDF_PATH).exists():
    _ckpt_path(PDF_PATH).unlink()

print(f"\n✅ Processing Phase Completed! Verified output configured structural schemas saved to: {output_path}")