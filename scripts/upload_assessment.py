"""
upload_assessment.py
====================
Uploads a pre-built assessment JSON file to Firebase Firestore so it appears
as a 'ready' assessment for a child in the AaravTutor app.

Firestore path: kids/{child}/assessments/{auto-id}

Usage (from repo root, with venv activated):
  python scripts/upload_assessment.py --child Anish --subject Mathematics --class 7 --file scripts/assessment.json

Dry-run (validates JSON, no writes):
  python scripts/upload_assessment.py --child Anish --subject Mathematics --class 7 --file scripts/assessment.json --dry-run

Arguments:
  --child     Child's name as stored in Firestore (case-sensitive, e.g. "Anish" or "Aarav")
  --subject   Subject name (e.g. "English", "Hindi", "Mathematics")
  --class     Class number as string (e.g. "7")
  --file      Path to the assessment JSON file (relative to repo root or absolute)
  --dry-run   Validate and preview without writing to Firestore
"""

import argparse
import json, re, math
import sys, subprocess, shutil, tempfile
from datetime import datetime, timezone
from pathlib import Path

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass


# ── Repo root (this file is in scripts/) ──────────────────────────────────────
_REPO_ROOT = Path(__file__).resolve().parent.parent

# Load .env
try:
    from dotenv import load_dotenv
    load_dotenv(_REPO_ROOT / '.env')
except ImportError:
    pass

# ── Constants ──────────────────────────────────────────────────────────────────
STORAGE_BUCKET   = 'aaravtutor-1e880.firebasestorage.app'
SERVICE_ACCOUNT  = _REPO_ROOT / 'mynoo-1e880-serviceaccount.json'

# Subject → language code (matches app logic)
SUBJECT_LANG: dict[str, str] = {
    'hindi':   'hi',
    'punjabi': 'pa',
}

# Question types accepted by the app
VALID_TYPES = {
    'mcq', 'short_answer', 'transformation', 'fill_blank',
    'error_correction', 'jumbled', 'match_columns', 'translation',
}


# ── Helpers ────────────────────────────────────────────────────────────────────

def _lang_for_subject(subject: str) -> str:
    return SUBJECT_LANG.get(subject.strip().lower(), 'en')


