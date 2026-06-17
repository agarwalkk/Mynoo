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

# ── CONFIG KEY PARSER ──
def _get_local_property(key: str, default: str = "") -> str:
    # Check possible paths for local.properties
    paths_to_check = [
        Path(".") / "local.properties",
        Path(__file__).resolve().parent.parent / "local.properties",
        Path(__file__).resolve().parent / "local.properties"
    ]
    for p in paths_to_check:
        if p.exists():
            try:
                for line in p.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = [x.strip() for x in line.split("=", 1)]
                        if k == key:
                            # Unescape java-style property values
                            v = v.strip("'\"")
                            v = v.replace("\\:", ":").replace("\\\\", "\\")
                            return v
            except Exception:
                pass
    return default

# ── CONFIG ──
GEMINI_API_KEY = _get_local_property("GEMINI_API_KEY", "")
DEFAULT_YT_KEY = _get_local_property("DEFAULT_YT_KEY", "")   
MODEL          = "gemini-2.5-pro"

_client = genai.Client(api_key=GEMINI_API_KEY)

def gemini_generate(*args, **kwargs):
    return _client.models.generate_content(*args, **kwargs)

# ── SYSTEM PROMPT ──
SYSTEM_PROMPT = """You are **Mynoo Formatter**, a content-structuring AI for Mynoo — a school tutoring app for classes 6–12.

Your sole job is to read raw textbook or lesson content provided by the user and convert it into a valid Mynoo chapter JSON object. You are precise, faithful to the source, and never improvise facts.

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
  MEANING FORMAT & RULES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

TARGET READER: A Hindi-speaking school student (classes 6–12) whose mother tongue
is Hindi but who understands simple everyday English better than complex or
Sanskritized Hindi. Always prefer a simple English word over a complex Hindi word.

Single term:   "**term** — definition"
Multiple:      "**term1** — def1 | **term2** — def2"
With context:  "**term** — def\nFull sentence explanation here."

Rules by Subject:

1. English, and any English-medium subject:
   - Word definitions: Define key terms in simple English.
   - Sentence meaning (after \n): MUST be in Hinglish — a natural mix of simple
     Hindi words (Devanagari) and simple English words (Roman script), flowing
     like natural spoken Hindi.
   - Script rule: Hindi words in Devanagari (e.g. अपने, से, लेकिन), English words
     in Roman (e.g. history, idea, government).
   - VOCABULARY PREFERENCE — strictly follow this priority order:
       1st choice → simple, familiar English word (Roman script)
                    e.g. "equal" not "समान", "freedom" not "स्वतंत्रता",
                         "justice" not "न्याय", "adopted" not "अपनाया गया"
       2nd choice → simple everyday Hindi word a 6th-grader would use at home
                    e.g. "घर", "काम", "बात", "लोग", "देश", "पैसा"
       NEVER use → formal, Sanskritized, or literary Hindi words such as:
                    समानता, न्यायपालिका, विधायिका, कार्यपालिका, प्रावधान,
                    संप्रभुता, अखंडता, पंथनिरपेक्ष, एतद्द्वारा, मताधिकार,
                    अधिनियमित, अंगीकृत, प्रस्तावना, आत्मार्पित, नैतिक,
                    सहिष्णुता, बंधुत्व, गरिमा, निदेशक, उद्देशिका, etc.
   - When a complex Hindi word is unavoidable, always follow it immediately with
     the English equivalent in Roman script in brackets:
       CORRECT:   "यह देश को एक साथ रखता है — unity (एकता)"
       INCORRECT: "यह राष्ट्र की अखंडता बनाए रखता है"
   - Format for mixed words: always English (हिंदी), never हिंदी / English:
       CORRECT:   "Parliament (कानून बनाने वाली जगह)"
       INCORRECT: "विधायिका / Parliament"
   - Examples:
     * Source: "Taking a clue from their history lesson, they decided to visit
       Pāțaliputra for their first journey."
       Meaning: "अपने history lesson से idea लेकर, उन्होंने अपनी पहली journey
       के लिए Pāțaliputra जाने का decide किया"
     * Source: "The Constitution was adopted on 26 November 1949."
       Meaning: "Constitution को 26 November 1949 को adopted (accept) किया गया"
     * Source: "Democratic institutions require willingness to respect the
       viewpoint of others."
       Meaning: "Democratic institutions के सही काम के लिए, दूसरों की बात को
       respect करने की willingness होनी चाहिए"

2. Hindi subject:
   - Word definitions: Define key terms in simple Hindi (Devanagari).
   - Sentence meaning (after \n): Mix of simple Hindi (Devanagari) and simple
     English (Roman), same Hinglish flow as above.
   - Same vocabulary preference rule applies: prefer simple English over complex
     Sanskritized Hindi.

3. Punjabi subject:
   - Word definitions: Define in simple Hindi (Devanagari) + English (Roman).
   - Sentence meaning (after \n): Simple Hindi (Devanagari) with English (Roman)
     words wherever a complex Hindi word would otherwise be needed.

4. Science / Maths subjects:
   - Word definitions: Include SI unit or formula in the definition.
   - Sentence meaning (after \n): Hinglish — natural mix of Hindi and English,
     preferring English for technical terms.

General Rules:
  • The sentence-level meaning after \n is MANDATORY for every sentence.
  • NEVER omit "meaning" from any sentence.
  • ALWAYS write words in their original script: Hindi in Devanagari, English in
    Roman, Punjabi in Gurmukhi. NEVER write English words in Devanagari
    (e.g. do NOT write "पार्लियामेंट" — write "Parliament").
  • Always bold the defined term: **term**.
  • Format: "**term1** — def1 | **term2** — def2\nFull Hinglish meaning."

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
    Wikimedia format: https://commons.wikimedia.org/wiki/Special:FilePath/Filename.jpg
  • Group related media into one media paragraph per topic section.
  • Place media paragraph immediately after the prose or activity it illustrates.
  • NEVER put raw URLs inside prose, note, activity, callout, or blockquote sentences.

MEDIA URL RULES — CRITICAL:
  • You MUST supply real, working URLs from your training knowledge. Do NOT invent or guess URLs.
  • For photos: search your knowledge for the exact Wikimedia Commons filename for the subject
    (e.g. https://commons.wikimedia.org/wiki/Special:FilePath/Mahatma_Gandhi_photograph.jpg).
    Use the https://commons.wikimedia.org/wiki/Special:FilePath redirect format — it always resolves to the actual file.
  • For videos: find a real YouTube video ID you know exists for the topic
    (e.g. https://www.youtube.com/watch?v=XXXXXXXXXXX). Only include if you are confident the video exists.
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

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  MEDIA MAPS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

When the user lists media map entries in the prompt (format: VIDEO|url|caption or PHOTO|url|caption), you MUST include them:

- Create a media paragraph of type "video" (for VIDEO) or "photo" (for PHOTO) placed immediately after the prose or activity paragraph that discusses the topic.
- Use the exact URL and caption provided in the Media Maps. Do NOT modify the URL.
- If a media map entry matches the content, prioritize using it to provide real, working video/photo links.
"""

