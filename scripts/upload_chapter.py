"""
upload_chapter.py
=================
Uploads a chapter content.json to Firebase Storage and writes the chapter
metadata to Firestore.  No audio is generated or uploaded.

Storage path : classes/{class}/{subject}/{chapterId}/content.json
Firestore    : classes/{class}/subjects/{subject}/chapters/{chapterId}

Usage (from repo root, with venv activated):
  python scripts/upload_chapter.py \\
      --subject english --class 7 \\
      --order 2 \\
      --file scripts/sample_chapter.json

Dry-run (validates JSON, no writes):
  ... same args ... --dry-run

Arguments:
  --subject        Subject slug (e.g. "english", "hindi", "punjabi")
  --class          Class number as string (e.g. "7")
  --order          Integer display order within subject (e.g. 2)
  --file           Path to content.json (relative to repo root or absolute)
  --dry-run        Validate and preview without writing anything

JSON must contain top-level:
  "title" — human-readable chapter title (e.g. "The Rebel")
  Chapter ID is auto-built as ch{order:02d}-{slug} (e.g. order=2, title="The Rebel" → "ch02-the-rebel").
  Language is auto-detected from --subject (hindi→hi, punjabi→pa, else en).
"""

import argparse
import json
import re
import sys
import urllib.parse
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
STORAGE_BUCKET  = 'aaravtutor-1e880.firebasestorage.app'
SERVICE_ACCOUNT = _REPO_ROOT / 'mynoo-1e880-serviceaccount.json'

# Subject slug → language code (matches app logic)
SUBJECT_LANG: dict[str, str] = {
    'hindi':   'hi',
    'punjabi': 'pa',
}

# Paragraph types that have no playable audio / are decorative
NO_AUDIO_TYPES = {'table', 'media', 'assessment'}

# All recognised paragraph types
VALID_PARA_TYPES = {
    'prose', 'heading', 'attribution', 'verse', 'blockquote',
    # Science / textbook types
    'subheading', 'list', 'table', 'equation', 'activity', 'note', 'callout',
    # Tappable media cards (video / photo links)
    'media',
    # Interactive assessments
    'assessment',
}


# ── Helpers ────────────────────────────────────────────────────────────────────

def _subject_slug(subject: str) -> str:
    """Lowercase, underscores — matches chapterContent.ts subjectSlug()."""
    return subject.strip().lower().replace(' ', '_')


def _lang_for_subject(subject_slug: str) -> str:
    return SUBJECT_LANG.get(subject_slug, 'en')