def clean_latex_to_unicode(text: str) -> str:
    """Convert LaTeX math symbols into clean Unicode text (e.g. \\dfrac{7}{10} -> 7/10, 65^{\\circ} -> 65°)."""
    if not text or not isinstance(text, str):
        return text

    s = text

    # 1. Fractions: \dfrac{a}{b}, \frac{a}{b}, \tfrac{a}{b}, \cfrac{a}{b}
    for _ in range(3):
        s = re.sub(r'\\(?:d|t|c)?frac\s*\{([^}]+)\}\s*\{([^}]+)\}', r'\1/\2', s)

    # 2. Text/font styling macros: \text{...}, \mathrm{...}, \mathbf{...}, \mathit{...}, \mathsf{...}, \mathtt{...}
    s = re.sub(r'\\(?:text|mathrm|mathbf|mathit|mathsf|mathtt)\s*\{([^}]+)\}', r'\1', s)

    # 3. Accents / lines: \overline{...}, \vec{...}, \hat{...}
    s = re.sub(r'\\(?:overline|vec|hat)\s*\{([^}]+)\}', r'\1', s)

    # 4. Kerning and micro-spacing: \!, \,, \;, \quad, \qquad
    s = re.sub(r'\\(?:!|,|;|quad|qquad)', '', s)

    # 5. Left/right delimiter pairs: \left(, \right), \left[, \right], \left\{, \right\}, \{, \}
    s = re.sub(r'\\left\s*([(\[{|.])', r'\1', s)
    s = re.sub(r'\\right\s*([)\]}|.])', r'\1', s)
    s = re.sub(r'\\\{', '{', s)
    s = re.sub(r'\\\}', '}', s)

    # 6. Degrees, Angles, Operators, Symbols
    replacements = [
        (r'\^\s*\{\s*\\circ\s*\}', '°'),
        (r'\^\s*\{\s*\\degree\s*\}', '°'),
        (r'\^\s*\{\s*°\s*\}', '°'),
        (r'\{\s*°\s*\}', '°'),
        (r'\^\s*\\circ', '°'),
        (r'\^\\circ', '°'),
        (r'\\circ', '°'),
        (r'\^\degree', '°'),
        (r'\\degree', '°'),
        (r'\\angle', '∠'),
        (r'\\measuredangle', '∡'),
        (r'\\implies', '⇒'),
        (r'\\iff', '⇔'),
        (r'\\perp', '⊥'),
        (r'\\parallel', '∥'),
        (r'\\approx', '≈'),
        (r'\\neq', '≠'),
        (r'\\ne\b', '≠'),
        (r'\\le(?:q)?\b', '≤'),
        (r'\\ge(?:q)?\b', '≥'),
        (r'\\times', '×'),
        (r'\\div', '÷'),
        (r'\\pm', '±'),
        (r'\\mp', '∓'),
        (r'\\cdot', '·'),
        (r'\\infty', '∞'),
        (r'\\pi', 'π'),
        (r'\\theta', 'θ'),
        (r'\\alpha', 'α'),
        (r'\\beta', 'β'),
        (r'\\gamma', 'γ'),
        (r'\\delta', 'δ'),
        (r'\\mu', 'μ'),
        (r'\\sigma', 'σ'),
    ]

    for pattern, repl in replacements:
        s = re.sub(pattern, repl, s)

    # 7. Subscripts & Superscripts
    sub_map = {'0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄', '5': '₅', '6': '₆', '7': '₇', '8': '₈', '9': '₉', '+': '₊', '-': '₋', '=': '₌', '(': '₍', ')': '₎', 'a': 'ₐ', 'e': 'ₑ', 'o': 'ₒ', 'x': 'ₓ', 'h': 'ₕ', 'k': 'ₖ', 'l': 'ₗ', 'm': 'ₘ', 'n': 'ₙ', 'p': 'ₚ', 's': 'ₛ', 't': 'ₜ'}
    sup_map = {'0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴', '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹', '+': '⁺', '-': '⁻', '=': '⁼', '(': '⁽', ')': '⁾', 'n': 'ⁿ', 'i': 'ⁱ'}

    def sub_replacer(match):
        val = match.group(1)
        if all(ch in sub_map for ch in val):
            return "".join(sub_map[ch] for ch in val)
        return f"_{val}"

    def sup_replacer(match):
        val = match.group(1)
        if all(ch in sup_map for ch in val):
            return "".join(sup_map[ch] for ch in val)
        return f"^{val}"

    s = re.sub(r'_\{\s*([^}]+)\s*\}', sub_replacer, s)
    s = re.sub(r'\^\{\s*([^}]+)\s*\}', sup_replacer, s)
    s = re.sub(r'_([0-9a-zA-Z])', sub_replacer, s)

    # 8. Remove inline math dollar signs e.g., $70°, 110°$ -> 70°, 110°
    s = re.sub(r'\$([^$]+)\$', r'\1', s)

    return s



def _sanitize_latex_in_json(raw_text: str) -> str:
    """Fix unescaped LaTeX backslashes inside JSON strings (e.g. \\circ -> \\\\circ)."""
    result = []
    i = 0
    length = len(raw_text)
    in_string = False

    while i < length:
        ch = raw_text[i]
        if ch == '"' and (i == 0 or raw_text[i-1] != '\\'):
            in_string = not in_string
            result.append(ch)
            i += 1
            continue

        if in_string and ch == '\\':
            if i + 1 < length:
                nxt = raw_text[i+1]
                if nxt in ['"', '\\', '/', 'b', 'f', 'n', 'r', 't']:
                    result.append('\\')
                    result.append(nxt)
                    i += 2
                    continue
                elif nxt == 'u' and i + 5 < length and all(c in '0123456789abcdefABCDEF' for c in raw_text[i+2:i+6]):
                    result.append('\\')
                    result.append('u')
                    i += 2
                    continue
                else:
                    result.append('\\\\')
                    i += 1
                    continue
        result.append(ch)
        i += 1
    return "".join(result)


