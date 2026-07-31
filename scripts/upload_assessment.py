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

try:
    import drawsvg as draw
except ImportError:
    draw = None

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
    """Convert LaTeX math symbols into clean Unicode text (e.g. 65^{\\circ} -> 65°)."""
    if not text or not isinstance(text, str):
        return text

    s = text

    # Common TeX symbol replacements (handling optional braces e.g. ^{\circ}, ^{\degree}, ^{°}, {°})
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
        (r'\\implies', '⇒'),
        (r'\\angle', '∠'),
        (r'\\perp', '⊥'),
        (r'\\parallel', '∥'),
        (r'\\approx', '≈'),
        (r'\\neq', '≠'),
        (r'\\le(?:q)?\b', '≤'),
        (r'\\ge(?:q)?\b', '≥'),
        (r'\\times', '×'),
        (r'\\div', '÷'),
        (r'\\pi', 'π'),
        (r'\\theta', 'θ'),
        (r'\\alpha', 'α'),
        (r'\\beta', 'β'),
        (r'\\gamma', 'γ'),
        (r'\\delta', 'δ'),
        (r'\\frac\{([^}]+)\}\{([^}]+)\}', r'\1/\2'),
    ]

    for pattern, repl in replacements:
        s = re.sub(pattern, repl, s)

    # Remove inline math dollar signs e.g., $70°, 110°$ -> 70°, 110°
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