def _validate_content(data: dict) -> list[str]:
    """Return validation error strings (empty list = OK).

    Format rules (matches LLM prompt spec):
      heading / attribution  — must have "text"
      prose / blockquote     — must have "sentences" array (NOT paragraph-level text)
      verse                  — must have "text" + "meaning" (required for verse)
    """
    errors: list[str] = []

    if not isinstance(data, dict):
        return ['Root must be a JSON object with a "paragraphs" key.']

    paragraphs = data.get('paragraphs')
    if not isinstance(paragraphs, list) or len(paragraphs) == 0:
        return ['"paragraphs" must be a non-empty array.']

    # title is optional at top level but warn if missing
    if not data.get('title'):
        errors.append('Missing top-level "title" field (required).')

    ids_seen: set[str] = set()
    all_sent_ids: set[str] = set()

    for i, p in enumerate(paragraphs):
        pid   = p.get('id', '')
        label = f'paragraphs[{i}] (id={pid or "?"})'

        if not pid:
            errors.append(f'{label}: missing "id"')
        elif pid in ids_seen:
            errors.append(f'{label}: duplicate paragraph id "{pid}"')
        else:
            ids_seen.add(pid)

        ptype = str(p.get('type', 'prose')).strip().lower()
        if ptype not in VALID_PARA_TYPES:
            errors.append(f'{label}: unknown type "{ptype}" — must be one of {sorted(VALID_PARA_TYPES)}')
            continue

        if ptype in ('heading', 'attribution', 'subheading'):
            # Must have paragraph-level text, no sentences
            if not str(p.get('text', '')).strip():
                errors.append(f'{label}: type "{ptype}" must have a non-empty "text" field')
            if p.get('sentences'):
                errors.append(f'{label}: type "{ptype}" must NOT have a "sentences" array')

        elif ptype == 'verse':
            # Paragraph-level text, no sentences; meaning is required
            if not str(p.get('text', '')).strip():
                errors.append(f'{label}: verse must have a non-empty "text" field')
            if p.get('sentences'):
                errors.append(f'{label}: verse must NOT have a "sentences" array (use "text" for the full stanza)')
            if not str(p.get('meaning', '')).strip():
                errors.append(f'{label}: verse requires a "meaning" field')

        elif ptype == 'equation':
            # Single text block (LaTeX or plain), no sentences
            if not str(p.get('text', '')).strip():
                errors.append(f'{label}: type "equation" must have a non-empty "text" field')
            if p.get('sentences'):
                errors.append(f'{label}: type "equation" must NOT have a "sentences" array')

        elif ptype == 'list':
            # Support both sentences (new format) and items (old format)
            items = p.get('items')
            sentences = p.get('sentences')
            if items is not None and sentences is not None:
                errors.append(f'{label}: type "list" cannot have both "items" and "sentences"')
            elif items is not None:
                if not isinstance(items, list) or len(items) == 0:
                    errors.append(f'{label}: type "list" must have a non-empty "items" array')
                else:
                    for k, item in enumerate(items):
                        if not str(item).strip():
                            errors.append(f'{label}.items[{k}]: item text is empty')
            elif sentences is not None:
                if not isinstance(sentences, list) or len(sentences) == 0:
                    errors.append(f'{label}: type "list" must have a non-empty "sentences" array')
                else:
                    for j, s in enumerate(sentences):
                        slabel = f'{label}.sentences[{j}]'
                        sid = s.get('id', '')
                        if not sid:
                            errors.append(f'{slabel}: missing "id"')
                        elif sid in all_sent_ids:
                            errors.append(f'{slabel}: duplicate sentence id "{sid}" (must be unique across the chapter)')
                        else:
                            all_sent_ids.add(sid)
                        if not str(s.get('text', '')).strip():
                            errors.append(f'{slabel}: "text" is empty')
            else:
                errors.append(f'{label}: type "list" must have either "items" or "sentences"')

            if str(p.get('text', '')).strip():
                errors.append(f'{label}: type "list" must NOT have a "text" field')

        elif ptype == 'table':
            # rows is required (2-D array of cells), headers optional
            rows = p.get('rows')
            if not isinstance(rows, list) or len(rows) == 0:
                errors.append(f'{label}: type "table" must have a non-empty "rows" array')
            else:
                for r, row in enumerate(rows):
                    if not isinstance(row, list) or len(row) == 0:
                        errors.append(f'{label}.rows[{r}]: each row must be a non-empty array of cells')
                    else:
                        for c, cell in enumerate(row):
                            if isinstance(cell, str):
                                continue
                            elif isinstance(cell, dict):
                                if 'value' not in cell:
                                    errors.append(f'{label}.rows[{r}][{c}]: cell object is missing the "value" key')
                                elif not isinstance(cell['value'], str):
                                    errors.append(f'{label}.rows[{r}][{c}]: cell "value" must be a string')
                                if 'colorHint' in cell and cell['colorHint'] is not None and not isinstance(cell['colorHint'], str):
                                    errors.append(f'{label}.rows[{r}][{c}]: cell "colorHint" must be a string')
                            else:
                                errors.append(f'{label}.rows[{r}][{c}]: cell must be a string or a cell object with a "value" key')
            headers = p.get('headers')
            if headers is not None and not isinstance(headers, list):
                errors.append(f'{label}: "headers" must be an array of strings')
            if p.get('sentences') or str(p.get('text', '')).strip():
                errors.append(f'{label}: type "table" must NOT have "text" or "sentences"')

        elif ptype == 'media':
            # items[] of media objects, each with mediaType + url + caption
            items = p.get('items')
            if not isinstance(items, list) or len(items) == 0:
                errors.append(f'{label}: type "media" must have a non-empty "items" array')
            else:
                valid_media_types = {'video', 'photo'}
                for k, item in enumerate(items):
                    mlabel = f'{label}.items[{k}]'
                    if not isinstance(item, dict):
                        errors.append(f'{mlabel}: each media item must be an object'); continue
                    mt = str(item.get('mediaType', '')).strip().lower()
                    if mt not in valid_media_types:
                        errors.append(f'{mlabel}: "mediaType" must be "video" or "photo", got "{mt}"')
                    url = str(item.get('url', '')).strip()
                    if not url:
                        errors.append(f'{mlabel}: missing required "url" field')
                    elif not (url.startswith('http://') or url.startswith('https://') or url.startswith('pdf-image://')):
                        errors.append(f'{mlabel}: "url" must start with http://, https://, or pdf-image://')
                    if not str(item.get('caption', '')).strip():
                        errors.append(f'{mlabel}: missing required "caption" field')
            if p.get('sentences') or str(p.get('text', '')).strip():
                errors.append(f'{label}: type "media" must NOT have "text" or "sentences"')

        elif ptype == 'assessment':
            # Must have assessmentType, question, and meaning.
            # If assessmentType == 'mcq', must have options array.
            atype = str(p.get('assessmentType', '')).strip().lower()
            if atype not in ('mcq', 'short_answer'):
                errors.append(f'{label}: type "assessment" must have "assessmentType" as "mcq" or "short_answer", got "{atype}"')
            if not str(p.get('question', '')).strip():
                errors.append(f'{label}: type "assessment" must have a non-empty "question" field')
            if not str(p.get('meaning', '')).strip():
                errors.append(f'{label}: type "assessment" must have a non-empty "meaning" field (used for the solution explanation)')
            if atype == 'mcq':
                options = p.get('options')
                if not isinstance(options, list) or len(options) == 0:
                    errors.append(f'{label}: type "assessment" with "mcq" must have a non-empty "options" array')
                else:
                    for k, option in enumerate(options):
                        if not str(option).strip():
                            errors.append(f'{label}.options[{k}]: option text is empty')
            if p.get('sentences') or str(p.get('text', '')).strip():
                errors.append(f'{label}: type "assessment" must NOT have "text" or "sentences"')

        elif ptype in ('prose', 'blockquote', 'activity', 'callout', 'note'):
            # Sentence-level: must have sentences[], must NOT have paragraph-level text
            sentences = p.get('sentences')
            if not isinstance(sentences, list) or len(sentences) == 0:
                errors.append(f'{label}: type "{ptype}" must have a non-empty "sentences" array')
            else:
                for j, s in enumerate(sentences):
                    slabel = f'{label}.sentences[{j}]'
                    sid = s.get('id', '')
                    if not sid:
                        errors.append(f'{slabel}: missing "id"')
                    elif sid in all_sent_ids:
                        errors.append(f'{slabel}: duplicate sentence id "{sid}" (must be unique across the chapter)')
                    else:
                        all_sent_ids.add(sid)
                    if not str(s.get('text', '')).strip():
                        errors.append(f'{slabel}: "text" is empty')
            if str(p.get('text', '')).strip():
                errors.append(f'{label}: type "{ptype}" must use "sentences" array — remove the "text" field')

    return errors