def extract_func_args(stmt: str, func_name: str) -> list[str]:
    """Extract comma-separated arguments inside func_name(...) handling nested parentheses."""
    idx = stmt.find(func_name + "(")
    if idx == -1:
        return []
    start_idx = idx + len(func_name) + 1
    depth = 1
    arg_start = start_idx
    args = []

    i = start_idx
    while i < len(stmt) and depth > 0:
        ch = stmt[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                args.append(stmt[arg_start:i].strip())
                break
        elif ch == ',' and depth == 1:
            args.append(stmt[arg_start:i].strip())
            arg_start = i + 1
        i += 1
    return args


def _validate_questions(questions: list, passages: dict) -> list[str]:
    """Return a list of validation error strings (empty = OK)."""
    errors: list[str] = []
    ids_seen: set[str] = set()

    for i, q in enumerate(questions):
        label = f'Q{i + 1} (id={q.get("id", "?")})'

        # Required fields
        if not q.get('id'):
            errors.append(f'{label}: missing "id"')
        elif q['id'] in ids_seen:
            errors.append(f'{label}: duplicate id "{q["id"]}"')
        else:
            ids_seen.add(q['id'])

        qtype = str(q.get('type', '')).strip().lower()
        if qtype not in VALID_TYPES:
            errors.append(f'{label}: unknown type "{qtype}" — must be one of {sorted(VALID_TYPES)}')

        if not str(q.get('question', '')).strip():
            errors.append(f'{label}: "question" text is empty')

        marks = q.get('marks')
        if marks is None:
            errors.append(f'{label}: missing "marks"')
        elif not isinstance(marks, (int, float)) or marks <= 0:
            errors.append(f'{label}: "marks" must be a positive number')

        # Type-specific checks
        if qtype == 'mcq':
            opts = q.get('options', [])
            if not isinstance(opts, list) or len(opts) < 2:
                errors.append(f'{label}: MCQ needs at least 2 options')
            ci = q.get('correctIndex')
            if ci is None:
                errors.append(f'{label}: MCQ missing "correctIndex"')
            elif not isinstance(ci, int) or ci < 0 or ci >= len(opts):
                errors.append(f'{label}: MCQ "correctIndex" {ci} is out of range')

        if qtype == 'fill_blank':
            blanks = q.get('blanks', [])
            if not isinstance(blanks, list) or len(blanks) == 0:
                errors.append(f'{label}: fill_blank missing "blanks" array')

        # Passage reference validation
        passage_id = q.get('passageId', '').strip()
        if passage_id and passage_id not in passages:
            errors.append(f'{label}: references passageId "{passage_id}" which is not in "passages"')

    return errors


def _load_and_validate(json_path: Path) -> tuple[list, dict, str | None]:
    """Load JSON, return (questions_list, passages_dict, title). Exits on error."""
    if not json_path.exists():
        sys.exit(f'❌  File not found: {json_path}')

    raw_text = json_path.read_text(encoding='utf-8')
    try:
        data = json.loads(raw_text)
    except json.JSONDecodeError:
        sanitized = _sanitize_latex_in_json(raw_text)
        try:
            data = json.loads(sanitized)
        except json.JSONDecodeError as e:
            sys.exit(f'❌  Invalid JSON in {json_path.name}: {e}')

    # Accept bare array OR {questions: [...], passages: [...], title: "..."}
    if isinstance(data, list):
        questions = data
        passages_list: list = []
        file_title: str | None = None
    elif isinstance(data, dict):
        questions = data.get('questions', [])
        passages_list = data.get('passages', [])
        file_title = data.get('title') or None
    else:
        sys.exit('❌  JSON must be an array of questions or an object with a "questions" key.')

    if not isinstance(questions, list) or len(questions) == 0:
        sys.exit('❌  No questions found in JSON.')

    # Build passage lookup dict
    passages: dict[str, str] = {}
    for p in passages_list:
        if isinstance(p, dict) and p.get('id') and p.get('text'):
            passages[str(p['id'])] = str(p['text'])

    errors = _validate_questions(questions, passages)
    if errors:
        print(f'❌  Validation failed with {len(errors)} error(s):')
        for err in errors:
            print(f'    • {err}')
        sys.exit(1)

    return questions, passages, file_title




def normalize_asy_backslashes(code: str) -> str:
    """Fix over-escaped LaTeX backslashes in Asymptote code.

    JSON files often contain doubly-escaped backslashes (e.g. \\\\circ instead
    of \\circ).  After json.loads() this results in \\\\circ in the Python
    string, but Asymptote / LaTeX needs exactly \\circ (one backslash).

    This collapses runs of 2+ backslashes before known LaTeX command names
    down to a single backslash.
    """
    # Known LaTeX commands used in geometry Asymptote code
    latex_cmds = (
        'circ', 'angle', 'degree', 'triangle', 'square', 'perp',
        'parallel', 'approx', 'neq', 'leq', 'geq', 'le', 'ge',
        'times', 'div', 'pi', 'theta', 'alpha', 'beta', 'gamma',
        'delta', 'frac', 'sqrt', 'implies', 'infty', 'pm', 'mp',
        'cdot', 'ldots', 'cdots', 'text', 'mathrm', 'mathbf',
        'overline', 'underline', 'hat', 'bar', 'vec', 'tilde',
    )
    pattern = r'\\{2,}(' + '|'.join(latex_cmds) + r')'
    return re.sub(pattern, r'\\\1', code)


def fix_asy_format_int_real(code: str) -> str:
    """Fix Asymptote 3.x incompatibility: format("%d", expr) fails when expr is real.

    In Asymptote 3.x, integer division (e.g. i/10) returns a real, but
    format("%d", ...) requires an int argument.  This wraps the argument
    in an explicit (int)(...) cast to make it compatible.

    Handles patterns like:
      format("%d", i/10)  ->  format("%d", (int)(i/10))
    """
    # Match format("%d", <expr>) where expr doesn't already start with (int)
    def _fix_match(m):
        expr = m.group(1).strip()
        if expr.startswith('(int)'):
            return m.group(0)  # already cast
        return f'format("%d", (int)({expr}))'

    return re.sub(r'format\s*\(\s*"%d"\s*,\s*([^)]+)\)', _fix_match, code)


def sanitize_asy_code(code: str) -> str:
    """Apply all Asymptote code sanitization/normalization steps."""
    code = normalize_asy_backslashes(code)
    code = fix_asy_format_int_real(code)
    return code


def get_asy_binary() -> str | None:
    """Locate the Asymptote ('asy') binary executable on the system."""
    asy_candidates = [
        r"C:\Program Files\Asymptote\asy.exe",
        shutil.which("asy"),
        r"C:\Program Files (x86)\Asymptote\asy.exe",
        r"C:\Asymptote\asy.exe",
        r"c:\Apps\Mynoo\asy_bin\asy.exe",
    ]
    return next((path for path in asy_candidates if path and Path(path).exists()), None)


def convert_asy_to_svg(asy_code: str, asy_bin: str | None = None) -> str:
    """Convert Asymptote vector graphics code into an SVG string.
    Uses native 'asy' CLI executable ONLY. Returns empty string if asy is missing or fails.
    """
    if not asy_code or not asy_code.strip():
        return ""

    if not asy_bin:
        asy_bin = get_asy_binary()

    if not asy_bin:
        return ""

    code = sanitize_asy_code(asy_code.strip())

    try:
        # Build environment PATH with MiKTeX & Ghostscript binary directories
        import os
        env = dict(os.environ)
        extra_paths = [
            r"C:\Program Files\Asymptote",
            r"C:\Users\agarw\AppData\Local\Programs\MiKTeX\miktex\bin\x64",
            r"C:\Program Files\MiKTeX\miktex\bin\x64",
            r"C:\Program Files\gs\gs10.07.1\bin",
        ]
        # Search for any ghostscript bin folders
        gs_base = Path(r"C:\Program Files\gs")
        if gs_base.exists():
            for sub in gs_base.glob("gs*/bin"):
                extra_paths.append(str(sub))

        env['PATH'] = ";".join([p for p in extra_paths if Path(p).exists()]) + ";" + env.get('PATH', '')
        env['LIBGS'] = r"C:\Program Files\gs\gs10.07.1\bin\gsdll64.dll"
        env['ASYMPTOTE_GS'] = r"C:\Program Files\gs\gs10.07.1\bin\gswin64c.exe"
        env['MIKTEX_ENABLE_INSTALL'] = '0'
        env['MIKTEX_AUTO_INSTALL'] = '2'
        env['MIKTEX_NONINTERACTIVE'] = '1'

        # Add helper preamble if using common unimported functions like rightanglemark
        preamble = ""
        if "rightanglemark" in code and "path rightanglemark" not in code:
            preamble += (
                "path rightanglemark(pair A, pair B, pair C, real size=1) {\n"
                "    pair u = unit(A-B)*size;\n"
                "    pair v = unit(C-B)*size;\n"
                "    return B+u--B+u+v--B+v;\n"
                "}\n"
            )

        full_code = preamble + code

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            asy_file = tmp_path / "diagram.asy"
            out_prefix = tmp_path / "diagram"
            asy_file.write_text(full_code, encoding="utf-8")

            # Strategy 1a: Native asy -f svg (using latex + dvisvgm)
            cmd = [asy_bin, "-f", "svg", "-o", str(out_prefix), str(asy_file)]
            try:
                res = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, text=True, env=env)
                svg_files = list(tmp_path.glob("*.svg"))
                if res.returncode == 0 and svg_files:
                    svg_content = svg_files[0].read_text(encoding="utf-8")
                    if "<svg" in svg_content:
                        return svg_content
                else:
                    stderr_msg = (res.stderr or '').strip()[:200]
                    print(f"⚠️ asy SVG failed (exit {res.returncode}): {stderr_msg}")
            except subprocess.TimeoutExpired:
                print("⚠️ asy SVG timed out after 15s")

            # Strategy 1b: PDF generation with -tex none + PyMuPDF
            pdf_cmd = [asy_bin, "-f", "pdf", "-tex", "none", "-o", str(out_prefix), str(asy_file)]
            try:
                res_pdf = subprocess.run(pdf_cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, text=True, env=env)
                pdf_files = list(tmp_path.glob("*.pdf"))
                if res_pdf.returncode == 0 and pdf_files:
                    try:
                        import fitz
                        doc = fitz.open(pdf_files[0])
                        svg_content = doc[0].get_svg_image()
                        doc.close()
                        if "<svg" in svg_content:
                            return svg_content
                    except Exception:
                        pass
                else:
                    stderr_msg = (res_pdf.stderr or '').strip()[:200]
                    print(f"⚠️ asy PDF fallback failed (exit {res_pdf.returncode}): {stderr_msg}")
            except subprocess.TimeoutExpired:
                print("⚠️ asy PDF fallback timed out after 5s")
    except Exception as err:
        print(f"⚠️ asy execution warning: {err}")

    return ""