def render_diagram_spec(spec: dict) -> str:
    """Render a parametric diagramSpec object into an SVG string."""
    if not spec or not isinstance(spec, dict) or draw is None:
        return ""

    dtype = str(spec.get("type", "")).lower()

    try:
        if dtype == "intersecting_lines":
            d = draw.Drawing(300, 180, viewBox="0 0 300 180")
            cx, cy = 150.0, 90.0
            raw_angle = spec.get("knownAngle")
            known_angle = float(raw_angle) if raw_angle is not None else 110.0
            known_lbl = clean_latex_to_unicode(str(spec.get("knownLabel", f"{int(known_angle)}°")))
            unk_lbl = clean_latex_to_unicode(str(spec.get("unknownLabel", "x")))
            pos = str(spec.get("position", "vertically_opposite")).lower()

            rad1 = math.radians(30)
            rad2 = math.radians(140)

            # Lines
            d.append(draw.Line(cx - 120 * math.cos(rad1), cy + 120 * math.sin(rad1),
                               cx + 120 * math.cos(rad1), cy - 120 * math.sin(rad1),
                               stroke="#1E293B", stroke_width=2.5, stroke_linecap="round"))
            d.append(draw.Line(cx - 120 * math.cos(rad2), cy + 120 * math.sin(rad2),
                               cx + 120 * math.cos(rad2), cy - 120 * math.sin(rad2),
                               stroke="#1E293B", stroke_width=2.5, stroke_linecap="round"))

            # Top angle arc & label
            d.append(draw.Arc(cx, cy, 22, -140, -30, cw=False, stroke="#2563EB", stroke_width=2.0, fill="none"))
            d.append(draw.Text(known_lbl, font_size=15, x=cx, y=cy - 34, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="middle", dominant_baseline="central"))

            # Bottom angle arc & label
            if pos == "vertically_opposite":
                d.append(draw.Arc(cx, cy, 22, 40, 150, cw=False, stroke="#2563EB", stroke_width=2.0, fill="none"))
                d.append(draw.Text(unk_lbl, font_size=15, x=cx, y=cy + 34, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="middle", dominant_baseline="central"))
            else:
                d.append(draw.Arc(cx, cy, 22, -30, 40, cw=False, stroke="#2563EB", stroke_width=2.0, fill="none"))
                d.append(draw.Text(unk_lbl, font_size=15, x=cx + 34, y=cy, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="middle", dominant_baseline="central"))

            return d.as_svg()

        elif dtype == "parallel_lines_transversal":
            d = draw.Drawing(340, 200, viewBox="0 0 340 200")
            d.append(draw.Rectangle(0, 0, 340, 200, fill="#ffffff"))

            y1, y2 = 60.0, 140.0
            x_start, x_end = 50.0, 270.0

            d.append(draw.Line(x_start, y1, x_end, y1, stroke="#1E293B", stroke_width=2.5, stroke_linecap="round"))
            d.append(draw.Line(x_start, y2, x_end, y2, stroke="#1E293B", stroke_width=2.5, stroke_linecap="round"))

            line_names = spec.get("lines", ["AB", "CD"])
            l1_str = str(line_names[0]) if len(line_names) > 0 else "AB"
            l2_str = str(line_names[1]) if len(line_names) > 1 else "CD"

            # Line 1 (AB) endpoints
            label_a_left = l1_str[0] if len(l1_str) > 0 else "A"
            label_a_right = l1_str[1] if len(l1_str) > 1 else "B"
            d.append(draw.Text(label_a_left, font_size=15, x=x_start - 15, y=y1, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="end", dominant_baseline="central"))
            d.append(draw.Text(label_a_right, font_size=15, x=x_end + 15, y=y1, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="start", dominant_baseline="central"))

            # Line 2 (CD) endpoints
            label_b_left = l2_str[0] if len(l2_str) > 0 else "C"
            label_b_right = l2_str[1] if len(l2_str) > 1 else "D"
            d.append(draw.Text(label_b_left, font_size=15, x=x_start - 15, y=y2, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="end", dominant_baseline="central"))
            d.append(draw.Text(label_b_right, font_size=15, x=x_end + 15, y=y2, fill="#1E293B", font_weight="bold", font_family="sans-serif", text_anchor="start", dominant_baseline="central"))

            # Transversal line EF
            tx1, ty1 = 90.0, 180.0
            tx2, ty2 = 230.0, 20.0
            d.append(draw.Line(tx1, ty1, tx2, ty2, stroke="#2563EB", stroke_width=2.5, stroke_linecap="round"))

            trans_str = str(spec.get("transversal", "EF"))
            trans_top = trans_str[0] if len(trans_str) > 0 else "E"
            trans_bot = trans_str[1] if len(trans_str) > 1 else "F"
            d.append(draw.Text(trans_top, font_size=15, x=tx2 + 10, y=ty2 - 5, fill="#2563EB", font_weight="bold", font_family="sans-serif", text_anchor="start", dominant_baseline="central"))
            d.append(draw.Text(trans_bot, font_size=15, x=tx1 - 10, y=ty1 + 5, fill="#2563EB", font_weight="bold", font_family="sans-serif", text_anchor="end", dominant_baseline="central"))

            # Intersections E and F
            top_x = tx1 + 0.75 * (tx2 - tx1) # = 195.0
            top_y = y1                       # = 60.0
            bot_x = tx1 + 0.25 * (tx2 - tx1) # = 125.0
            bot_y = y2                       # = 140.0

            trans_down_deg = 180 - math.degrees(math.atan2(160, 140)) # ~ 131.2 deg
            trans_up_deg = -math.degrees(math.atan2(160, 140))        # ~ -48.8 deg
            arc_r = 24.0

            known_lbl = clean_latex_to_unicode(str(spec.get("knownLabel", "65°")))
            unk_lbl = clean_latex_to_unicode(str(spec.get("unknownLabel", "?")))

            # Top angle ∠ AEF (Interior Left Angle at top line AB): between ray EA (180°) and ray EF down-left (131.2°)
            d.append(draw.Arc(top_x, top_y, arc_r, trans_down_deg, 180, cw=True, stroke="#2563EB", stroke_width=2.0, fill="none"))
            bis_top_rad = math.radians(155.6)
            d.append(draw.Text(
                known_lbl,
                font_size=14,
                x=top_x + 38 * math.cos(bis_top_rad),
                y=top_y + 38 * math.sin(bis_top_rad),
                fill="#1E293B",
                font_weight="bold",
                font_family="sans-serif",
                text_anchor="middle",
                dominant_baseline="central"
            ))

            # Bottom angle ∠ DFE (Alternate Interior Angle at bottom line CD): between ray FD (0°) and ray FE up-right (-48.8°)
            d.append(draw.Arc(bot_x, bot_y, arc_r, trans_up_deg, 0, cw=True, stroke="#2563EB", stroke_width=2.0, fill="none"))
            bis_bot_rad = math.radians(-24.4)
            d.append(draw.Text(
                unk_lbl,
                font_size=14,
                x=bot_x + 38 * math.cos(bis_bot_rad),
                y=bot_y + 38 * math.sin(bis_bot_rad),
                fill="#1E293B",
                font_weight="bold",
                font_family="sans-serif",
                text_anchor="middle",
                dominant_baseline="central"
            ))

            return d.as_svg()

        elif dtype in ("triangle", "right_triangle"):
            d = draw.Drawing(300, 180, viewBox="0 0 300 180")
            p = draw.Path(stroke="#1E293B", stroke_width=2.5, fill="none", stroke_linecap="round", stroke_linejoin="round")
            p.M(60, 40).L(60, 140).L(240, 140).Z()
            d.append(p)

            verts = spec.get("vertices", ["A", "B", "C"])
            vA = verts[0] if len(verts) > 0 else "A"
            vB = verts[1] if len(verts) > 1 else "B"
            vC = verts[2] if len(verts) > 2 else "C"

            d.append(draw.Text(vA, font_size=15, x=50, y=30, fill="#1E293B", font_weight="bold", font_family="sans-serif"))
            d.append(draw.Text(vB, font_size=15, x=45, y=155, fill="#1E293B", font_weight="bold", font_family="sans-serif"))
            d.append(draw.Text(vC, font_size=15, x=250, y=155, fill="#1E293B", font_weight="bold", font_family="sans-serif"))

            labels = spec.get("sideLabels", {})
            if "AB" in labels:
                d.append(draw.Text(clean_latex_to_unicode(str(labels["AB"])), font_size=14, x=35, y=90, fill="#2563EB", font_weight="bold", font_family="sans-serif", text_anchor="end"))
            if "BC" in labels:
                d.append(draw.Text(clean_latex_to_unicode(str(labels["BC"])), font_size=14, x=150, y=162, fill="#2563EB", font_weight="bold", font_family="sans-serif", text_anchor="middle"))
            if "AC" in labels:
                d.append(draw.Text(clean_latex_to_unicode(str(labels["AC"])), font_size=14, x=160, y=80, fill="#2563EB", font_weight="bold", font_family="sans-serif", text_anchor="start"))

            return d.as_svg()

    except Exception as e:
        print(f"⚠️ render_diagram_spec error: {e}")

    return ""


