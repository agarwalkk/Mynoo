@echo off
:: Mynoo Web Companion — Windows Startup Script
:: Run this from the repo root: web\start.cmd

setlocal enabledelayedexpansion

cd /d "%~dp0.."
echo.
echo  ==========================================
echo    Mynoo Web Companion - Assessment Player
echo  ==========================================
echo.

:: ── Check for Python ──────────────────────────────────────────────
where python >nul 2>&1
if %errorlevel% neq 0 (
    where python3 >nul 2>&1
    if !errorlevel! neq 0 (
        echo  [ERROR] Python not found. Please install Python 3.9+ from https://python.org
        pause & exit /b 1
    )
    set PYTHON=python3
) else (
    set PYTHON=python
)
echo  [OK] Python found: & %PYTHON% --version

:: ── Check / activate virtual environment ─────────────────────────
if exist ".venv\Scripts\activate.bat" (
    echo  [OK] Using existing .venv
    call .venv\Scripts\activate.bat
) else if exist "venv\Scripts\activate.bat" (
    echo  [OK] Using existing venv
    call venv\Scripts\activate.bat
) else (
    echo  [INFO] No virtual environment found, creating one...
    %PYTHON% -m venv .venv
    call .venv\Scripts\activate.bat
)

:: ── Install / check dependencies ─────────────────────────────────
echo  [INFO] Checking web dependencies...
pip install -q flask firebase-admin google-genai 2>nul
if %errorlevel% neq 0 (
    echo  [WARN] pip install had warnings, continuing...
)

:: ── Check service account ─────────────────────────────────────────
if not exist "mynoo-1e880-serviceaccount.json" (
    echo.
    echo  [ERROR] Firebase service account not found.
    echo          Expected: mynoo-1e880-serviceaccount.json (repo root)
    echo.
    pause & exit /b 1
)

:: ── Check Gemini key ──────────────────────────────────────────────
if not exist "local.properties" (
    echo.
    echo  [WARN] local.properties not found. Gemini answer validation will fail.
    echo         Create local.properties with: GEMINI_API_KEY=your_key_here
    echo.
)

:: ── Launch ────────────────────────────────────────────────────────
echo.
echo  Starting server... Open your browser at:
echo    http://localhost:8080
echo.
echo  Press Ctrl+C to stop.
echo.
%PYTHON% web\server.py

endlocal