def strip_asy_code(questions: list) -> list:
    """Strip asy/asymptote/tikz fields from questions and options."""
    for q in questions:
        if isinstance(q, dict):
            q.pop('asy', None)
            q.pop('asymptote', None)
            q.pop('tikz', None)
            opts = q.get('options')
            if isinstance(opts, list):
                for opt in opts:
                    if isinstance(opt, dict):
                        opt.pop('asy', None)
                        opt.pop('asymptote', None)
                        opt.pop('tikz', None)
    return questions


def _clean_question_object(q: dict) -> dict:
    """Clean all string fields in a question object using clean_latex_to_unicode."""
    eq = dict(q)
    # Strip raw asy fields
    eq.pop('asy', None)
    eq.pop('asymptote', None)
    eq.pop('tikz', None)

    for field in ('question', 'hint', 'explanation', 'inputSentence'):
        if field in eq and isinstance(eq[field], str):
            eq[field] = clean_latex_to_unicode(eq[field])

    raw_ca = eq.get('correctAnswer') or eq.get('answer') or ''
    if raw_ca and isinstance(raw_ca, str):
        clean_ca = clean_latex_to_unicode(raw_ca)
        eq['correctAnswer'] = clean_ca
        eq['answer'] = clean_ca

    for arr_field in ('blanks', 'columnA', 'columnB', 'correctMatches', 'jumbledWords'):
        if arr_field in eq and isinstance(eq[arr_field], list):
            eq[arr_field] = [clean_latex_to_unicode(str(x)) if isinstance(x, str) else x for x in eq[arr_field]]

    if eq.get('type') == 'mcq' and isinstance(eq.get('options'), list):
        clean_opts = []
        for o in eq['options']:
            if isinstance(o, dict):
                co = dict(o)
                co.pop('asy', None)
                co.pop('asymptote', None)
                co.pop('tikz', None)
                if 'text' in co and isinstance(co['text'], str) and not co['text'].startswith('<svg'):
                    co['text'] = clean_latex_to_unicode(co['text'])
                if 'explanation' in co and isinstance(co['explanation'], str):
                    co['explanation'] = clean_latex_to_unicode(co['explanation'])
                clean_opts.append(co)
            elif isinstance(o, str) and not o.startswith('<svg'):
                clean_opts.append(clean_latex_to_unicode(o))
            else:
                clean_opts.append(o)
        eq['options'] = clean_opts

    return eq


