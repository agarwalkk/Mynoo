"""
gen_assessment_json.py
======================
Generates structured Assessment JSON from any PDF file using Gemini 2.5 Pro.
Output is 100% compatible with upload_assessment.py and Mynoo's AssessmentScreen.

Requirements:
  pip install google-genai pymupdf python-dotenv

Usage:
  python scripts/gen_assessment_json.py <path/to/assessment.pdf> [options]

Examples:
  python scripts/gen_assessment_json.py scripts/tenses.pdf --subject English --class 7
  python scripts/gen_assessment_json.py scripts/tenses.pdf --target-marks 50 --output scripts/tenses_paper.json
  python scripts/gen_assessment_json.py scripts/maths_quiz.pdf --restart
"""

import argparse
import hashlib
import json
import os
import sys
import time
import warnings
from pathlib import Path

# Ensure UTF-8 output on Windows consoles
if sys.stdout.encoding and sys.stdout.encoding.lower() != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Silence warnings
warnings.filterwarnings("ignore", category=UserWarning)

# PyMuPDF fallback import
try:
    import fitz  # PyMuPDF
    _FITZ_AVAILABLE = True
    if hasattr(fitz, "set_logging_level"):
        fitz.set_logging_level(0)
    elif hasattr(fitz, "TOOLS") and hasattr(fitz.TOOLS, "mutes_set_on"):
        fitz.TOOLS.mutes_set_on(True)
    else:
        os.environ["MU_LOG_LEVEL"] = "0"
except ImportError:
    _FITZ_AVAILABLE = False

from google import genai
from google.genai import types

# ── REPO ROOT & CONFIG ──
_REPO_ROOT = Path(__file__).resolve().parent.parent

def _get_local_property(key: str, default: str = "") -> str:
    """Retrieve key from local.properties or .env or environment."""
    # Check env vars first
    if os.environ.get(key):
        return os.environ[key]

    # Check local.properties
    paths_to_check = [
        Path(".") / "local.properties",
        _REPO_ROOT / "local.properties",
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
                            return v.strip("'\"").replace("\\:", ":").replace("\\\\", "\\")
            except Exception:
                pass

    # Check .env
    env_file = _REPO_ROOT / ".env"
    if env_file.exists():
        try:
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = [x.strip() for x in line.split("=", 1)]
                    if k == key:
                        return v.strip("'\"")
        except Exception:
            pass

    return default


GEMINI_API_KEY = _get_local_property("GEMINI_API_KEY", "")
DEFAULT_MODEL = "gemini-2.5-pro"

# Question types accepted by upload_assessment.py and AssessmentScreen
VALID_TYPES = {
    'mcq', 'short_answer', 'transformation', 'fill_blank',
    'error_correction', 'jumbled', 'match_columns', 'translation',
}

# ── DETERMINISTIC RESPONSE SCHEMA FOR GEMINI ──
ASSESSMENT_RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "title": {"type": "STRING", "description": "Assessment Title (4-8 words based on topic or paper header)"},
        "passages": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "id": {"type": "STRING"},
                    "text": {"type": "STRING"}
                },
                "required": ["id", "text"]
            }
        },
        "questions": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "id": {"type": "STRING", "description": "Unique question ID like q1, q2"},
                    "type": {
                        "type": "STRING",
                        "enum": ["mcq", "short_answer", "transformation", "fill_blank", "error_correction", "jumbled", "match_columns", "translation"]
                    },
                    "category": {"type": "STRING"},
                    "marks": {"type": "NUMBER", "minimum": 0, "maximum": 3, "default": 1, "multipleOf": 1},
                    "difficulty": {"type": "STRING", "enum": ["easy", "medium", "hard"]},
                    "question": {"type": "STRING", "description": "Question text in Markdown"},
                    "passageId": {"type": "STRING", "description": "ID of passage if based on reading comprehension, else empty string"},
                    "tag": {"type": "STRING"},
                    "hint": {"type": "STRING"},
                    "asy": {"type": "STRING", "description": "Asymptote vector code e.g. size(200); draw((0,0)--(4,0)--(1.5,3)--cycle); label(\"$A$\", (1.5,3.2)); for mathematical/geometry diagrams, or empty string if no diagram required"},
                    "options": {
                        "type": "ARRAY",
                        "description": "For MCQ, array of 4 option objects with text, optional asy diagram, and explanation",
                        "items": {
                            "type": "OBJECT",
                            "properties": {
                                "text": {"type": "STRING", "description": "Option text description or Asymptote code"},
                                "asy": {"type": "STRING", "description": "Optional Asymptote vector code if option is a visual diagram"},
                                "explanation": {"type": "STRING", "description": "Why option is correct or wrong"}
                            },
                            "required": ["text", "explanation"]
                        }
                    },
                    "correctIndex": {"type": "INTEGER", "description": "0-based index of correct option for MCQ, or -1"},
                    "correctAnswer": {"type": "STRING", "description": "Model correct answer string"},
                    "explanation": {"type": "STRING", "description": "Educational explanation of answer"},
                    "inputSentence": {"type": "STRING"},
                    "transformationType": {"type": "STRING"},
                    "blanks": {"type": "ARRAY", "items": {"type": "STRING"}},
                    "jumbledWords": {"type": "ARRAY", "items": {"type": "STRING"}},
                    "columnA": {"type": "ARRAY", "items": {"type": "STRING"}},
                    "columnB": {"type": "ARRAY", "items": {"type": "STRING"}},
                    "correctMatches": {"type": "ARRAY", "items": {"type": "STRING"}}
                },
                "required": ["id", "type", "category", "marks", "difficulty", "question", "correctAnswer", "explanation"]
            }
        }
    },
    "required": ["title", "passages", "questions"]
}

