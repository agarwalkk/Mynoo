#!/usr/bin/env python3
"""
Mynoo Web Companion — Assessment Player
A lightweight Flask web server for taking Mynoo assessments in a browser.

Reads keys from the repo root local.properties and the Firebase service account
JSON — no extra configuration needed.
"""

import os, sys, json, re, uuid, math
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# ── Ensure UTF-8 on Windows consoles ─────────────────────────────────────────
if hasattr(sys.stdout, 'reconfigure'):
    try: sys.stdout.reconfigure(encoding='utf-8')
    except Exception: pass
if hasattr(sys.stderr, 'reconfigure'):
    try: sys.stderr.reconfigure(encoding='utf-8')
    except Exception: pass

# ── Flask ─────────────────────────────────────────────────────────────────────
from flask import Flask, render_template, request, jsonify, abort, redirect, url_for

# ── Firebase Admin ────────────────────────────────────────────────────────────
import firebase_admin
from firebase_admin import credentials, firestore

# ── Google Gen AI ─────────────────────────────────────────────────────────────
from google import genai
from google.genai import types as genai_types

# ═════════════════════════════════════════════════════════════════════════════
# Bootstrap — paths & config
# ═════════════════════════════════════════════════════════════════════════════

REPO_ROOT = Path(__file__).resolve().parent.parent
GEMINI_MODEL = 'gemini-3.8-flash'

app = Flask(__name__, template_folder='templates')
app.secret_key = os.environ.get('SECRET_KEY', 'mynoo-web-companion-2026-secret')
app.config['TEMPLATES_AUTO_RELOAD'] = True

# ── Read local.properties ─────────────────────────────────────────────────────
def _get_local_prop(key: str, default: str = '') -> str:
    for p in [REPO_ROOT / 'local.properties', Path('.') / 'local.properties']:
        if p.exists():
            for line in p.read_text(encoding='utf-8', errors='ignore').splitlines():
                line = line.strip()
                if line.startswith(key + '=') and not line.startswith('#'):
                    return line.split('=', 1)[1].strip()
    return os.environ.get(key, default)

GEMINI_API_KEY = _get_local_prop('GEMINI_API_KEY')

# ── Firebase init (lazy) ──────────────────────────────────────────────────────
_SA_CANDIDATES = [
    REPO_ROOT / 'mynoo-1e880-serviceaccount.json',
    Path('mynoo-1e880-serviceaccount.json'),
]

def _find_sa() -> Optional[Path]:
    for p in _SA_CANDIDATES:
        if p.exists():
            return p
    return None

_firebase_initialized = False

def get_db():
    global _firebase_initialized
    if not _firebase_initialized:
        sa = _find_sa()
        if sa is None:
            raise RuntimeError(
                f'Firebase service account JSON not found. '
                f'Expected at: {_SA_CANDIDATES[0]}'
            )
        cred = credentials.Certificate(str(sa))
        firebase_admin.initialize_app(cred)
        _firebase_initialized = True
    return firestore.client()

# ── Gemini client (lazy) ──────────────────────────────────────────────────────
_gemini_client = None

def get_gemini():
    global _gemini_client
    if _gemini_client is None:
        if not GEMINI_API_KEY:
            raise RuntimeError('GEMINI_API_KEY not found in local.properties')
        _gemini_client = genai.Client(api_key=GEMINI_API_KEY)
    return _gemini_client

# ═════════════════════════════════════════════════════════════════════════════
# LaTeX → Unicode helper
# ═════════════════════════════════════════════════════════════════════════════

_SUB_TRANS = str.maketrans('0123456789+-=()', '₀₁₂₃₄₅₆₇₈₉₊⁻⁼₍₎')
_SUP_TRANS = str.maketrans('0123456789+-=()', '⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾')