def build_payload(
    child_name: str,
    subject: str,
    class_num: str,
    lang: str,
    questions: list,
    passages: dict,
    chapter_ids: list[str] | None,
    chapter_titles: list[str] | None,
    title: str | None,
) -> dict:
    now = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
    total_marks = sum(float(q.get('marks', 1)) for q in questions)

    enriched: list[dict] = []
    for q in questions:
        # Preserve any existing svg field before _clean_question_object
        pre_svg = q.get('svg')
        eq = _clean_question_object(q)
        if pre_svg:
            eq['svg'] = pre_svg

        pid = str(eq.get('passageId', '')).strip()
        if pid and pid in passages:
            eq['passage'] = clean_latex_to_unicode(str(passages[pid]))

        # Convert question-level Asymptote code into SVG string if svg not set and asy code present
        if 'svg' not in eq:
            asy_code = str(q.get('asy') or q.get('asymptote') or q.get('tikz') or '')
            if asy_code.strip():
                svg_str = convert_asy_to_svg(asy_code)
                if svg_str:
                    eq['svg'] = svg_str

        # Normalise MCQ options
        if eq.get('type') == 'mcq' and isinstance(eq.get('options'), list):
            raw_opts = q.get('options', [])
            flat_opts: list[str] = []
            expl_opts: list[str] = []
            for o in raw_opts:
                if isinstance(o, dict):
                    opt_asy = str(o.get('asy') or o.get('asymptote') or o.get('tikz') or '')
                    if opt_asy.strip():
                        opt_svg = convert_asy_to_svg(opt_asy)
                        opt_val = opt_svg if opt_svg else str(o.get('text') or '')
                    else:
                        opt_val = str(o.get('text') or o.get('option') or o.get('value') or '')
                    if not opt_val.startswith("<svg"):
                        opt_val = clean_latex_to_unicode(opt_val)
                    flat_opts.append(opt_val)
                    expl_opts.append(clean_latex_to_unicode(str(o.get('explanation') or '')))
                else:
                    opt_val = str(o)
                    if not opt_val.startswith("<svg"):
                        opt_val = clean_latex_to_unicode(opt_val)
                    flat_opts.append(opt_val)
                    expl_opts.append('')
            eq['options'] = flat_opts
            eq['optionExplanations'] = expl_opts

        enriched.append(eq)

    payload: dict = {
        'childName':      child_name,
        'subject':        subject,
        'classNum':       class_num,
        'lang':           lang,
        'status':         'ready',
        'createdAt':      now,
        'questions':      enriched,
        'totalQuestions': len(enriched),
        'totalMarks':     int(total_marks) if total_marks == int(total_marks) else total_marks,
    }
    if chapter_ids:
        payload['chapterIds'] = chapter_ids
    if chapter_titles:
        payload['chapterTitles'] = chapter_titles
    if title:
        payload['title'] = title
    return payload


