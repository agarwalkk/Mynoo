@echo off
:: Mynoo Web Companion — Auto-start Installer
:: Registers the server to start silently in the background when Windows boots / logs in.

setlocal enabledelayedexpansion

cd /d "%~dp0.."
set "REPO_ROOT=%CD%"

echo.
echo  ======================================================
echo    Mynoo Web Companion - Windows Auto-Start Installer
echo  ======================================================
echo.

:: ── Locate pythonw.exe ────────────────────────────────────
set "PYTHONW="
if exist "%REPO_ROOT%\.venv\Scripts\pythonw.exe" (
    set "PYTHONW=%REPO_ROOT%\.venv\Scripts\pythonw.exe"
) else if exist "%REPO_ROOT%\venv\Scripts\pythonw.exe" (
    set "PYTHONW=%REPO_ROOT%\venv\Scripts\pythonw.exe"
) else (
    where pythonw >nul 2>&1
    if !errorlevel! equ 0 (
        for /f "delims=" %%i in ('where pythonw') do (
            if not defined PYTHONW set "PYTHONW=%%i"
        )
    )
)

if not defined PYTHONW (
    echo  [ERROR] pythonw.exe not found!
    echo          Please run web\start.cmd first to set up the virtual environment.
    echo.
    pause
    exit /b 1
)

echo  [OK] Found Python runner: %PYTHONW%
echo  [OK] Repo working dir  : %REPO_ROOT%

:: ── Create Startup Shortcut via PowerShell ────────────────
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$WshShell = New-Object -ComObject WScript.Shell; " ^
    "$startup = [System.Environment]::GetFolderPath('Startup'); " ^
    "$sc = $WshShell.CreateShortcut((Join-Path $startup 'MynooWebCompanion.lnk')); " ^
    "$sc.TargetPath = '%PYTHONW%'; " ^
    "$sc.Arguments = 'web\server.py'; " ^
    "$sc.WorkingDirectory = '%REPO_ROOT%'; " ^
    "$sc.Description = 'Mynoo Web Companion - Assessment Player'; " ^
    "$sc.Save(); " ^
    "Write-Host '  [OK] Shortcut created in Startup folder:' (Join-Path $startup 'MynooWebCompanion.lnk')"

if %errorlevel% neq 0 (
    echo  [ERROR] Failed to create startup shortcut.
    pause
    exit /b 1
)

echo.
echo  ------------------------------------------------------
echo  SUCCESS: Mynoo Web Companion is now set to start
echo  automatically in the background whenever Windows starts!
echo.
echo  Server will run at: http://localhost:8080
echo  To remove autostart, run: web\uninstall-autostart.cmd
echo  ------------------------------------------------------
echo.
pause