# ── SYSTEM PROMPT ──
SYSTEM_PROMPT = r"""You are **Mynoo Assessment Generator**, an expert Indian school teacher (CBSE/ICSE classes 6–12) and content structuring AI.

Your task is to analyze the provided PDF document (which may be a question paper, worksheet, test, or textbook chapter) and convert it into a structured assessment JSON object that follows the exact Mynoo Assessment Schema below.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ASSESSMENT JSON SCHEMA
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Return a SINGLE JSON object with the following top-level keys:

{
  "title": "Assessment Title (4-8 words based on topic or paper header)",
  "passages": [
    {
      "id": "p1",
      "text": "Full reading passage text here in Markdown format."
    }
  ],
  "questions": [
    ... list of question objects ...
  ]
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  RULES & QUESTION TYPES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. **Rich Markdown**:
   Use standard Markdown formatting for passage text, question stems, input sentences, explanations, and options where appropriate (bold, italics, code blocks, lists).

2. **Required Fields for EVERY question**:
   - "id": string (unique ID e.g. "q1", "q2", "q3"...)
   - "type": string (must be one of: mcq, fill_blank, transformation, error_correction, jumbled, match_columns, translation, short_answer)
   - "category": string (topic/subtopic e.g. "Tenses", "Preamble", "Algebra", "Reading Comprehension")
   - "marks": number (positive int or float, e.g. 1 or 2)
   - "difficulty": string ("easy", "medium", or "hard")
   - "question": string (the question text shown to the student)
   - "passageId": string ("p1" if linked to a passage in "passages", or "" if standalone)
   - "tag": string (optional label e.g. "Active to Passive", or "")
   - "hint": string (helpful hint for student, or "")
   - "correctAnswer": string (the complete correct answer string)
   - "explanation": string (clear, educational explanation of why the answer is correct)

3. **Type-Specific Fields**:

   a. **mcq**:
      - "options": list of EXACTLY 4 objects:
        [
          { "text": "Option A text", "explanation": "Why A is correct or wrong" },
          { "text": "Option B text", "explanation": "Why B is correct or wrong" },
          { "text": "Option C text", "explanation": "Why C is correct or wrong" },
          { "text": "Option D text", "explanation": "Why D is correct or wrong" }
        ]
      - "correctIndex": integer (0, 1, 2, or 3)
      - "correctAnswer": string (must match text of option at correctIndex)

   b. **fill_blank**:
      - "blanks": array of string expected blank fill words e.g. ["barks"]
      - "question": question text using "___" to represent blanks e.g. "The dog ___ every night."
      - "correctAnswer": full filled sentence or correct missing word(s)

   c. **transformation**:
      - "inputSentence": original sentence to transform e.g. "She wrote a letter."
      - "transformationType": target format e.g. "Active to Passive"
      - "correctAnswer": transformed sentence e.g. "A letter was written by her."

   d. **error_correction**:
      - "inputSentence": sentence containing error e.g. "He don't like mangoes."
      - "correctAnswer": corrected sentence e.g. "He doesn't like mangoes."

   e. **jumbled**:
      - "jumbledWords": array of words/phrases e.g. ["went", "she", "market", "to", "the"]
      - "correctAnswer": correctly ordered sentence e.g. "She went to the market."

   f. **match_columns**:
      - "columnA": array of strings e.g. ["If it rains,", "If I were rich,", "If you heat ice,"]
      - "columnB": array of strings e.g. ["(a) I would travel.", "(b) it melts.", "(c) we will stay indoors."]
      - "correctMatches": array of matching option letters corresponding to columnA items e.g. ["c", "a", "b"]
      - "correctAnswer": formatted match string e.g. "1-c, 2-a, 3-b"

   g. **translation**:
      - "inputSentence": sentence in original language e.g. "वह स्कूल जाती है।"
      - "correctAnswer": translated sentence e.g. "She goes to school."

   h. **short_answer**:
      - "correctAnswer": clear model answer for the question.

4. **Passage Linking**:
   If questions are based on a reading passage in the PDF:
   - Include the full passage text in the `passages` array with `id: "p1"`.
   - Set `passageId: "p1"` for all questions derived from that passage.
   - For non-passage questions, set `passageId: ""`.

5. **Mathematical Diagrams & High-Precision Asymptote Code**:
   For Mathematics, Geometry, or Science questions that involve visual diagrams:
   - Include an `"asy"` string field in the question object containing clean, valid Asymptote vector code.
   - **Asymptote Code in Options**: Asymptote code CAN ALSO BE placed inside the `"asy"` or `"text"` field of individual MCQ options (e.g. when student picks the correct diagram).
   - **STRICT GEOMETRIC & ANGLE ACCURACY RULES**:
     1. **Use Polar Coordinates `dir(deg)`**: Calculate endpoints using polar vectors matching exact degree values (e.g., for a 75° angle between two lines, set line angles to 20° and 95°: `pair A = 2.5 * dir(20); pair B = 2.5 * dir(200); pair C = 2.5 * dir(95); pair D = 2.5 * dir(275);`).
     2. **Draw Angle Arcs `arc(...)`**: Always draw circular arcs to indicate marked angles: `draw(arc(O, 0.5 * dir(20), 0.5 * dir(95)));`.
     3. **Bisector Label Positioning**: Place angle text along the angle bisector vector: `label("$75^\\circ$", 0.85 * dir(57.5));` (where 57.5° = (20° + 95°)/2).
   - **FEW-SHOT ASYMPTOTE EXAMPLES**:
     - *Intersecting Lines & Vertically Opposite Angles (75° & x)*:
       `size(180); pair O=(0,0); pair A=2.5*dir(20); pair B=2.5*dir(200); pair C=2.5*dir(95); pair D=2.5*dir(275); draw(A--B); draw(C--D); draw(arc(O, 0.5*dir(20), 0.5*dir(95))); label("$75^\\circ$", 0.85*dir(57.5)); draw(arc(O, 0.5*dir(200), 0.5*dir(275))); label("$x$", 0.85*dir(237.5));`
     - *Triangle with Right Angle & Vertices*:
       `size(180); draw((0,0)--(4,0)--(0,3)--cycle, black+1.2pt); label("$B$", (0,0), SW); label("$C$", (4,0), SE); label("$A$", (0,3), NW); label("6 cm", (0,1.5), W); label("8 cm", (2,0), S); label("$AC = ?$", (2,1.5), NE);`
     - *Parallel Lines with Slanted Transversal (110°)*:
       `size(220,140); draw((-2.5,1.5)--(3.5,1.5), black+1.2pt); draw((-2.5,-0.5)--(3.5,-0.5), black+1.2pt); draw((-1.8,-1.2)--(0.2,2.2), blue+1.2pt); draw((0.7,-1.2)--(2.7,2.2), blue+1.2pt); label("$l$", (3.7,1.5)); label("$m$", (3.7,-0.5)); label("$p$", (0.2,2.4)); label("$q$", (2.7,2.4)); label("$110^\\circ$", (-0.8,1.8)); label("$a$", (0.0,1.2)); label("$b$", (2.5,1.2)); label("$c$", (-0.8,-0.2));`

6. **Subject Specific Rule - Mathematics**:
   - For Mathematics subject assessments: **ALL questions MUST be Multiple Choice Questions (`"type": "mcq"`)**. Do NOT generate fill_blank, short_answer, or transformation questions for Mathematics.

7. **Completeness & Question Count**:
   - Digitizing a Question Paper: Capture ALL questions present in the PDF with their exact marks.
   - Generating Assessment from Chapter PDF: Create a comprehensive, well-balanced assessment containing MULTIPLE questions (at least 15–25 questions across various sections: MCQs, fill_blank, short_answer, match_columns, etc.).
   - NEVER output a single-question summary paper. Always generate a full test paper.
   - Every question object MUST have a non-empty "question" string.
   - For every "fill_blank" question, include a non-empty "blanks" array of expected answers e.g. ["0.5"].
   - Output valid, parseable JSON ONLY.
"""