def _count_paragraphs_and_sentences(paragraphs: list) -> tuple[int, int]:
    playable = sum(1 for p in paragraphs if p.get('type', 'prose') not in NO_AUDIO_TYPES)
    # Count audio segments:
    #   any type with "sentences" array → each sentence is a segment
    #   list → each sentence/item is a segment
    #   other types with "text" → the whole paragraph is one segment
    sentences = 0
    for p in paragraphs:
        ptype = p.get('type', 'prose')
        if ptype in NO_AUDIO_TYPES:
            continue
        sentences_list = p.get('sentences')
        if ptype == 'list':
            if isinstance(sentences_list, list) and sentences_list:
                sentences += len(sentences_list)
            elif isinstance(p.get('items'), list):
                sentences += len(p['items'])
        elif isinstance(sentences_list, list) and sentences_list:
            sentences += len(sentences_list)
        elif str(p.get('text', '')).strip():
            sentences += 1
    return playable, sentences


# ── Firebase upload ────────────────────────────────────────────────────────────

def _collect_sentence_ids(paragraphs: list) -> set[str]:
    """Return every audio-segment ID from the parsed paragraphs (mirrors Kotlin logic)."""
    ids: set[str] = set()
    for p in paragraphs:
        ptype = p.get('type', 'prose')
        if ptype in NO_AUDIO_TYPES:
            continue
        sentences = p.get('sentences')
        if ptype == 'list':
            if isinstance(sentences, list) and sentences:
                for s in sentences:
                    if s.get('id'):
                        ids.add(s['id'])
            elif isinstance(p.get('items'), list):
                for idx in range(len(p['items'])):
                    ids.add(f"{p['id']}-item-{idx}")
        elif isinstance(sentences, list) and sentences:
            for s in sentences:
                if s.get('id'):
                    ids.add(s['id'])
        elif str(p.get('text', '')).strip():
            if p.get('id'):
                ids.add(p['id'])
    return ids