def _clean_latex(s: str) -> str:
    """Convert common LaTeX math notation to Unicode for browser display."""
    if not s:
        return s
    import re as _re
    s = str(s)
    # Arrows
    s = s.replace(r'\rightarrow', '→').replace(r'\to', '→').replace(r'\leftarrow', '←')
    s = s.replace(r'\Rightarrow', '⇒').replace(r'\Leftrightarrow', '⇔')
    # Fractions: \frac{a}{b} → a/b
    for _ in range(3):
        s = _re.sub(r'\\(?:d|t|c)?frac\s*\{([^}]+)\}\s*\{([^}]+)\}', r'\1/\2', s)
    # Remove font/text macros: \text{...}, \mathrm{...}, etc.
    s = _re.sub(r'\\(?:text|mathrm|mathbf|mathit|mathsf|mathtt)\s*\{([^}]+)\}', r'\1', s)
    # Superscripts with digits/signs → Unicode superscripts
    s = _re.sub(r'\^\{([0-9+\-=()]+)\}', lambda m: m.group(1).translate(_SUP_TRANS), s)
    s = _re.sub(r'\^([0-9+\-])', lambda m: m.group(1).translate(_SUP_TRANS), s)
    s = _re.sub(r'\^\{([^}]+)\}', r'^\1', s)
    # Subscripts with digits/signs → Unicode subscripts
    s = _re.sub(r'_\{([0-9+\-=()]+)\}', lambda m: m.group(1).translate(_SUB_TRANS), s)
    s = _re.sub(r'_([0-9])', lambda m: m.group(1).translate(_SUB_TRANS), s)
    s = _re.sub(r'_\{([^}]+)\}', r'_\1', s)
    # Common symbols
    s = s.replace(r'\degree', '°').replace(r'\circ', '°')
    s = s.replace(r'\pm', '±').replace(r'\times', '×').replace(r'\cdot', '·')
    s = s.replace(r'\Delta', 'Δ').replace(r'\alpha', 'α').replace(r'\beta', 'β')
    s = s.replace(r'\gamma', 'γ').replace(r'\lambda', 'λ').replace(r'\mu', 'μ')
    s = s.replace(r'\infty', '∞').replace(r'\leq', '≤').replace(r'\geq', '≥')
    s = s.replace(r'\neq', '≠').replace(r'\approx', '≈')
    # Remove stray $ math delimiters
    s = _re.sub(r'\$+([^$]+)\$+', r'\1', s)
    # Remove remaining lone backslash-commands (e.g. \quad, \,)
    s = _re.sub(r'\\(?:quad|qquad|,|;|!|:|medspace|thinspace|enspace)', ' ', s)
    # Collapse multiple spaces
    s = _re.sub(r'  +', ' ', s)
    return s.strip()


def _extract_equation_from_text(question: str) -> tuple:
    """
    Some questions embed the chemical equation inside the question text itself,
    e.g. '...reaction shown: HCl + NaOH → NaCl + H₂O. Determine the type.'
    Split those into (question_stem, equation) so the equation can be rendered
    visually distinct in the UI.
    Returns (cleaned_question, extracted_equation_or_empty).
    """
    import re as _re
    if '→' not in question:
        return question, ''
    m = _re.search(
        r'([A-Za-z0-9()\[\]\s+^_\-·\u2070-\u209F\u00B2\u00B3\u00B9]*→[A-Za-z0-9()\[\]\s+^_\-·\u2070-\u209F\u00B2\u00B3\u00B9]+(?:\([^)]+\))?)'
        r'(?=[\s.]*(?:What\s+|Determine\s+|Find\s+|State\s+|Calculate\s+|\?|$))',
        question, _re.IGNORECASE
    )
    if not m:
        return question, ''
    eq = m.group(1).strip()
    if '→' not in eq:
        return question, ''
    left, right = eq.split('→', 1)
    if not (_re.search(r'[A-Za-z]', left) and _re.search(r'[A-Za-z]', right)):
        return question, ''

    before = question[:m.start()].rstrip()
    after = question[m.end():].lstrip()
    if after.startswith('.'):
        after = after[1:].lstrip()

    if before.endswith(':'):
        stem = before
        if after:
            stem = stem + ' ' + after
    elif before and after:
        stem = before + ' ' + after
    else:
        stem = before or after

    return stem.strip(), eq