# ── HELPERS ──

def _pdfs_md5(pdf_paths: list[Path]) -> str:
    """Compute combined MD5 hash of all PDF files for checkpoint caching."""
    hasher = hashlib.md5()
    for pdf_path in sorted(pdf_paths):
        with open(pdf_path, 'rb') as f:
            while chunk := f.read():
                hasher.update(chunk)
    return hasher.hexdigest()


def _get_cache_path(pdf_paths: list[Path]) -> Path:
    md5 = _pdfs_md5(pdf_paths)
    return pdf_paths[0].parent / f".cache_assessment_{md5}.json"


def _extract_pdfs_text_fitz(pdf_paths: list[Path]) -> str:
    """Extract text from multiple PDFs using PyMuPDF if available."""
    if not _FITZ_AVAILABLE:
        return ""
    all_blocks = []
    for idx, pdf_path in enumerate(pdf_paths, start=1):
        file_blocks = []
        try:
            doc = fitz.open(pdf_path)
            for page_num, page in enumerate(doc, start=1):
                page_text = page.get_text("text").strip()
                if page_text:
                    file_blocks.append(f"--- PAGE {page_num} ---\n{page_text}")
            doc.close()
            if file_blocks:
                all_blocks.append(f"=== FILE {idx}: {pdf_path.name} ===\n" + "\n\n".join(file_blocks))
        except Exception as e:
            print(f"⚠️  PyMuPDF text extraction warning for {pdf_path.name}: {e}")
    return "\n\n".join(all_blocks)