# ── 1.3 ARGUMENT PARSER AND INITIALIZATION ──
parser = argparse.ArgumentParser(description="Convert a PDF chapter to AaravTutor JSON.")
parser.add_argument("pdf_path", nargs="?", default=None, help="Path to the PDF file to convert")
parser.add_argument("--youtube-key", default="", help="YouTube Data API v3 key (optional)")
parser.add_argument("--skip-images", action="store_true", help="Skip PDF image extraction")
parser.add_argument("--images-only", action="store_true", help="Extract and describe images then stop")
parser.add_argument("--restart", action="store_true", help="Ignore any saved checkpoint and restart completely")
parser.add_argument("--resume", action="store_true", help="Resume from the latest checkpoint if it exists, without prompting")
parser.add_argument("--text-only", action="store_true", help="Extract raw text from PDF and save it to a txt file, then exit")
parser.add_argument("--step", choices=["images", "topics", "media", "stream", "meaning", "cleanup"], default=None,
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

# Check cache / checkpoint state
has_checkpoint = Path(PDF_PATH).with_suffix(".ckpt.json").exists()

# Interactive configuration for pipeline step execution rather than YouTube Key setup
if not args.text_only and not args.step and not args.restart and not args.resume:
    print(f"\n⚡ Select Execution Mode:")
    print(f"  [1] Generate Full Chapter JSON (continue from cached step or start fresh)")
    print(f"  [2] Extract Raw PDF Text & Generate External LLM Prompt (without LLM)")
    if has_checkpoint:
        print(f"  [3] Recalculate Sentence Meanings only (from cached LLM output)")
        print(f"  [4] Force Restart entire process from scratch")
    else:
        print(f"  [3] Force Restart entire process from scratch")
        
    mode_choice = input(f"\nSelect mode [1]: ").strip() or "1"
    
    if mode_choice == "2":
        args.text_only = True
    elif mode_choice == "3" and has_checkpoint:
        args.step = "meaning"
    elif (mode_choice == "4" and has_checkpoint) or (mode_choice == "3" and not has_checkpoint):
        args.restart = True
    elif mode_choice == "1" and has_checkpoint:
        # If they want to continue from checkpoint, prompt which cached step to force-restart or just run sequentially
        print(f"\n🔄 Checkpoint detected. Pipeline Segment Restart Choice:")
        print(f"  [1] Resume sequentially (continue from latest cached step)")
        print(f"  [2] Force restart from PDF image extraction ('images')")
        print(f"  [3] Force restart from Media Topic extraction ('topics')")
        print(f"  [4] Force restart from Media Search Engine ('media')")
        print(f"  [5] Force restart from Gemini Streaming Transformation ('stream')")
        print(f"  [6] Force restart from Structural Cleanup Polishing ('cleanup')")
        
        step_choice = input("\nSelect pipeline target [1]: ").strip() or "1"
        if step_choice == "2":
            args.step = "images"
        elif step_choice == "3":
            args.step = "topics"
        elif step_choice == "4":
            args.step = "media"
        elif step_choice == "5":
            args.step = "stream"
        elif step_choice == "6":
            args.step = "cleanup"

# Bypass interactive key request and leverage fallback defaults cleanly
YOUTUBE_KEY = args.youtube_key or os.environ.get("YOUTUBE_API_KEY", DEFAULT_YT_KEY)

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
        desc = img.get("description")
        if desc and not desc.startswith("(description failed:") and desc != "(file not found)":
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

# ── 1.1 NON-LLM TEXT & IMAGE EXTRACTION HANDLER ──
if args.text_only:
    print("📂 Extracting raw text from PDF (without LLM)...")
    if not _FITZ_AVAILABLE:
        print("❌ Error: PyMuPDF (fitz) is not installed. Cannot extract text.")
        sys.exit(1)
    
    # 1. Extract raw text
    doc = fitz.open(PDF_PATH)
    text_content = []
    for page_num in range(len(doc)):
        page = doc[page_num]
        text_content.append(f"--- Page {page_num + 1} ---")
        text_content.append(page.get_text("text"))
    doc.close()
    
    raw_text_str = "\n".join(text_content)
    output_txt_path = PDF_PATH.replace(".pdf", ".txt")
    Path(output_txt_path).write_text(raw_text_str, encoding="utf-8")
    print(f"✅ Raw text successfully saved to: {output_txt_path}")
    
    # 2. Extract images
    print("📸 Extracting images from PDF...")
    pdf_bytes_data = Path(PDF_PATH).read_bytes()
    pdf_images = extract_pdf_images(PDF_PATH, pdf_bytes_data)
    
    # 3. Compile image references
    image_references = []
    for img in pdf_images:
        image_references.append(f"- pdf-image://{img['filename']} (extracted from Page {img['page']}, dimensions: {img['width']}x{img['height']})")
    image_refs_str = "\n".join(image_references) if image_references else "No images found."
    
    print(f"✅ Extracted {len(pdf_images)} image(s) to folder: {Path(PDF_PATH).with_suffix('')}/images")
    
    # 4. Generate external LLM prompt template
    prompt_content = f"""{SYSTEM_PROMPT}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  INSTRUCTIONS FOR THE EXTERNAL LLM
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Convert the raw textbook content below into AaravTutor JSON adhering strictly to the Golden Rules, paragraph types, JSON Schema, formatting and Hinglish meaning rules defined above.

PDF Images:
Match each extracted PDF image below to the topic/page it illustrates using its page number and surrounding context. Create a media paragraph of type "photo" placed immediately AFTER the prose paragraph that corresponds to that page. Use the exact filename as the URL with the pdf-image:// prefix:
{image_refs_str}

Raw Textbook Content:
{raw_text_str}
"""
    output_prompt_path = PDF_PATH.replace(".pdf", "_prompt.txt")
    Path(output_prompt_path).write_text(prompt_content, encoding="utf-8")
    print(f"📝 External LLM Prompt template saved to: {output_prompt_path}")
    sys.exit(0)

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
        params = {
            "action": "query",
            "generator": "search",
            "gsrnamespace": "6",
            "gsrsearch": query,
            "gsrlimit": "3",
            "prop": "imageinfo",
            "iiprop": "url|thumburl|mediatype",
            "iiurlwidth": "800",
            "format": "json"
        }
        r = requests.get("https://commons.wikimedia.org/w/api.php", params=params, timeout=10, headers={"User-Agent": "AaravTutor/1.0"})
        pages = r.json().get("query", {}).get("pages", {})
        for page in pages.values():
            info = page.get("imageinfo", [{}])[0]
            if info.get("mediatype") in ("BITMAP", "DRAWING"):
                title = page.get("title", "")
                if title.startswith("File:"):
                    filename = title[5:]  # Remove 'File:' prefix
                    filename = filename.replace(" ", "_")
                    return f"https://commons.wikimedia.org/wiki/Special:FilePath/{urllib.parse.quote(filename)}"
                return info.get("thumburl") or info.get("url")
    except Exception as e:
        print(f"          [Wikimedia API Error: {e}]")
    return None

def search_youtube(query: str, api_key: str) -> str | None:
    if not api_key:
        print("          [YouTube Error: API key is not configured]")
        return None
    try:
        params = {"part": "snippet", "q": query, "type": "video", "maxResults": "1", "safeSearch": "strict", "key": api_key}
        r = requests.get("https://www.googleapis.com/youtube/v3/search", params=params, timeout=10)
        res_json = r.json()
        if "error" in res_json:
            print(f"          [YouTube API Error: {res_json['error'].get('message', 'Unknown error')}]")
            return None
        items = res_json.get("items", [])
        if items: return f"https://www.youtube.com/watch?v={items[0]['id']['videoId']}"
    except Exception as e:
        print(f"          [YouTube Connection Error: {e}]")
    return None


def find_media_links(pdf_bytes: bytes, cached_topics=None) -> str:
    topics = cached_topics if cached_topics else extract_topics(pdf_bytes)
    media_lines = []
    print(f"      Running media searches for {len(topics)} topic(s)...")
    for t in topics:
        query, caption, kind = t.get("query", ""), t.get("caption", ""), t.get("type", "photo")
        if kind == "video":
            search_q = query + " educational"
            print(f"        Searching YouTube: '{search_q}'")
            url = search_youtube(search_q, YOUTUBE_KEY)
            if url:
                print(f"          -> Found: {url}")
                media_lines.append(f"VIDEO|{url}|{caption}")
            else:
                print(f"          -> No working video link found")
        else:
            print(f"        Searching Wikimedia: '{query}'")
            url = search_wikimedia(query)
            if url:
                print(f"          -> Found: {url}")
                media_lines.append(f"PHOTO|{url}|{caption}")
            else:
                print(f"          -> No working image link found")
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
print(f"[1/9] Analyzing target PDF file and initializing checkpoint...")
pdf_bytes = Path(PDF_PATH).read_bytes()
pdf_md5 = hashlib.md5(pdf_bytes).hexdigest()

if args.restart and _ckpt_path(PDF_PATH).exists():
    _ckpt_path(PDF_PATH).unlink()

ckpt = _load_checkpoint(PDF_PATH, pdf_md5)
ckpt.setdefault("completed", [])

if args.step:
    step_levels = {"images": 0, "topics": 1, "media": 2, "stream": 3, "meaning": 4, "cleanup": 5}
    target_level = step_levels.get(args.step, 0)
    if target_level <= 0: ckpt.pop("images", None)
    if target_level <= 1: ckpt.pop("topics", None)
    if target_level <= 2: ckpt.pop("media_hints", None)
    if target_level <= 3: ckpt.pop("raw_json_stream", None)
    if target_level <= 4: ckpt.pop("chapter_json_meanings_updated", None); ckpt.pop("chapter_json", None)
    if target_level <= 5: ckpt.pop("chapter_json", None)

# Step 2: PDF Image Extraction
has_failed_descriptions = False
if "images" in ckpt:
    pdf_images = ckpt["images"]
    if any(not img.get("description") or img["description"].startswith("(description failed:") or img["description"] == "(file not found)" for img in pdf_images):
        has_failed_descriptions = True
else:
    pdf_images = None

if pdf_images is not None and not has_failed_descriptions:
    print(f"[2/9] PDF Images: Loaded description from cache.")
elif args.skip_images or not _FITZ_AVAILABLE:
    pdf_images = []
    print("[2/9] PDF Images: Extraction skipped via configuration instructions.")
else:
    if pdf_images is not None:
        print("[2/9] PDF Images: Retrying failed descriptions from cache...")
    else:
        print("[2/9] PDF Images: Extracting and describing images from PDF...")
        pdf_images = extract_pdf_images(PDF_PATH, pdf_bytes)
        
    if pdf_images:
        pdf_images = describe_pdf_images(PDF_PATH, pdf_images)
    ckpt["images"] = pdf_images
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

# Step 3: Media Topics Extraction
if "topics" in ckpt:
    topics = ckpt["topics"]
    print("[3/9] Media Topics: Loaded from cache.")
else:
    print("[3/9] Media Topics: Extracting educational topics requiring visual media...")
    topics = extract_topics(pdf_bytes)
    ckpt["topics"] = topics
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

# Step 4: Media Search Engine (YouTube & Wikimedia)
if "media_hints" in ckpt:
    media_hints = ckpt["media_hints"]
    print("[4/9] Media Search: Media links loaded from cache.")
else:
    print("[4/9] Media Search: Querying YouTube and Wikimedia APIs for media links...")
    media_hints, topics = find_media_links(pdf_bytes, cached_topics=topics)
    ckpt["media_hints"] = media_hints
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)