def convert_assessment_json_asy_to_svg(json_path: Path) -> Path:
    """Pre-process an assessment JSON file to convert all non-blank 'asy' fields to 'svg' using the 'asy' binary.
    If 'asy' binary is present and non-blank 'asy' code is found:
      Saves the converted JSON to <filename>_svg.json alongside the original file, and returns its Path.
    If no non-blank 'asy' code is found:
      Does NOT create an SVG version of the JSON; returns the original file path.
    If non-blank 'asy' code is found but 'asy' binary is missing:
      Prints a warning message to the console, does NOT create an SVG version of the JSON,
      strips off asy code from questions, and returns the original file path.
    """
    if not json_path.exists():
        sys.exit(f'❌  File not found: {json_path}')

    raw_text = json_path.read_text(encoding='utf-8')
    try:
        data = json.loads(raw_text)
    except json.JSONDecodeError:
        sanitized = _sanitize_latex_in_json(raw_text)
        try:
            data = json.loads(sanitized)
        except json.JSONDecodeError as e:
            sys.exit(f'❌  Invalid JSON in {json_path.name}: {e}')

    if isinstance(data, list):
        questions = data
        passages = []
        file_title = None
    elif isinstance(data, dict):
        questions = data.get('questions', [])
        passages = data.get('passages', [])
        file_title = data.get('title')
    else:
        return json_path

    # Collect all targets with non-blank asy code
    asy_targets = []
    for q in questions:
        qid = q.get('id', 'question')
        q_asy = q.get('asy') or q.get('asymptote') or q.get('tikz')
        if q_asy and str(q_asy).strip():
            asy_targets.append(('question', q, qid, str(q_asy).strip()))

        opts = q.get('options')
        if isinstance(opts, list):
            for opt_idx, opt in enumerate(opts):
                if isinstance(opt, dict):
                    opt_asy = opt.get('asy') or opt.get('asymptote') or opt.get('tikz')
                    if opt_asy and str(opt_asy).strip():
                        asy_targets.append(('option', opt, f"{qid} option {opt_idx+1}", str(opt_asy).strip()))

    total_targets = len(asy_targets)
    if total_targets == 0:
        print(f"ℹ️  No non-blank Asymptote code found in {json_path.name}. Uploading file directly.")
        return json_path

    asy_bin = get_asy_binary()
    if not asy_bin:
        print(f"⚠️  Asymptote binary ('asy') not found on this machine.")
        print(f"    Skipping SVG creation and stripping 'asy' code from {json_path.name} before upload.\n")
        strip_asy_code(questions)
        return json_path

    # Clean text in all questions & passages first
    questions = [_clean_question_object(q) for q in questions]
    if isinstance(passages, list):
        for p in passages:
            if isinstance(p, dict) and 'text' in p:
                p['text'] = clean_latex_to_unicode(p['text'])

    print(f"🎨 Found {total_targets} non-blank Asymptote diagram(s) in {json_path.name}. Converting to SVG using asy binary...")
    converted_count = 0
    for i, (target_type, obj, label, code) in enumerate(asy_targets, 1):
        print(f"  [{i}/{total_targets}] Converting diagram for {label}...", end=" ", flush=True)
        svg_str = convert_asy_to_svg(code, asy_bin=asy_bin)
        if svg_str and "<svg" in svg_str:
            obj['svg'] = svg_str
            obj.pop('asy', None)
            obj.pop('asymptote', None)
            obj.pop('tikz', None)
            converted_count += 1
            print(f"✅ Done ({len(svg_str)} chars)")
        else:
            obj.pop('asy', None)
            obj.pop('asymptote', None)
            obj.pop('tikz', None)
            print("❌ Failed (Asymptote conversion error)")

    # Determine output path: e.g. assessment.json -> assessment_svg.json
    if json_path.name.endswith("_svg.json"):
        out_path = json_path
    else:
        out_name = f"{json_path.stem}_svg{json_path.suffix}"
        out_path = json_path.parent / out_name

    if isinstance(data, dict):
        out_data = {
            "title": file_title,
            "passages": passages,
            "questions": questions
        }
    else:
        out_data = questions

    out_path.write_text(json.dumps(out_data, indent=2, ensure_ascii=False), encoding='utf-8')
    print(f"\n💾 Saved SVG-converted assessment file: {out_path.name} ({converted_count}/{total_targets} diagrams converted to SVG)\n")
    return out_path