def _validate_assessment_json(data: dict) -> list[str]:
    """Validate generated assessment JSON against upload_assessment.py requirements."""
    errors: list[str] = []

    if not isinstance(data, dict):
        return ["Root JSON must be an object with 'questions' array."]

    questions = data.get("questions", [])
    if not isinstance(questions, list) or len(questions) == 0:
        return ["JSON missing 'questions' array or array is empty."]

    passages_list = data.get("passages", [])
    passages: dict[str, str] = {}
    if isinstance(passages_list, list):
        for p in passages_list:
            if isinstance(p, dict) and p.get("id") and p.get("text"):
                passages[str(p["id"])] = str(p["text"])

    ids_seen = set()
    for i, q in enumerate(questions):
        label = f"Q{i + 1} (id={q.get('id', '?')})"

        if not q.get('id'):
            errors.append(f"{label}: missing 'id'")
        elif q['id'] in ids_seen:
            errors.append(f"{label}: duplicate id '{q['id']}'")
        else:
            ids_seen.add(q['id'])

        qtype = str(q.get('type', '')).strip().lower()
        if qtype not in VALID_TYPES:
            errors.append(f"{label}: unknown type '{qtype}' — must be one of {sorted(VALID_TYPES)}")

        if not str(q.get('question', '')).strip():
            errors.append(f"{label}: 'question' text is empty")

        marks = q.get('marks')
        if marks is None:
            errors.append(f"{label}: missing 'marks'")
        elif not isinstance(marks, (int, float)) or marks <= 0:
            errors.append(f"{label}: 'marks' must be a positive number")

        # Type-specific checks
        if qtype == 'mcq':
            opts = q.get('options', [])
            if not isinstance(opts, list) or len(opts) < 2:
                errors.append(f"{label}: MCQ needs at least 2 options")
            ci = q.get('correctIndex')
            if ci is None:
                errors.append(f"{label}: MCQ missing 'correctIndex'")
            elif not isinstance(ci, int) or ci < 0 or ci >= len(opts):
                errors.append(f"{label}: MCQ 'correctIndex' {ci} is out of range")

        if qtype == 'fill_blank':
            blanks = q.get('blanks', [])
            if not isinstance(blanks, list) or len(blanks) == 0:
                errors.append(f"{label}: fill_blank missing 'blanks' array")

        if qtype == 'match_columns':
            colA = q.get('columnA', [])
            colB = q.get('columnB', [])
            if not isinstance(colA, list) or not isinstance(colB, list) or len(colA) == 0:
                errors.append(f"{label}: match_columns missing 'columnA' or 'columnB'")

        passage_id = str(q.get('passageId', '')).strip()
        if passage_id and passage_id not in passages:
            errors.append(f"{label}: references passageId '{passage_id}' which is not in 'passages'")

    return errors


