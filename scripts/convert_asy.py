"""
convert_asy.py
==============
Standalone script to convert Asymptote (.asy) code strings or files into SVG diagrams.

Usage:
  # 1. Convert q1 from assessment.json (default when run without arguments):
  python scripts/convert_asy.py

  # 2. Convert Asymptote code string directly:
  python scripts/convert_asy.py "size(120); pair O=(2,2); draw((0,0)--(4,4)); dot(O); label(\"O\", O, S);"

  # 3. Convert an .asy file:
  python scripts/convert_asy.py diagram.asy -o my_diagram.svg
"""

import sys
import os
import json
import re
import argparse
import subprocess
import tempfile
import shutil
from pathlib import Path

# Fix Windows console UTF-8 output encoding
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

DEFAULT_Q1_ASY = (
    r'size(120); pair O=(2,2); pair A=(0,2.5); pair B=(4,1.5); pair C=(1,0); pair D=(3,4); '
    r'draw(A--B, black+1pt); draw(C--D, black+1pt); dot(O); label("O", O, S); '
    r'draw(arc(O, 0.4, 26, 63)); label("$120^\circ$", O + 0.6*dir(45));'
)


def get_q1_code() -> tuple[str, str]:
    """Retrieves q1 Asymptote code from assessment.json if available, or returns fallback."""
    json_candidates = [
        Path(__file__).parent / "assessment.json",
        Path.cwd() / "scripts" / "assessment.json",
        Path.cwd() / "assessment.json",
    ]
    for jpath in json_candidates:
        if jpath.exists():
            try:
                data = json.loads(jpath.read_text(encoding="utf-8"))
                questions = data.get("questions", []) if isinstance(data, dict) else data
                for q in questions:
                    if q.get("id") == "q1":
                        asy_val = q.get("asy") or q.get("asymptote") or q.get("tikz")
                        if asy_val and str(asy_val).strip():
                            return str(asy_val).strip(), f"Question q1 ({jpath.name})"
            except Exception:
                pass
    return DEFAULT_Q1_ASY, "Question q1 (default sample)"


def find_asy_binary() -> Path | None:
    asy_candidates = [
        r"C:\Program Files\Asymptote\asy.exe",
        shutil.which("asy"),
        r"C:\Program Files (x86)\Asymptote\asy.exe",
        r"C:\Asymptote\asy.exe",
    ]
    for candidate in asy_candidates:
        if candidate and Path(candidate).exists():
            return Path(candidate)
    return None