# ═════════════════════════════════════════════════════════════════════════════
# Firestore helpers
# ═════════════════════════════════════════════════════════════════════════════

def _resolve_child(db, name: str) -> str:
    """Case-insensitive child document resolution."""
    try:
        if db.collection('kids').document(name).get().exists:
            return name
    except Exception:
        pass
    try:
        for doc in db.collection('kids').stream():
            if doc.id.lower() == name.lower():
                return doc.id
    except Exception:
        pass
    return name


def list_kids(db) -> list:
    kids = []
    for doc in db.collection('kids').stream():
        d = doc.to_dict() or {}
        kids.append({
            'id':       doc.id,
            'name':     d.get('name', doc.id),
            'age':      d.get('age', ''),
            'classNum': d.get('class', ''),
        })
    return sorted(kids, key=lambda k: k['name'].lower())


def _parse_question(q: dict) -> dict:
    qtype     = str(q.get('type', 'mcq'))
    question  = _clean_latex(str(q.get('question', '')))
    input_r   = _clean_latex(str(q.get('inputReaction',      '') or ''))
    unbal_eq  = _clean_latex(str(q.get('unbalancedEquation', '') or ''))
    input_s   = _clean_latex(str(q.get('inputSentence',      '') or ''))

    # For reaction/equation questions where the equation is embedded in the
    # question text (no dedicated field), extract it into the right field.
    if qtype == 'reaction_identification' and not input_r:
        question, input_r = _extract_equation_from_text(question)
    if qtype == 'equation_balancing' and not unbal_eq:
        question, unbal_eq = _extract_equation_from_text(question)

    return {
        'id':                 str(q.get('id', '')),
        'type':               qtype,
        'question':           question,
        'options':            [_clean_latex(str(o)) for o in q.get('options', [])],
        'correctIndex':       int(q.get('correctIndex', -1)),
        'answer':             _clean_latex(str(q.get('answer', q.get('correctAnswer', '')) or '')),
        'inputSentence':      input_s,
        'tag':                str(q.get('tag', '')),
        'blanks':             [_clean_latex(str(b)) for b in q.get('blanks', [])],
        'marks':              float(q.get('marks', 1.0)),
        'difficulty':         str(q.get('difficulty', '')),
        'explanation':        _clean_latex(str(q.get('explanation', '') or '')),
        'passage':            _clean_latex(str(q.get('passage', '') or '')),
        'hint':               _clean_latex(str(q.get('hint', '') or '')),
        'transformationType': str(q.get('transformationType', '')),
        'jumbledWords':       [_clean_latex(str(w)) for w in q.get('jumbledWords', [])],
        'columnA':            [_clean_latex(str(c)) for c in q.get('columnA', [])],
        'columnB':            [_clean_latex(str(c)) for c in q.get('columnB', [])],
        'correctMatches':     [_clean_latex(str(c)) for c in q.get('correctMatches', [])],
        'unbalancedEquation': unbal_eq,
        'inputReaction':      input_r,
    }


def _parse_assessment(doc) -> dict:
    d = doc.to_dict() or {}
    questions = [_parse_question(q) for q in (d.get('questions') or [])]
    raw_answers = list(d.get('answers') or [])
    # Normalise to None for unanswered slots
    answers: list = [a if isinstance(a, dict) else None for a in raw_answers]
    while len(answers) < len(questions):
        answers.append(None)
    return {
        'id':            doc.id,
        'subject':       d.get('subject', ''),
        'classNum':      d.get('classNum', ''),
        'lang':          d.get('lang', 'en'),
        'date':          d.get('date', ''),
        'status':        d.get('status', 'ready'),
        'title':         d.get('title', ''),
        'chapterTitles': d.get('chapterTitles', []),
        'score':         d.get('score'),
        'summary':       d.get('summary', ''),
        'createdAt':     d.get('createdAt', ''),
        'completedAt':   d.get('completedAt', ''),
        'questions':     questions,
        'answers':       answers[:len(questions)],
    }