def _check_audio_id_drift(
    bucket,
    storage_base: str,
    new_ids: set[str],
) -> bool:
    """List existing audio segment IDs in Storage and report additions / removals.

    Returns True if IDs match perfectly, False if there are mismatches.
    Mismatches are printed as warnings but never block the upload.
    """
    existing_blobs = list(bucket.list_blobs(prefix=f'{storage_base}/audio/'))
    # Derive IDs from blob names like …/audio/sentences/<id>.mp3 or …/audio/paragraphs/<id>.mp3
    # Ignore .json timing files — they share the same base ID.
    existing_ids: set[str] = set()
    for blob in existing_blobs:
        name = blob.name  # full path
        # Strip everything up to and including /audio/sentences/ or /audio/paragraphs/
        for folder in ('sentences', 'paragraphs'):
            marker = f'/audio/{folder}/'
            if marker in name:
                basename = name.split(marker, 1)[1]  # e.g. "s01-p02.mp3"
                stem = basename.rsplit('.', 1)[0]     # strip extension
                if stem:  # skip .json timing files that share the same stem? No — still add.
                    existing_ids.add(stem)
                break

    if not existing_ids:
        print('    ℹ️   No existing audio found — ID drift check skipped.')
        return True

    added   = new_ids - existing_ids   # in new JSON but no audio yet
    removed = existing_ids - new_ids   # audio exists but ID gone from new JSON

    if not added and not removed:
        print(f'    ✅  All {len(existing_ids)} audio segment IDs match the new JSON.')
        return True

    if added:
        print(f'    ⚠️   {len(added)} new sentence ID(s) with NO existing audio (will need re-generation):')
        for sid in sorted(added)[:20]:
            print(f'         + {sid}')
        if len(added) > 20:
            print(f'         … and {len(added) - 20} more')
    if removed:
        print(f'    ⚠️   {len(removed)} sentence ID(s) removed from JSON but audio still in Storage (orphaned):')
        for sid in sorted(removed)[:20]:
            print(f'         - {sid}')
        if len(removed) > 20:
            print(f'         … and {len(removed) - 20} more')
    return False


def _firebase_public_url(bucket_name: str, blob_path: str) -> str:
    """Return the public Firebase Storage download URL for a blob path."""
    encoded = urllib.parse.quote(blob_path, safe='')
    return f'https://firebasestorage.googleapis.com/v0/b/{bucket_name}/o/{encoded}?alt=media'