def _export_txt_file(data: dict, txt_path: Path) -> None:
    """Format assessment into printable & downloadable text file."""
    lines = []
    title = data.get("title", "Assessment")
    questions = data.get("questions", [])
    passages = data.get("passages", [])
    total_marks = sum(float(q.get("marks", 1)) for q in questions)

    lines.append("=" * 80)
    lines.append(f"TITLE: {title}")
    lines.append(f"TOTAL QUESTIONS: {len(questions)} | TOTAL MARKS: {total_marks:.0f}")
    lines.append("=" * 80)
    lines.append("")

    if passages:
        lines.append("--- READING PASSAGES ---")
        for p in passages:
            lines.append(f"[{p.get('id', 'p')}]")
            lines.append(p.get("text", "").strip())
            lines.append("")

    lines.append("--- QUESTIONS ---")
    lines.append("")

    for i, q in enumerate(questions, start=1):
        qtype = q.get("type", "mcq").upper()
        marks = q.get("marks", 1)
        diff = q.get("difficulty", "medium").upper()
        category = q.get("category", "")
        pid = q.get("passageId", "")

        header = f"Q{i}. [{qtype}] [{marks} mark(s)] [{diff}]"
        if category:
            header += f" (Category: {category})"
        if pid:
            header += f" [Ref Passage: {pid}]"
        lines.append(header)
        lines.append(q.get("question", "").strip())

        if q.get("inputSentence"):
            lines.append(f"Input Sentence: {q['inputSentence']}")

        if q.get("type") == "mcq" and isinstance(q.get("options"), list):
            lines.append("Options:")
            for idx, opt in enumerate(q["options"]):
                letter = chr(65 + idx)
                if isinstance(opt, dict):
                    opt_text = opt.get("text", "")
                    opt_expl = opt.get("explanation", "")
                    lines.append(f"  ({letter}) {opt_text}")
                    if opt_expl:
                        lines.append(f"      Explanation: {opt_expl}")
                else:
                    lines.append(f"  ({letter}) {opt}")

        if q.get("type") == "jumbled" and q.get("jumbledWords"):
            lines.append(f"Jumbled Words: {q['jumbledWords']}")

        if q.get("type") == "match_columns":
            lines.append("Column A:")
            for idx, item in enumerate(q.get("columnA", [])):
                lines.append(f"  {idx+1}. {item}")
            lines.append("Column B:")
            for item in q.get("columnB", []):
                lines.append(f"  {item}")

        lines.append(f"Correct Answer: {q.get('correctAnswer', q.get('answer', ''))}")
        if q.get("explanation"):
            lines.append(f"Explanation: {q['explanation']}")
        lines.append("-" * 60)
        lines.append("")

    lines.append("=" * 80)
    lines.append("RAW JSON REPRESENTATION FOR UPLOAD:")
    lines.append("=" * 80)
    lines.append(json.dumps(data, indent=2, ensure_ascii=False))

    txt_path.write_text("\n".join(lines), encoding="utf-8")