def list_assessments(db, child_name: str) -> list:
    kid_id = _resolve_child(db, child_name)
    col = db.collection('kids').document(kid_id).collection('assessments')
    try:
        snap = col.order_by('createdAt', direction=firestore.Query.DESCENDING).limit(40).stream()
    except Exception:
        try:
            snap = col.order_by('date', direction=firestore.Query.DESCENDING).limit(40).stream()
        except Exception:
            snap = col.limit(40).stream()
    return [_parse_assessment(doc) for doc in snap]


def load_assessment(db, child_name: str, assessment_id: str) -> Optional[dict]:
    kid_id = _resolve_child(db, child_name)
    doc = (db.collection('kids').document(kid_id)
             .collection('assessments').document(assessment_id).get())
    return _parse_assessment(doc) if doc.exists else None


def save_assessment_progress(db, child_name: str, assessment_id: str,
                              answers: list, status: str):
    kid_id = _resolve_child(db, child_name)
    (db.collection('kids').document(kid_id)
       .collection('assessments').document(assessment_id)
       .update({'answers': answers, 'status': status}))


def mark_assessment_complete(db, child_name: str, assessment_id: str,
                              answers: list, score: float, summary: str):
    kid_id = _resolve_child(db, child_name)
    (db.collection('kids').document(kid_id)
       .collection('assessments').document(assessment_id)
       .update({
           'answers':     answers,
           'status':      'completed',
           'score':       score,
           'summary':     summary,
           'completedAt': datetime.now(timezone.utc).isoformat(),
       }))

# ═════════════════════════════════════════════════════════════════════════════
# Scoring
# ═════════════════════════════════════════════════════════════════════════════

def _is_answered(ans: Optional[dict]) -> bool:
    if not ans or not isinstance(ans, dict):
        return False
    t = ans.get('type', '')
    if t == 'mcq' or 'selectedIndex' in ans:
        return int(ans.get('selectedIndex', -1) or -1) >= 0
    text = str(ans.get('textAnswer') or '').strip()
    fb   = str(ans.get('aiFeedback') or '').strip()
    return bool(text) and text != '(skipped)' and fb != 'Skipped'


def compute_score(questions: list, answers: list) -> tuple:
    """Returns (earned_marks, total_marks)."""
    total  = sum(q['marks'] for q in questions)
    earned = 0.0
    for i, q in enumerate(questions):
        ans = answers[i] if i < len(answers) else None
        if not isinstance(ans, dict):
            continue
        t = ans.get('type', '')
        if t == 'mcq':
            if ans.get('correct'):
                earned += q['marks'] if int(ans.get('attempts', 1) or 1) == 1 else q['marks'] / 2.0
        else:
            ai = ans.get('earnedMarks') or ans.get('aiEarnedMarks')
            if ai is not None:
                earned += float(ai)
            else:
                sg = ans.get('selfGrade', '')
                if sg == 'got_it':     earned += q['marks']
                elif sg == 'partial':  earned += q['marks'] / 2.0
    return earned, total


def _is_exact_match(a: str, b: str) -> bool:
    def norm(s):
        s = re.sub(r'\s+', ' ', s.strip().lower())
        s = re.sub(r'[.!?,;:]+$', '', s).strip()
        return s
    return norm(a) == norm(b)

# ═════════════════════════════════════════════════════════════════════════════
# Gemini validation & summary
# ═════════════════════════════════════════════════════════════════════════════