def convert_asy_to_svg(asy_code: str) -> str:
    """Convert Asymptote (or TikZ) vector graphics code into an SVG string.
    Uses native 'asy' CLI if available; falls back to enhanced Python drawsvg parser.
    """
    if not asy_code or not asy_code.strip():
        return ""

    code = asy_code.strip()

    # --- Strategy 1: Try native 'asy' CLI executable if installed ---
    asy_bin = shutil.which("asy")
    if asy_bin:
        try:
            with tempfile.TemporaryDirectory() as tmpdir:
                asy_file = Path(tmpdir) / "diagram.asy"
                svg_file = Path(tmpdir) / "diagram.svg"
                asy_file.write_text(code, encoding="utf-8")

                cmd = [asy_bin, "-f", "svg", "-o", str(svg_file), str(asy_file)]
                res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, text=True)
                if res.returncode == 0 and svg_file.exists():
                    svg_content = svg_file.read_text(encoding="utf-8")
                    if "<svg" in svg_content:
                        return svg_content
        except Exception:
            pass

    # --- Strategy 2: Enhanced Python Vector Parser using drawsvg ---
    if draw is None:
        return ""

    try:
        canvas_w, canvas_h = 320, 200
        size_m = re.search(r'size\s*\(\s*(\d+)(?:\s*,\s*(\d+))?\s*\)', code)
        if size_m:
            w_val = int(size_m.group(1))
            h_val = int(size_m.group(2)) if size_m.group(2) else int(w_val * 0.75)
            canvas_w = max(260, min(480, int(w_val * 1.5)))
            canvas_h = max(180, min(360, int(h_val * 1.2)))

        d = draw.Drawing(canvas_w, canvas_h, viewBox=f"0 0 {canvas_w} {canvas_h}")

        scale = 36.0
        cx, cy = canvas_w / 2.0, canvas_h / 2.0

        def tx(x_val): return round(cx + float(x_val) * scale, 2)
        def ty(y_val): return round(cy - float(y_val) * scale, 2)

        DIR_MAP = {
            "N": (0.0, 0.4), "S": (0.0, -0.4), "E": (0.4, 0.0), "W": (-0.4, 0.0),
            "NE": (0.3, 0.3), "NW": (-0.3, 0.3), "SE": (0.3, -0.3), "SW": (-0.3, -0.3)
        }

        var_map: dict[str, tuple[float, float]] = {"O": (0.0, 0.0)}

        def parse_single_term(term: str) -> tuple[float, float] | None:
            t = term.strip()
            if not t:
                return None
            if t in var_map:
                return var_map[t]
            if t in DIR_MAP:
                return DIR_MAP[t]

            # Scale * var/dir e.g., 2.5*dir(30) or 0.85*dir(85)
            scale_m = re.match(r'^([\d\.\-]+)\s*\*\s*(.+)$', t)
            if scale_m:
                factor = float(scale_m.group(1))
                sub_pt = parse_single_term(scale_m.group(2))
                if sub_pt:
                    return (factor * sub_pt[0], factor * sub_pt[1])

            # dir(deg)
            dir_m = re.match(r'^dir\(([\d\.\-]+)\)$', t)
            if dir_m:
                deg = float(dir_m.group(1))
                rad = math.radians(deg)
                return (math.cos(rad), math.sin(rad))

            # (x, y)
            t_m = re.match(r'^\(([\d\.\-]+)\s*,\s*([\d\.\-]+)\)$', t)
            if t_m:
                return (float(t_m.group(1)), float(t_m.group(2)))

            return None

        def parse_expr(expr_str: str) -> tuple[float, float] | None:
            s = expr_str.strip()
            tokens = re.split(r'(\+|\-)', s)
            if not tokens:
                return None

            total_x, total_y = 0.0, 0.0
            current_op = '+'

            for tok in tokens:
                tok = tok.strip()
                if tok in ('+', '-'):
                    current_op = tok
                elif tok:
                    pt = parse_single_term(tok)
                    if pt is None:
                        return None
                    if current_op == '+':
                        total_x += pt[0]
                        total_y += pt[1]
                    else:
                        total_x -= pt[0]
                        total_y -= pt[1]

            return (total_x, total_y)

        clean_code = re.sub(r"\\begin\{tikzpicture\}(\[.*?\])?", "", code)
        clean_code = re.sub(r"\\end\{tikzpicture\}", "", clean_code)
        statements = clean_code.split(";")

        has_elements = False

        for stmt in statements:
            stmt = stmt.strip()
            if not stmt:
                continue

            # Parse pair definitions e.g. pair O=(0,0); or pair A=2.5*dir(30), B=2.5*dir(210);
            if "pair" in stmt or stmt.startswith("pair"):
                pair_defs = re.findall(r'([A-Za-z0-9_]+)\s*=\s*([^\s;]+(?:\([^)]*\))?[^\s;,]*)', stmt)
                for vname, expr in pair_defs:
                    pt = parse_expr(expr)
                    if pt:
                        var_map[vname] = pt
                continue

            # Parse arc(...)
            if "arc(" in stmt:
                arc_args = extract_func_args(stmt, "arc")
                if len(arc_args) >= 3:
                    center_pt = parse_expr(arc_args[0]) or (0.0, 0.0)
                    start_pt = parse_expr(arc_args[1])
                    end_pt = parse_expr(arc_args[2])
                    if start_pt and end_pt:
                        r = round(math.hypot(start_pt[0] - center_pt[0], start_pt[1] - center_pt[1]) * scale, 2)
                        start_deg = round(math.degrees(math.atan2(start_pt[1] - center_pt[1], start_pt[0] - center_pt[0])), 2)
                        end_deg = round(math.degrees(math.atan2(end_pt[1] - center_pt[1], end_pt[0] - center_pt[0])), 2)

                        d.append(draw.Arc(
                            tx(center_pt[0]),
                            ty(center_pt[1]),
                            r,
                            -end_deg,
                            -start_deg,
                            cw=False,
                            stroke="#2563EB",
                            stroke_width=2.0,
                            fill="none"
                        ))
                        has_elements = True
                        continue

            # Parse draw / fill commands
            if ("draw(" in stmt or "\\draw" in stmt or "fill(" in stmt or "\\fill" in stmt) and "label(" not in stmt:
                is_fill = "fill" in stmt or "\\fill" in stmt
                stroke_color = "#2563EB" if "blue" in stmt else ("#DC2626" if "red" in stmt else ("#16A34A" if "green" in stmt else "#1E293B"))

                path_match = re.search(r'(?:draw|fill)\s*\(\s*([^,\)]+(?:--[^,\)]+)+)', stmt)
                coords = []
                if path_match:
                    seg_str = path_match.group(1).replace("--cycle", "")
                    node_names = seg_str.split("--")
                    for n in node_names:
                        pt = parse_expr(n.strip())
                        if pt:
                            coords.append(pt)

                if len(coords) >= 2:
                    p = draw.Path(
                        stroke=stroke_color,
                        stroke_width=2.5,
                        fill=stroke_color if is_fill else 'none',
                        stroke_linecap='round',
                        stroke_linejoin='round'
                    )
                    p.M(tx(coords[0][0]), ty(coords[0][1]))
                    for c in coords[1:]:
                        p.L(tx(c[0]), ty(c[1]))
                    if "cycle" in stmt:
                        p.Z()
                    d.append(p)
                    has_elements = True

            # Parse label(...) statements
            if "label(" in stmt or "\\node" in stmt:
                lbl_args = extract_func_args(stmt, "label")
                if len(lbl_args) >= 2:
                    raw_text = clean_latex_to_unicode(lbl_args[0].strip('"\''))
                    pt_expr = lbl_args[1].strip()
                    pt = parse_expr(pt_expr)

                    dir_offset = lbl_args[2].strip() if len(lbl_args) >= 3 else ""
                    offset_x, offset_y = 0.0, 0.0
                    if dir_offset in DIR_MAP:
                        offset_x = DIR_MAP[dir_offset][0] * 35.0
                        offset_y = DIR_MAP[dir_offset][1] * 35.0

                    if pt:
                        d.append(draw.Text(
                            raw_text,
                            font_size=15,
                            x=tx(pt[0]) + offset_x,
                            y=ty(pt[1]) - offset_y,
                            fill="#1E293B",
                            font_weight="bold",
                            font_family="sans-serif",
                            text_anchor="middle",
                            dominant_baseline="central"
                        ))
                        has_elements = True

        if has_elements:
            return d.as_svg()
    except Exception as e:
        print(f"⚠️ drawsvg conversion error: {e}")

    return ""


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
        eq = dict(q)

        # Clean text fields from LaTeX math syntax ($70^\circ$ -> 70°)
        if 'question' in eq:
            eq['question'] = clean_latex_to_unicode(str(eq['question']))
        if 'hint' in eq:
            eq['hint'] = clean_latex_to_unicode(str(eq['hint']))
        if 'explanation' in eq:
            eq['explanation'] = clean_latex_to_unicode(str(eq['explanation']))

        raw_ca = eq.get('correctAnswer') or eq.get('answer') or ''
        if raw_ca:
            clean_ca = clean_latex_to_unicode(str(raw_ca))
            eq['correctAnswer'] = clean_ca
            eq['answer'] = clean_ca

        pid = str(eq.get('passageId', '')).strip()
        if pid and pid in passages:
            eq['passage'] = clean_latex_to_unicode(str(passages[pid]))

        # Render diagramSpec object if present
        dspec = eq.get('diagramSpec')
        if dspec and isinstance(dspec, dict):
            spec_svg = render_diagram_spec(dspec)
            if spec_svg:
                eq['svg'] = spec_svg

        # Convert question-level Asymptote code into SVG string if svg not set
        if 'svg' not in eq:
            asy_code = str(eq.get('asy') or eq.get('asymptote') or eq.get('tikz') or '')
            if asy_code.strip():
                svg_str = convert_asy_to_svg(asy_code)
                if svg_str:
                    eq['svg'] = svg_str

        # Normalise MCQ options & convert option Asymptote/diagramSpec to SVG / clean LaTeX
        if eq.get('type') == 'mcq' and isinstance(eq.get('options'), list):
            raw_opts = eq['options']
            flat_opts: list[str] = []
            expl_opts: list[str] = []
            for o in raw_opts:
                if isinstance(o, dict):
                    opt_spec = o.get('diagramSpec')
                    opt_asy = str(o.get('asy') or o.get('asymptote') or o.get('tikz') or '')
                    if opt_spec and isinstance(opt_spec, dict):
                        opt_svg = render_diagram_spec(opt_spec)
                        opt_val = opt_svg if opt_svg else str(o.get('text') or '')
                    elif opt_asy.strip():
                        opt_svg = convert_asy_to_svg(opt_asy)
                        opt_val = opt_svg if opt_svg else str(o.get('text') or '')
                    else:
                        opt_val = str(o.get('text') or o.get('option') or o.get('value') or '')
                        if "size(" in opt_val or "draw(" in opt_val:
                            opt_svg = convert_asy_to_svg(opt_val)
                            if opt_svg:
                                opt_val = opt_svg
                    if not opt_val.startswith("<svg"):
                        opt_val = clean_latex_to_unicode(opt_val)
                    flat_opts.append(opt_val)
                    expl_opts.append(clean_latex_to_unicode(str(o.get('explanation') or '')))
                else:
                    opt_val = str(o)
                    if "size(" in opt_val or "draw(" in opt_val:
                        opt_svg = convert_asy_to_svg(opt_val)
                        if opt_svg:
                            opt_val = opt_svg
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