def normalize_asy_backslashes(code: str) -> str:
    """Fix over-escaped LaTeX backslashes in Asymptote code.

    JSON files often contain doubly-escaped backslashes (e.g. \\\\circ instead
    of \\circ).  After json.loads() this results in \\\\circ in the Python
    string, but Asymptote / LaTeX needs exactly \\circ (one backslash).

    This collapses runs of 2+ backslashes before known LaTeX command names
    down to a single backslash.
    """
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
    """
    def _fix_match(m):
        expr = m.group(1).strip()
        if expr.startswith('(int)'):
            return m.group(0)
        return f'format("%d", (int)({expr}))'

    return re.sub(r'format\s*\(\s*"%d"\s*,\s*([^)]+)\)', _fix_match, code)


def sanitize_asy_code(code: str) -> str:
    """Apply all Asymptote code sanitization/normalization steps."""
    code = normalize_asy_backslashes(code)
    code = fix_asy_format_int_real(code)
    return code

def convert_code_to_svg(asy_code: str, output_path: Path | None = None) -> Path | None:
    asy_bin = find_asy_binary()
    if not asy_bin:
        print("❌ Asymptote binary (asy.exe) not found on system!")
        print("   Please ensure Asymptote is installed at 'C:\\Program Files\\Asymptote\\asy.exe' or in PATH.")
        return None

    # Environment setup matching verified test_libgs environment
    env = dict(os.environ)
    extra_paths = [
        r"C:\Program Files\Asymptote",
        r"C:\Users\agarw\AppData\Local\Programs\MiKTeX\miktex\bin\x64",
        r"C:\Program Files\gs\gs10.07.1\bin"
    ]
    env['PATH'] = ";".join(extra_paths) + ";" + env.get('PATH', '')
    env['LIBGS'] = r"C:\Program Files\gs\gs10.07.1\bin\gsdll64.dll"
    env['ASYMPTOTE_GS'] = r"C:\Program Files\gs\gs10.07.1\bin\gswin64c.exe"
    env['MIKTEX_ENABLE_INSTALL'] = '0'
    env['MIKTEX_AUTO_INSTALL'] = '2'
    env['MIKTEX_NONINTERACTIVE'] = '1'

    preamble = ""
    if "rightanglemark" in asy_code and "path rightanglemark" not in asy_code:
        preamble += (
            "path rightanglemark(pair A, pair B, pair C, real size=1) {\n"
            "    pair u = unit(A-B)*size;\n"
            "    pair v = unit(C-B)*size;\n"
            "    return B+u--B+u+v--B+v;\n"
            "}\n"
        )

    full_code = preamble + sanitize_asy_code(asy_code.strip())

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        asy_file = tmp_path / "diagram.asy"
        out_prefix = tmp_path / "diagram"
        asy_file.write_text(full_code, encoding="utf-8")

        print(f"⚙️ Running native Asymptote compiler: {asy_bin}")

        cmd = [str(asy_bin), "-f", "svg", "-o", str(out_prefix), str(asy_file)]

        try:
            res = subprocess.run(
                cmd,
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                env=env,
                timeout=15
            )

            svg_files = list(tmp_path.glob("*.svg"))

            if res.returncode == 0 and svg_files:
                target = output_path or Path.cwd() / "q1.svg"
                shutil.copy(svg_files[0], target)
                return target
            else:
                print(f"❌ Asymptote compilation failed with exit code {res.returncode}")
                if res.stdout:
                    print("  Stdout:", res.stdout.strip()[:300])
                if res.stderr:
                    print("  Stderr:", res.stderr.strip()[:300])
        except subprocess.TimeoutExpired:
            print("❌ Asymptote compilation timed out after 15s.")
            print("   This usually means LaTeX is hanging on malformed input.")
        except Exception as e:
            print(f"❌ Error during conversion: {e}")

    return None


def main():
    parser = argparse.ArgumentParser(
        description="Convert Asymptote (.asy) code or file to SVG. Defaults to q1 from assessment.json if no argument is given."
    )
    parser.add_argument("input", nargs="?", default=None, help="Asymptote code string OR path to .asy file (default: q1 from assessment.json)")
    parser.add_argument("-o", "--output", help="Output .svg file path (default: q1.svg)", default=None)

    args = parser.parse_args()

    if args.input:
        input_arg = args.input.strip()
        input_path = Path(input_arg)
        if input_path.exists() and input_path.is_file():
            print(f"📄 Reading Asymptote file: {input_path.name}")
            code = input_path.read_text(encoding="utf-8")
            out_target = Path(args.output) if args.output else input_path.with_suffix(".svg")
        else:
            code = input_arg
            out_target = Path(args.output) if args.output else Path.cwd() / "output.svg"
    else:
        code, source_label = get_q1_code()
        print(f"📄 Using Asymptote code from: {source_label}")
        print(f"   Code: {code}")
        out_target = Path(args.output) if args.output else Path.cwd() / "q1.svg"

    print("\n🎨 Converting Asymptote code to SVG...")
    result_file = convert_code_to_svg(code, out_target)

    if result_file and result_file.exists():
        svg_text = result_file.read_text(encoding="utf-8")
        print(f"\n✅ SUCCESS! SVG generated: {result_file.resolve()}")
        print(f"   Size: {result_file.stat().st_size} bytes")
        print("   SVG Preview:")
        print("   " + svg_text[:250].replace("\n", "\n   ") + "...\n")
    else:
        print("\n❌ Conversion failed.")
        sys.exit(1)


if __name__ == "__main__":
    main()