def _build_validation_prompt(q: dict, child_answer: str, assessment: dict) -> str:
    parts = [
        "You are an expert school teacher grading a student written answer. "
        "Return ONLY a valid JSON object (no markdown fences, no extra text).",
        f"Subject: {assessment.get('subject','')}, Class: {assessment.get('classNum','')}",
        f"Marks for this question: {q['marks']} — award earnedMarks between 0 and {q['marks']} in steps of 0.5.",
        f"Question type: {q['type']}",
    ]
    if q.get('passage'):
        parts.append(f'Reading passage:\n"""\n{q["passage"]}\n"""')
    parts.append(f"Question: {q['question']}")
    if q.get('inputSentence'):    parts.append(f"Input sentence: \"{q['inputSentence']}\"")
    if q.get('unbalancedEquation'): parts.append(f"Unbalanced equation: \"{q['unbalancedEquation']}\"")
    if q.get('inputReaction'):    parts.append(f"Chemical reaction: \"{q['inputReaction']}\"")
    if q.get('transformationType'): parts.append(f"Transformation type: {q['transformationType']}")
    elif q.get('tag'):            parts.append(f"Grammar focus: {q['tag']}")
    parts.append(f"Model answer: \"{q.get('answer','')}\"")
    parts.append(f"Student's answer: \"{child_answer}\"")
    if q['type'] == 'jumbled':
        parts.append(
            "Note: The student reconstructed a jumbled sentence by selecting word tiles. "
            "Do NOT deduct marks for lowercase initial letter or missing trailing punctuation "
            "if the words are in the correct sequence."
        )
    parts.append(
        "Return a JSON object with these exact fields:\n"
        "- verdict: string, one of: correct, partial, wrong\n"
        f"- earnedMarks: number, 0 to {q['marks']} in steps of 0.5\n"
        "- feedback: string, 1-2 encouraging sentences explaining the grade\n"
        "- corrections: array of {type:'spelling'|'grammar'|'deletion'|'addition', original:string, corrected:string}\n"
        "- correctedAnswer: string, the ideal corrected version (empty string if fully correct)\n"
        "Keep corrections minimal — only short phrases or individual words, not full sentences."
    )
    return '\n\n'.join(parts)


def validate_text_answer(q: dict, child_answer: str, assessment: dict) -> dict:
    client = get_gemini()
    prompt = _build_validation_prompt(q, child_answer, assessment)
    resp = client.models.generate_content(
        model=GEMINI_MODEL,
        contents=prompt,
        config=genai_types.GenerateContentConfig(
            temperature=0.1,
            response_mime_type='application/json',
        ),
    )
    raw = resp.text.strip()
    raw = re.sub(r'^```(?:json)?\s*', '', raw)
    raw = re.sub(r'\s*```$', '', raw)
    try:
        data = json.loads(raw)
    except Exception:
        data = {}
    verdict = data.get('verdict', 'wrong')
    earned  = float(data.get('earnedMarks', 0.0))
    earned  = round(max(0.0, min(float(q['marks']), earned)) * 2) / 2  # round to 0.5
    return {
        'verdict':        verdict,
        'earnedMarks':    earned,
        'feedback':       data.get('feedback', ''),
        'corrections':    data.get('corrections', []),
        'correctedAnswer': data.get('correctedAnswer', ''),
    }


