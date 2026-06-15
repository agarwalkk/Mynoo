"""
upload_assessment.py
====================
Uploads a pre-built assessment JSON file to Firebase Firestore so it appears
as a 'ready' assessment for a child in the AaravTutor app.

Firestore path: kids/{child}/assessments/{auto-id}

Usage (from repo root, with venv activated):
  python scripts/upload_assessment.py --child Aarav --subject English --class 7 --file scripts/tenses_paper_fixed_80.json

Dry-run (validates JSON, no writes):
  python scripts/upload_assessment.py --child Aarav --subject English --class 7 --file scripts/tenses_paper_fixed_80.json --dry-run

Arguments:
  --child     Child's name as stored in Firestore (case-sensitive, e.g. "Aarav")
  --subject   Subject name (e.g. "English", "Hindi", "Mathematics")
  --class     Class number as string (e.g. "7")
  --file      Path to the assessment JSON file (relative to repo root or absolute)
  --dry-run   Validate and preview without writing to Firestore
"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

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
SERVICE_ACCOUNT  = _REPO_ROOT / 'aaravtutor-1e880-serviceaccount.json'

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

    with open(json_path, encoding='utf-8') as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            sys.exit(f'❌  Invalid JSON: {e}')

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


def _build_payload(
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

    # Inline passage text into each question that references one (mirrors app behaviour)
    enriched: list[dict] = []
    for q in questions:
        eq = dict(q)
        pid = str(eq.get('passageId', '')).strip()
        if pid and pid in passages and 'passage' not in eq:
            eq['passage'] = passages[pid]

        # Normalise MCQ options: the app stores options as string[] and explanations
        # separately in optionExplanations[].  If the JSON uses {text, explanation}
        # objects (common LLM output format) we split them here so the UI never sees
        # a raw object as a React child.
        if eq.get('type') == 'mcq' and isinstance(eq.get('options'), list):
            raw_opts = eq['options']
            flat_opts: list[str] = []
            expl_opts: list[str] = []
            for o in raw_opts:
                if isinstance(o, dict):
                    flat_opts.append(str(o.get('text') or o.get('option') or o.get('value') or ''))
                    expl_opts.append(str(o.get('explanation') or ''))
                else:
                    flat_opts.append(str(o))
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
    parser.add_argument('--child',    required=True, help='Child name in Firestore (e.g. "Aarav")')
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
        # Try relative to repo root first, then cwd
        candidate = _REPO_ROOT / json_path
        if candidate.exists():
            json_path = candidate

    questions, passages, file_title = _load_and_validate(json_path)

    subject   = args.subject.strip()
    class_num = args.class_num.strip()
    lang      = _lang_for_subject(subject)
    total_marks = sum(float(q.get('marks', 1)) for q in questions)
    # --title flag takes priority; fall back to title embedded in JSON file
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

    payload = _build_payload(
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