def upload_pdf_images(
    content_str: str,
    images_dir: 'Path | None',
    bucket,
    storage_base: str,
    dry_run: bool,
) -> str:
    """Upload PDF-extracted images to Firebase Storage and rewrite pdf-image:// URLs.

    Scans *content_str* for occurrences of ``pdf-image://<filename>`` and, for
    each unique filename found:
      1. Looks for the file in *images_dir*.
      2. Uploads it to ``{storage_base}/images/<filename>`` in Firebase Storage.
      3. Replaces the pdf-image:// token with the public Firebase Storage URL.

    Returns the modified JSON string.
    """
    # Find all pdf-image:// filenames referenced in the JSON
    refs = re.findall(r'pdf-image://([^\s"\']+)', content_str)
    unique_refs = dict.fromkeys(refs)  # preserve order, deduplicate

    if not unique_refs:
        return content_str

    if images_dir is None:
        print(f'⚠️   {len(unique_refs)} pdf-image:// reference(s) found but no images directory provided.')
        print(f'    Pass --images-dir to upload images and resolve URLs.')
        return content_str

    if not images_dir.is_dir():
        print(f'⚠️   {len(unique_refs)} pdf-image:// reference(s) found but images directory does not exist: {images_dir}')
        return content_str

    print(f'🖼   Uploading {len(unique_refs)} PDF image(s) from {images_dir} ...')
    for filename in unique_refs:
        img_path = images_dir / filename
        if not img_path.exists():
            print(f'    ⚠️  Image not found, skipping: {img_path}')
            continue
        blob_path = f'{storage_base}/images/{filename}'
        public_url = _firebase_public_url(STORAGE_BUCKET, blob_path)
        if not dry_run:
            content_type = 'image/png' if filename.endswith('.png') else 'image/jpeg'
            blob = bucket.blob(blob_path)
            blob.upload_from_filename(str(img_path), content_type=content_type)
            print(f'    ✅  {filename}  ({img_path.stat().st_size:,} bytes)')
        else:
            print(f'    (dry-run) would upload: {filename} → gs://{STORAGE_BUCKET}/{blob_path}')
        content_str = content_str.replace(f'pdf-image://{filename}', public_url)

    return content_str