def generate_summary(assessment: dict, answers: list, score: float) -> str:
    client = get_gemini()
    lang_name = {'hi': 'Hindi', 'pa': 'Punjabi'}.get(assessment.get('lang', 'en'), 'English')
    lines = []
    for i, q in enumerate(assessment['questions']):
        ans = answers[i] if i < len(answers) else None
        if isinstance(ans, dict):
            if q['type'] == 'mcq':
                si = int(ans.get('selectedIndex', -1) or -1)
                user_ans = q['options'][si] if 0 <= si < len(q['options']) else '(skipped)'
                ci = int(q.get('correctIndex', -1))
                correct_ans = q['options'][ci] if 0 <= ci < len(q['options']) else q.get('answer', '')
            else:
                user_ans   = ans.get('textAnswer', '(skipped)')
                correct_ans = q.get('answer', '')
        else:
            user_ans, correct_ans = '(skipped)', ''
        lines.append(
            f"Q{i+1} [{q['type']}]: {q['question']}\n"
            f"Child answered: {user_ans}\nCorrect: {correct_ans}"
        )
    prompt = (
        f"Review this {assessment['subject']} assessment for Class {assessment['classNum']} "
        f"(Language medium: {lang_name}). The child scored {int(round(score))}%.\n\n"
        + '\n\n'.join(lines)
        + "\n\nWrite a 3-sentence performance summary: overall result, what they did well, "
          "and what to focus on next. Be warm and encouraging."
    )
    resp = client.models.generate_content(model=GEMINI_MODEL, contents=prompt)
    return resp.text.strip()

# ═════════════════════════════════════════════════════════════════════════════
# Jinja2 helpers
# ═════════════════════════════════════════════════════════════════════════════

def _answered_count(assessment: dict) -> int:
    return sum(1 for a in assessment['answers'] if _is_answered(a))

app.jinja_env.globals['answered_count'] = _answered_count

@app.template_filter('subject_color')
def subject_color(subject: str) -> str:
    s = (subject or '').lower().strip()
    return {
        'hindi':    '#e67e22',
        'english':  '#27ae60',
        'punjabi':  '#8e44ad',
        'mathematics': '#2980b9',
        'science':  '#16a085',
        'history':  '#c0392b',
        'geography': '#d35400',
    }.get(s, '#2980b9')

# ═════════════════════════════════════════════════════════════════════════════
# Routes
# ═════════════════════════════════════════════════════════════════════════════

@app.route('/')
def home():
    db   = get_db()
    kids = list_kids(db)
    return render_template('home.html', kids=kids)


@app.route('/kid/<kid_name>')
def assessments_page(kid_name: str):
    db    = get_db()
    items = list_assessments(db, kid_name)
    return render_template('assessments.html', kid=kid_name, assessments=items)


@app.route('/kid/<kid_name>/assessment/<assessment_id>')
def quiz_page(kid_name: str, assessment_id: str):
    db         = get_db()
    assessment = load_assessment(db, kid_name, assessment_id)
    if not assessment:
        abort(404)
    assessment_json = json.dumps(assessment, ensure_ascii=False)
    return render_template('quiz.html',
                           kid=kid_name,
                           assessment=assessment,
                           assessment_json=assessment_json)


# ── API ───────────────────────────────────────────────────────────────────────

@app.route('/api/answer/mcq', methods=['POST'])
def api_mcq():
    """Save an MCQ answer and persist to Firestore."""
    data          = request.get_json(force=True)
    kid           = data.get('kid', '')
    assessment_id = data.get('assessmentId', '')
    answers       = data.get('answers', [])

    db      = get_db()
    has_any = any(_is_answered(a) for a in answers if isinstance(a, dict))
    status  = 'in_progress' if has_any else 'ready'
    try:
        save_assessment_progress(db, kid, assessment_id, answers, status)
    except Exception as e:
        return jsonify({'ok': False, 'error': str(e)}), 500
    return jsonify({'ok': True})