# Step 5: Gemini Streaming Transformation (JSON Generation)
if "raw_json_stream" in ckpt or "chapter_json" in ckpt:
    print("[5/9] Gemini Stream: Loaded stream output from cache.")
else:
    print("[5/9] Gemini Stream: Querying Gemini model for structured chapter JSON...")
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
            f"Convert the above PDF textbook content into AaravTutor JSON.\n\n"
            f"IMPORTANT: You are provided with a list of verified media URLs under 'Media Maps'. "
            f"Whenever a topic/concept in the text matches one of these media maps, you MUST insert a media paragraph "
            f"containing the media items with the exact 'url' and 'caption' provided. "
            f"Prioritize these media maps and PDF Images over searching/guessing URLs.\n\n"
            f"Media Maps:\n{media_hints}\n"
            f"PDF Images:\n{image_section}"
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
    
    collected_stream = "".join(collected_chunks)
    ckpt["raw_json_stream"] = collected_stream
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)
    
    # Save unescaped raw output file for easy manual correction
    output_raw_path = PDF_PATH.replace(".pdf", "_raw.json")
    Path(output_raw_path).write_text(collected_stream, encoding="utf-8")
    print(f"      -> Cached raw LLM output to: {output_raw_path}")

# Step 6: Meaning Recalculation (re-run Gemini translation specifically on existing JSON)
if "chapter_json_meanings_updated" in ckpt:
    chapter_json = ckpt["chapter_json_meanings_updated"]
    print("[6/9] Meaning Recalculation: Loaded updated meanings from cache.")