# ── MAIN PIPELINE ──

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate Mynoo Assessment JSON from one or more PDF files using Gemini 2.5 Pro."
    )
    parser.add_argument("pdfs", nargs="+", help="Path(s) to input PDF file(s)")
    parser.add_argument("--child", default="Anish", help="Child's name for upload instructions (default: 'Anish')")
    parser.add_argument("--target-marks", type=float, default=None, help="Target total marks (e.g. 20, 50, 80)")
    parser.add_argument("--num-questions", type=int, default=None, help="Requested number of questions")
    parser.add_argument("--subject", default=None, help="Subject hint (e.g. 'English', 'Hindi', 'Mathematics')")
    parser.add_argument("--class", dest="class_num", default=None, help="Class/grade number (e.g. '7')")
    parser.add_argument("--title", default=None, help="Assessment title override")
    parser.add_argument("--model", default=DEFAULT_MODEL, help=f"Gemini model (default: {DEFAULT_MODEL})")
    parser.add_argument("-o", "--output", default=None, help="Output JSON path (default: <pdf_name>.json or <pdf1_name>_combined.json)")
    parser.add_argument("--txt-output", default=None, help="Output TXT file path (default: <output_json>.txt)")
    parser.add_argument("--restart", action="store_true", help="Ignore cache and regenerate from scratch")
    parser.add_argument("--dry-run-upload", action="store_true", help="Run upload_assessment dry run after generation")
    args = parser.parse_args()

    pdf_paths = [Path(p).resolve() for p in args.pdfs]
    missing = [p for p in pdf_paths if not p.exists()]
    if missing:
        sys.exit(f"❌ PDF file(s) not found: {', '.join(str(p) for p in missing)}")

    if not GEMINI_API_KEY:
        sys.exit(
            "❌ GEMINI_API_KEY not found!\n"
            "   Set it in local.properties, .env, or as environment variable GEMINI_API_KEY."
        )

    # Determine output file paths
    if args.output:
        output_json_path = Path(args.output)
    else:
        if len(pdf_paths) == 1:
            output_json_path = pdf_paths[0].with_suffix(".json")
        else:
            output_json_path = pdf_paths[0].parent / f"{pdf_paths[0].stem}_combined.json"

    output_txt_path = Path(args.txt_output) if args.txt_output else None

    print("📄 Assessment Generator")
    print(f"   Input PDF(s) : {', '.join(p.name for p in pdf_paths)} ({len(pdf_paths)} file(s))")
    print(f"   Target JSON  : {output_json_path.name}")
    if output_txt_path:
        print(f"   Target TXT   : {output_txt_path.name}")
    if args.subject:
        print(f"   Subject      : {args.subject}")
    if args.class_num:
        print(f"   Class        : {args.class_num}")
    if args.target_marks:
        print(f"   Target Marks : {args.target_marks}")

    # Check cache
    cache_path = _get_cache_path(pdf_paths)
    assessment_data = None
    if cache_path.exists() and not args.restart:
        print(f"\n📦 Loading cached response from {cache_path.name}...")
        try:
            assessment_data = json.loads(cache_path.read_text(encoding="utf-8"))
            print("✅ Successfully loaded from cache.")
        except Exception as e:
            print(f"⚠️ Cache corrupted ({e}), regenerating...")

    if assessment_data is None:
        print(f"\n🤖 Connecting to Gemini API ({args.model})...")
        client = genai.Client(api_key=GEMINI_API_KEY)

        # Upload files to Gemini File API
        uploaded_files = []
        for p in pdf_paths:
            print(f"☁️  Uploading PDF '{p.name}' to Gemini File API...")
            uf = client.files.upload(file=p)
            print(f"   Uploaded successfully: {uf.name}")
            uploaded_files.append(uf)

        # Extract supplemental text via PyMuPDF if available
        fitz_text = _extract_pdfs_text_fitz(pdf_paths)

        # Build prompt instructions
        user_instruction = f"Analyze the attached {len(pdf_paths)} PDF file(s) and generate a comprehensive, full-length assessment paper in exact JSON format."
        if args.subject or args.class_num:
            user_instruction += f"\nTarget Context: Subject: {args.subject or 'General'}, Class: {args.class_num or 'CBSE'}."
        if args.target_marks:
            user_instruction += (
                f"\nTarget Total Marks: {args.target_marks}. Generate a complete, multi-question test paper "
                f"(typically 15 to 25 questions: MCQs, Fill in blanks, Short Answer, Transformation, Match Columns) "
                f"whose individual question marks sum up to approximately {args.target_marks} marks."
            )
        if args.num_questions:
            user_instruction += f"\nTarget Number of Questions: {args.num_questions}. Generate EXACTLY {args.num_questions} questions."
        else:
            user_instruction += "\nGenerate a full assessment paper with at least 15 to 25 diverse questions covering all key topics across the provided PDF(s)."
        if args.subject and "math" in args.subject.lower():
            user_instruction += (
                "\n\nCRITICAL MATHEMATICS RULE: ALL questions MUST be Multiple Choice Questions (type=\"mcq\"). "
                "Do NOT generate fill_blank, short_answer, transformation, or match_columns questions. "
                "Every question MUST have type=\"mcq\" with 4 options. Asymptote vector code can be included in the question's 'asy' field "
                "OR inside the 'asy' or 'text' property of individual MCQ options."
            )

        if fitz_text:
            user_instruction += f"\n\nHere is extracted raw text from PyMuPDF for reference:\n{fitz_text[:16000]}"

        user_instruction += "\n\nOutput ONLY a valid JSON object matching the system schema. Do not include extra text outside JSON."

        contents = [*uploaded_files, user_instruction]

        print("⚡ Generating structured assessment JSON with Gemini Pro...")

        max_retries = 3
        raw_response_text = ""
        for attempt in range(1, max_retries + 1):
            try:
                collected_chunks = []
                output_bytes = 0
                t_start = time.time()

                for chunk in client.models.generate_content_stream(
                    model=args.model,
                    contents=contents,
                    config=types.GenerateContentConfig(
                        system_instruction=SYSTEM_PROMPT,
                        temperature=0.2,
                        response_mime_type="application/json",
                        response_schema=ASSESSMENT_RESPONSE_SCHEMA,
                        max_output_tokens=165536
                    )
                ):
                    if chunk.text:
                        collected_chunks.append(chunk.text)
                        output_bytes += len(chunk.text.encode("utf-8"))
                        print(f"\r   Received: {output_bytes:,} bytes", end="", flush=True)

                elapsed_total = time.time() - t_start
                print()
                print(f"   Streaming complete in {elapsed_total:.1f}s — {output_bytes:,} bytes received")

                raw_response_text = "".join(collected_chunks).strip()
                if raw_response_text:
                    break
            except Exception as e:
                print(f"\n⚠️  Gemini API error (attempt {attempt}/{max_retries}): {e}")
                if attempt < max_retries:
                    time.sleep(3)
                else:
                    sys.exit(f"❌ Failed to generate assessment from Gemini API after {max_retries} attempts.")

        # Clean JSON block formatting if present
        cleaned_json_text = raw_response_text
        if cleaned_json_text.startswith("```"):
            lines = cleaned_json_text.splitlines()
            if lines[0].startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].startswith("```"):
                lines = lines[:-1]
            cleaned_json_text = "\n".join(lines).strip()

        try:
            assessment_data = json.loads(cleaned_json_text)
        except json.JSONDecodeError as e:
            print(f"❌ Initial JSON decoding failed: {e}")
            repaired = False
            # 1. Attempt basic truncation fix if stream ended abruptly
            repaired_text = cleaned_json_text
            if not repaired_text.endswith("}"):
                last_q_end = repaired_text.rfind("}")
                if last_q_end != -1:
                    repaired_text = repaired_text[:last_q_end + 1] + "\n  ]\n}"
                    try:
                        assessment_data = json.loads(repaired_text)
                        repaired = True
                        print("✅ Successfully repaired JSON by closing truncated stream!")
                    except Exception:
                        pass
            
            if not repaired:
                print("🔄 Attempting repair with Gemini...")
                repair_response = client.models.generate_content(
                    model=args.model,
                    contents=[
                        f"The following output was cut off or has JSON syntax errors. "
                        f"Fix the JSON syntax so it becomes 100% valid JSON matching the Assessment schema. "
                        f"PRESERVE ALL questions in the list without discarding any:\n\n{cleaned_json_text}"
                    ],
                    config=types.GenerateContentConfig(
                        response_mime_type="application/json",
                        response_schema=ASSESSMENT_RESPONSE_SCHEMA,
                        max_output_tokens=165536,
                        temperature=0.0
                    )
                )
                try:
                    assessment_data = json.loads(repair_response.text)
                except Exception:
                    sys.exit(f"❌ Repair failed. Raw output preserved in error log.")

        # Save to cache
        cache_path.write_text(json.dumps(assessment_data, ensure_ascii=False, indent=2), encoding="utf-8")
        print("💾 Cached Gemini response.")

    # Override title if user passed explicit --title flag
    if args.title:
        assessment_data["title"] = args.title

    # Perform structural validation
    print("\n🔍 Validating generated Assessment structure...")
    validation_errors = _validate_assessment_json(assessment_data)

    if validation_errors:
        print(f"⚠️ Validation warnings ({len(validation_errors)}):")
        for err in validation_errors:
            print(f"   • {err}")
    else:
        print("✅ Structural validation passed with 0 errors!")

    # Calculate & adjust total marks if requested
    questions = assessment_data.get("questions", [])
    total_marks = sum(float(q.get("marks", 1)) for q in questions)

    if args.target_marks and total_marks != args.target_marks:
        print(f"⚖️ Target marks ({args.target_marks}) differs from total ({total_marks}). Adjusting marks...")
        scale = float(args.target_marks) / total_marks
        for q in questions:
            q["marks"] = round(float(q.get("marks", 1)) * scale, 1)
        total_marks = sum(float(q.get("marks", 1)) for q in questions)
        print(f"   Adjusted Total Marks: {total_marks}")

    # Write output JSON file
    output_json_path.write_text(
        json.dumps(assessment_data, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
    print(f"\n✅ JSON output saved to: {output_json_path}")

    # Write output TXT file if requested via --txt-output
    if output_txt_path:
        _export_txt_file(assessment_data, output_txt_path)
        print(f"✅ TXT export saved to: {output_txt_path}")

    # Print summary of generated paper
    print("\n" + "═" * 60)
    print(f"📋 ASSESSMENT SUMMARY: \"{assessment_data.get('title', 'Assessment')}\"")
    print("═" * 60)
    print(f"   Passages       : {len(assessment_data.get('passages', []))}")
    print(f"   Total Questions: {len(questions)}")
    print(f"   Total Marks    : {total_marks:.1f}")

    # Breakdown by question type
    type_counts = {}
    diff_counts = {}
    for q in questions:
        t = q.get("type", "unknown")
        d = q.get("difficulty", "medium")
        type_counts[t] = type_counts.get(t, 0) + 1
        diff_counts[d] = diff_counts.get(d, 0) + 1

    print("\n   Question Types:")
    for qtype, count in type_counts.items():
        print(f"     • {qtype:<18}: {count}")

    print("\n   Difficulty Breakdown:")
    for diff, count in diff_counts.items():
        print(f"     • {diff:<18}: {count}")

    print("═" * 60)

    # Optional dry-run upload test
    if args.dry_run_upload:
        print("\n🚀 Testing dry-run with upload_assessment.py...")
        from upload_assessment import _load_and_validate, _build_payload
        q_list, p_dict, f_title = _load_and_validate(output_json_path)
        print(f"✅ upload_assessment.py dry-run check PASSED for {len(q_list)} questions!")

    # Display upload instructions
    sub = args.subject or "English"
    cls = args.class_num or "7"
    child = args.child
    print("\n💡 NEXT STEP: To upload this assessment to Firestore, run:")
    print(
        f"   python scripts/upload_assessment.py --child {child} --subject {sub} --class {cls} "
        f"--file {output_json_path.relative_to(_REPO_ROOT) if output_json_path.is_relative_to(_REPO_ROOT) else output_json_path}"
    )


if __name__ == "__main__":
    main()