@app.route('/api/answer/validate', methods=['POST'])
def api_validate():
    """Validate a text answer with Gemini and save to Firestore."""
    data          = request.get_json(force=True)
    kid           = data.get('kid', '')
    assessment_id = data.get('assessmentId', '')
    q_idx         = int(data.get('questionIndex', 0))
    question      = data.get('question', {})
    child_answer  = str(data.get('answer', '')).strip()
    assessment_meta = data.get('assessmentMeta', {})
    retry_used    = bool(data.get('retryUsed', False))

    if not child_answer:
        return jsonify({'error': 'Answer cannot be empty.'}), 400

    # Fast exact-match path (no Gemini call needed)
    correct_ans = question.get('answer', '')
    if correct_ans and _is_exact_match(child_answer, correct_ans):
        result = {
            'verdict':         'correct',
            'earnedMarks':     question.get('marks', 1.0),
            'feedback':        '✓ Spot on! That matches the correct answer perfectly.',
            'corrections':     [],
            'correctedAnswer': '',
        }
    else:
        try:
            result = validate_text_answer(question, child_answer, assessment_meta)
        except Exception as e:
            return jsonify({'error': f'Gemini validation failed: {e}'}), 500

    # Persist answer to Firestore
    try:
        db         = get_db()
        assessment = load_assessment(db, kid, assessment_id)
        if assessment:
            answers = list(assessment['answers'])
            verdict   = result.get('verdict', 'wrong')
            selfGrade = 'got_it' if verdict == 'correct' else ('partial' if verdict == 'partial' else 'wrong')
            ans_map = {
                'questionId':     question.get('id', ''),
                'type':           question.get('type', ''),
                'textAnswer':     child_answer,
                'selfGrade':      selfGrade,
                'earnedMarks':    result['earnedMarks'],
                'aiEarnedMarks':  result['earnedMarks'],
                'corrections':    result.get('corrections', []),
                'correctedAnswer': result.get('correctedAnswer', ''),
                'aiFeedback':     result.get('feedback', ''),
                'retryUsed':      retry_used,
            }
            while len(answers) <= q_idx:
                answers.append(None)
            answers[q_idx] = ans_map
            has_any = any(_is_answered(a) for a in answers if isinstance(a, dict))
            save_assessment_progress(db, kid, assessment_id, answers,
                                     'in_progress' if has_any else 'ready')
    except Exception as e:
        # Non-fatal — return result even if Firestore write fails
        result['saveError'] = str(e)

    return jsonify(result)


@app.route('/api/finish', methods=['POST'])
def api_finish():
    """Mark assessment complete, compute score, generate summary."""
    data          = request.get_json(force=True)
    kid           = data.get('kid', '')
    assessment_id = data.get('assessmentId', '')
    answers       = data.get('answers', [])

    db         = get_db()
    assessment = load_assessment(db, kid, assessment_id)
    if not assessment:
        return jsonify({'error': 'Assessment not found.'}), 404

    assessment['answers'] = answers
    earned, total = compute_score(assessment['questions'], answers)
    score = round((earned / total * 100) if total > 0 else 0, 1)

    try:
        summary = generate_summary(assessment, answers, score)
    except Exception as e:
        summary = f"Assessment completed! You scored {int(round(score))}%. Keep up the great work!"

    try:
        mark_assessment_complete(db, kid, assessment_id, answers, score, summary)
    except Exception as e:
        return jsonify({'error': str(e)}), 500

    return jsonify({
        'ok':      True,
        'score':   score,
        'earned':  earned,
        'total':   total,
        'summary': summary,
    })

# ═════════════════════════════════════════════════════════════════════════════
# Entry point
# ═════════════════════════════════════════════════════════════════════════════

if __name__ == '__main__':
    sa  = _find_sa()
    print()
    print('🎓  Mynoo Web Companion — Assessment Player')
    print('━' * 48)
    print(f'   Firebase SA : {sa or "NOT FOUND (expected: " + str(_SA_CANDIDATES[0]) + ")"}')
    print(f'   Gemini key  : {"✓ loaded" if GEMINI_API_KEY else "✗ missing — check local.properties"}')
    print(f'   Gemini model: {GEMINI_MODEL}')
    print()
    print('📌  Open in your browser → http://localhost:8080')
    print()
    app.run(host='0.0.0.0', port=8080, debug=False)