elif args.step == "meaning":
    print("[6/9] Meaning Recalculation: Recalculating sentence meanings via Gemini...")
    # Load raw stream text from _raw.json or checkpoint cache
    output_raw_path = PDF_PATH.replace(".pdf", "_raw.json")
    if Path(output_raw_path).exists():
        raw_stream_text = Path(output_raw_path).read_text(encoding="utf-8")
    else:
        raw_stream_text = ckpt.get("raw_json_stream") or ""
        
    chapter_json = json.loads(raw_stream_text)
    
    response = _client.models.generate_content(
        model=MODEL,
        contents=[
            "You are provided with a structured AaravTutor JSON object. "
            "Your task is to update and recalculate the 'meaning' field for every sentence "
            "in the paragraphs to strictly adhere to the MEANING FIELD RULES (Hinglish/script rules). "
            "Do not modify the sentences' 'id' or 'text', and keep the structure exactly the same.\n\n"
            f"JSON to update:\n{json.dumps(chapter_json, ensure_ascii=False, indent=2)}"
        ],
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM_PROMPT,
            response_mime_type="application/json",
            response_schema=AARAVTUTOR_SCHEMA,
            max_output_tokens=100000,
            temperature=0.1
        )
    )
    chapter_json = json.loads(response.text)
    updated_json_str = json.dumps(chapter_json, ensure_ascii=False, indent=2)
    ckpt["chapter_json_meanings_updated"] = chapter_json
    # Write back to _raw.json to preserve updated meanings in unescaped format
    Path(output_raw_path).write_text(updated_json_str, encoding="utf-8")
    _save_checkpoint(PDF_PATH, pdf_md5, ckpt)