def upload_to_firebase(
    content_bytes: bytes,
    subject_slug: str,
    class_num: str,
    chapter_id: str,
    chapter_title: str,
    chapter_order: int,
    lang: str,
    paragraph_count: int,
    sentence_count: int,
    dry_run: bool,
    preserve_audio: bool = False,
    paragraphs: list | None = None,
    images_dir: 'Path | None' = None,
) -> None:
    import os
    sa_path = os.environ.get('FIREBASE_SERVICE_ACCOUNT', str(SERVICE_ACCOUNT))
    if not Path(sa_path).exists():
        sys.exit(
            f'❌  Service account not found: {sa_path}\n'
            '    Generate one at Firebase Console → Project Settings → Service accounts.'
        )

    import firebase_admin
    from firebase_admin import credentials, firestore as fs_admin, storage as fb_storage

    if not firebase_admin._apps:
        cred = credentials.Certificate(sa_path)
        firebase_admin.initialize_app(cred, {'storageBucket': STORAGE_BUCKET})

    storage_base = f'classes/{class_num}/{subject_slug}/{chapter_id}'
    db = bucket = None  # assigned inside the non-dry-run branches below

    # 0. Remove any existing data for this order slot (regardless of title/ID)
    if preserve_audio:
        print('🔒  --preserve-audio: skipping deletion of audio files.')
        if not dry_run:
            db = fs_admin.client()
            bucket = fb_storage.bucket()
            subject_chapters_ref = (
                db.collection('classes').document(class_num)
                  .collection('subjects').document(subject_slug)
                  .collection('chapters')
            )
            # Check for old docs with same order but different ID (renamed chapter)
            old_docs = list(subject_chapters_ref.where('order', '==', chapter_order).stream())
            for old_doc in old_docs:
                if old_doc.id != chapter_id:
                    print(f'    ⚠️   Order {chapter_order} was previously chapter "{old_doc.id}" — '
                          f'Firestore doc will be replaced but its audio is left intact.')
                    old_doc.reference.delete()
                    print(f'    🗑️  Deleted old Firestore doc: {old_doc.id}')
            # ID-drift check: compare new sentence IDs vs existing audio files
            print('🔍  Comparing sentence IDs vs existing audio…')
            new_ids = _collect_sentence_ids(paragraphs or [])
            _check_audio_id_drift(bucket, storage_base, new_ids)
        else:
            print('    (dry-run: skipping Storage ID drift check)')
    else:
        print('🗑️   Checking for existing chapter data…')
        if not dry_run:
            db = fs_admin.client()
            bucket = fb_storage.bucket()
            subject_chapters_ref = (
                db.collection('classes').document(class_num)
                  .collection('subjects').document(subject_slug)
                  .collection('chapters')
            )
            # Find all docs with the same order number (catches old IDs with different title slugs)
            old_docs = subject_chapters_ref.where('order', '==', chapter_order).stream()
            for old_doc in old_docs:
                old_id = old_doc.id
                old_doc.reference.delete()
                print(f'    🗑️  Deleted Firestore doc: {old_id}')
                old_blobs = list(bucket.list_blobs(prefix=f'classes/{class_num}/{subject_slug}/{old_id}/'))
                if old_blobs:
                    for blob in old_blobs:
                        blob.delete()
                    print(f'    🗑️  Deleted {len(old_blobs)} Storage file(s) for {old_id}')
            # Also clean up by exact current chapter_id in case order field was missing
            exact_ref = subject_chapters_ref.document(chapter_id)
            exact_snap = exact_ref.get()
            if exact_snap.exists:
                exact_ref.delete()
                print(f'    🗑️  Deleted Firestore doc: {chapter_id}')
            exact_blobs = list(bucket.list_blobs(prefix=f'{storage_base}/'))
            if exact_blobs:
                for blob in exact_blobs:
                    blob.delete()
                print(f'    🗑️  Deleted {len(exact_blobs)} existing Storage file(s) for {chapter_id}')

    # 1a. Upload PDF-extracted images and rewrite pdf-image:// URLs in the JSON
    content_str = content_bytes.decode('utf-8')
    content_str = upload_pdf_images(
        content_str=content_str,
        images_dir=images_dir,
        bucket=bucket,
        storage_base=storage_base,
        dry_run=dry_run,
    )
    content_bytes = content_str.encode('utf-8')

    # 1b. content.json → Storage
    print('☁️   Uploading content.json to Storage…')
    if not dry_run:
        assert bucket is not None  # assigned in the non-dry-run branches above
        blob = bucket.blob(f'{storage_base}/content.json')
        blob.upload_from_string(content_bytes, content_type='application/json; charset=utf-8')
    print(f'    ✅  gs://{STORAGE_BUCKET}/{storage_base}/content.json  ({len(content_bytes):,} bytes)')

    # 2. Firestore chapter meta
    print('📄  Writing Firestore chapter meta…')
    if not dry_run:
        db = fs_admin.client()
        chapter_ref = (
            db.collection('classes').document(class_num)
              .collection('subjects').document(subject_slug)
              .collection('chapters').document(chapter_id)
        )
        chapter_ref.set({
            'title':          chapter_title,
            'order':          chapter_order,
            'lang':           lang,
            'subject':        subject_slug,
            'classNum':       class_num,
            'paragraphCount': paragraph_count,
            'sentenceCount':  sentence_count,
            'published':      True,
            'updatedAt':      fs_admin.SERVER_TIMESTAMP,  # type: ignore[attr-defined]
        }, merge=True)
    print(f'    ✅  classes/{class_num}/subjects/{subject_slug}/chapters/{chapter_id}')