# ── Firebase upload ────────────────────────────────────────────────────────────

def upload_to_firestore(payload: dict, dry_run: bool) -> str | None:
    import os
    sa_path = os.environ.get('FIREBASE_SERVICE_ACCOUNT', str(SERVICE_ACCOUNT))
    if not Path(sa_path).exists():
        sys.exit(
            f'❌  Service account not found: {sa_path}\n'
            '    Generate one at Firebase Console → Project Settings → Service accounts.'
        )

    import firebase_admin
    from firebase_admin import credentials, firestore as fs_admin

    if not firebase_admin._apps:
        cred = credentials.Certificate(sa_path)
        firebase_admin.initialize_app(cred, {'storageBucket': STORAGE_BUCKET})

    db = fs_admin.client()

    child = payload['childName']
    if dry_run:
        return None

    ref = db.collection('kids').document(child).collection('assessments').add(payload)
    # firebase_admin.add() returns (timestamp, DocumentReference)
    doc_ref = ref[1] if isinstance(ref, tuple) else ref
    return doc_ref.id


# ── Main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description='Upload a pre-built assessment JSON to Firebase Firestore.',
    )
    parser.add_argument('--child',    required=True, help='Child name in Firestore (e.g. "Anish" or "Aarav")')
    parser.add_argument('--subject',  required=True, help='Subject name (e.g. "English")')
    parser.add_argument('--class',    dest='class_num', required=True, help='Class number (e.g. "7")')
    parser.add_argument('--file',     required=True, help='Path to assessment JSON (relative to repo root or absolute)')
    parser.add_argument('--chapter-ids',    nargs='+', metavar='ID',    default=None, help='Optional chapter IDs')
    parser.add_argument('--chapter-titles', nargs='+', metavar='TITLE', default=None, help='Optional chapter titles')
    parser.add_argument('--title',           default=None, help='Optional assessment title (e.g. "Ch4 Revision")')
    parser.add_argument('--dry-run',  action='store_true', help='Validate only — no writes')
    args = parser.parse_args()

    if args.dry_run:
        print('🔍  DRY RUN — nothing will be written.\n')

    # Resolve file path
    json_path = Path(args.file)
    if not json_path.is_absolute():
        candidate = _REPO_ROOT / json_path
        if candidate.exists():
            json_path = candidate

    # First, convert asy code in input JSON to SVG and save as <filename>_svg.json
    json_path = convert_assessment_json_asy_to_svg(json_path)

    questions, passages, file_title = _load_and_validate(json_path)

    subject   = args.subject.strip()
    class_num = args.class_num.strip()
    lang      = _lang_for_subject(subject)
    total_marks = sum(float(q.get('marks', 1)) for q in questions)
    title = args.title or file_title or None

    print(f'📝  Assessment details')
    print(f'    Child   : {args.child}')
    print(f'    Subject : {subject} | Class {class_num} | Lang {lang}')
    print(f'    File    : {json_path.name}')
    print(f'    Questions: {len(questions)} | Total marks: {total_marks:.0f}')
    if title:
        print(f'    Title   : {title}')
    if passages:
        print(f'    Passages : {len(passages)}')
    if args.chapter_ids:
        print(f'    Chapters : {", ".join(args.chapter_ids)}')
    print()

    payload = build_payload(
        child_name=args.child,
        subject=subject,
        class_num=class_num,
        lang=lang,
        questions=questions,
        passages=passages,
        chapter_ids=args.chapter_ids,
        chapter_titles=args.chapter_titles,
        title=title,
    )

    print('✅  Validation passed.')

    if args.dry_run:
        print()
        print('📋  Payload preview (first question):')
        preview = dict(payload)
        preview['questions'] = payload['questions'][:1]
        print(json.dumps(preview, indent=2, ensure_ascii=False))
        print()
        print('🔍  Dry run complete — no data written.')
        return

    print('☁️   Uploading to Firestore…')
    doc_id = upload_to_firestore(payload, dry_run=False)
    print()
    print('✅  Done!')
    print(f'   Firestore: kids/{args.child}/assessments/{doc_id}')
    print(f'   Status   : ready  (child can start the quiz immediately)')


if __name__ == '__main__':
    main()