else:
    print("[6/9] Meaning Recalculation: Skipped (using original meanings).")

# Step 7: JSON Parsing & Validation
output_raw_path = PDF_PATH.replace(".pdf", "_raw.json")
if Path(output_raw_path).exists():
    print("[7/9] Parsing JSON: Parsing raw JSON from disk...")
    try:
        raw_stream_text = Path(output_raw_path).read_text(encoding="utf-8")
        chapter_json = json.loads(raw_stream_text)
    except json.JSONDecodeError as e:
        print(f"\n❌ JSON Parsing Failed: {e}")
        print(f"💡 You can edit and fix the JSON syntax in: {output_raw_path}")
        print("   Then restart the script to retry parsing/validation.")
        sys.exit(1)
elif "chapter_json_meanings_updated" in ckpt:
    chapter_json = ckpt["chapter_json_meanings_updated"]
    print("[7/9] Parsing JSON: Chapter JSON loaded from cache (meanings updated).")
elif "chapter_json" in ckpt:
    chapter_json = ckpt["chapter_json"]
    print("[7/9] Parsing JSON: Chapter JSON loaded from cache.")
elif "raw_json_stream" in ckpt:
    print("[7/9] Parsing JSON: Parsing raw stream text from cache...")
    try:
        chapter_json = json.loads(ckpt["raw_json_stream"])
    except json.JSONDecodeError as e:
        print(f"\n❌ JSON Parsing Failed: {e}")
        print("   Checkpoint stream is corrupted. Use --restart to start fresh.")
        sys.exit(1)
else:
    print("❌ Error: No raw JSON output found in cache or disk.")
    sys.exit(1)

# Step 8: Structural Polishing & Cleanup
if "chapter_json" in ckpt and args.step != "cleanup":
    print("[8/9] Polishing: Loaded polished chapter JSON from cache.")
else:
    print("[8/9] Polishing: Performing structural cleanup and formatting fixes...")
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

# Step 9: Saving Output JSON
print("[9/9] Saving Output: Writing finalized JSON file...")
output_path = PDF_PATH.replace(".pdf", ".json")
Path(output_path).write_text(json.dumps(chapter_json, ensure_ascii=False, indent=2), encoding="utf-8")

print(f"\n✅ Processing Phase Completed! Output saved to: {output_path}")
print("💡 Checkpoint file is preserved for quick step recalculations. Use --restart to start fresh.")