# ── Main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description='Upload a chapter content.json to Firebase (no audio).',
    )
    parser.add_argument('--subject',  required=True, help='Subject slug (e.g. "english", "hindi")')
    parser.add_argument('--class',    dest='class_num', required=True, help='Class number (e.g. "7")')
    parser.add_argument('--order',    required=True, type=int, help='Display order within subject (e.g. 2)')
    parser.add_argument('--file',     required=True, help='Path to content.json (relative to repo root or absolute)')
    parser.add_argument('--dry-run',      action='store_true', help='Validate only — no writes')
    parser.add_argument('--preserve-audio', action='store_true',
                        help='Reupload content.json only — keep existing audio files intact. '
                             'Prints a warning if sentence IDs have changed.')
    parser.add_argument('--images-dir', '--images_dir', default='',
                        help='Directory containing PDF-extracted images (e.g. scripts/ch01-geo/images). '
                             'Auto-detected from --file if omitted and the default folder exists.')
    args = parser.parse_args()

    if args.dry_run:
        print('🔍  DRY RUN — nothing will be written.\n')
    if args.preserve_audio:
        print('🔒  PRESERVE AUDIO — existing audio files will NOT be deleted.\n')

    # Resolve file path
    json_path = Path(args.file)
    if not json_path.is_absolute():
        candidate = _REPO_ROOT / json_path
        if candidate.exists():
            json_path = candidate

    if not json_path.exists():
        sys.exit(f'❌  File not found: {json_path}')

    with open(json_path, encoding='utf-8') as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            sys.exit(f'❌  Invalid JSON: {e}')

    errors = _validate_content(data)
    if errors:
        print(f'❌  Validation failed with {len(errors)} error(s):')
        for err in errors:
            print(f'    • {err}')
        sys.exit(1)

    subject_slug  = _subject_slug(args.subject)
    class_num     = args.class_num.strip()
    lang          = _lang_for_subject(subject_slug)
    paragraphs    = data['paragraphs']
    para_count, sent_count = _count_paragraphs_and_sentences(paragraphs)

    chapter_title = str(data.get('title', '')).strip()
    if not chapter_title:
        sys.exit('❌  JSON is missing a top-level "title" field (e.g. "The Rebel").')

    # Build chapter ID from order + title slug (e.g. 2, "The Rebel" → "ch02-the-rebel")
    import re
    title_slug = re.sub(r'[^\w]+', '-', chapter_title.lower()).strip('-')
    chapter_id = f'ch{args.order:02d}-{title_slug}'

    print('📖  Chapter details')
    print(f'    Title   : {chapter_title}')
    print(f'    ID      : {chapter_id}')
    print(f'    Subject : {subject_slug} | Class {class_num} | Lang {lang} | Order {args.order}')
    print(f'    File    : {json_path.name}')
    print(f'    Paragraphs: {para_count} playable  |  Sentences: {sent_count}')
    print()
    print('✅  Validation passed.')

    if args.dry_run:
        print()
        print('📋  Payload preview (first 2 paragraphs):')
        preview = {'paragraphs': paragraphs[:2]}
        print(json.dumps(preview, indent=2, ensure_ascii=False))
        print()
        print('🔍  Dry run complete — no data written.')
        return

    # Resolve images directory (auto-detect: <json_stem>/images/ beside the JSON file)
    if args.images_dir:
        images_dir = Path(args.images_dir)
    else:
        images_dir = json_path.parent / json_path.stem / 'images'
        print(f'🖼   Using images directory: {images_dir}')

    content_bytes = json_path.read_bytes()
    upload_to_firebase(
        content_bytes=content_bytes,
        subject_slug=subject_slug,
        class_num=class_num,
        chapter_id=chapter_id,
        chapter_title=chapter_title,
        chapter_order=args.order,
        lang=lang,
        paragraph_count=para_count,
        sentence_count=sent_count,
        dry_run=False,
        preserve_audio=args.preserve_audio,
        paragraphs=paragraphs,
        images_dir=images_dir,
    )

    print()
    print('✅  Done!')
    print(f'   Storage  : gs://{STORAGE_BUCKET}/classes/{class_num}/{subject_slug}/{chapter_id}/content.json')
    print(f'   Firestore: classes/{class_num}/subjects/{subject_slug}/chapters/{chapter_id}')
    print(f'   Published: True  (chapter will appear in the app immediately)')


if __name__ == '__main__':
    main()
