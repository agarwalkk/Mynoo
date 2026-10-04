# Mynoo Web Companion — Assessment Player

A lightweight browser-based assessment player for the Mynoo app.  
Runs on any Windows PC — no Android device or emulator required.

---

## Quick Start (This PC)

The repo already has a Python `.venv` with all dependencies installed. Just run:

```
web\start.cmd
```

Then open **http://localhost:8080** in your browser.

---

## Starting on a Different Windows PC

### Step 1 — Prerequisites
- Python 3.9 or newer: https://python.org/downloads  
  ✅ During install, tick **"Add Python to PATH"**

### Step 2 — Copy the repo
Copy this entire `Mynoo` folder to the new PC.

### Step 3 — Double-click `web\start.cmd`
The script will automatically:
1. Create a `.venv` (first run only, ~30 seconds)
2. Install Flask, firebase-admin, google-genai
3. Check that `mynoo-1e880-serviceaccount.json` exists
4. Start the server

### Step 4 — Open the browser
Navigate to **http://localhost:8080**

---

## Files you must have at the repo root

| File | Purpose |
|------|---------|
| `mynoo-1e880-serviceaccount.json` | Firebase access (read kids, read/write assessments) |
| `local.properties` | Contains `GEMINI_API_KEY` for AI answer grading |

---

## What the companion supports

| Feature | Status |
|---------|--------|
| Kid profile selector | ✅ |
| Assessment list (pending / completed) | ✅ |
| MCQ — two-attempt grading (half marks on retry) | ✅ |
| Short answer (AI graded via Gemini) | ✅ |
| Fill in the blank (inline inputs, AI graded) | ✅ |
| Jumbled sentence (click-to-place word tiles) | ✅ |
| Match the columns (click-to-match) | ✅ |
| Transformation / Error correction / Translation | ✅ |
| Equation balancing / Reaction identification | ✅ |
| **Paste blocked on all text answer fields** | ✅ |
| Progress saved to Firebase Firestore live | ✅ |
| Resume from where you left off | ✅ |
| AI-generated performance summary on finish | ✅ |

---

## Manual start (without start.cmd)

```powershell
# From repo root, with .venv active:
.\.venv\Scripts\Activate.ps1
pip install flask firebase-admin google-genai
python web\server.py
```

---

## Starting Automatically When Windows Starts

You can configure Mynoo Web Companion to start automatically in the background whenever Windows boots or logs in.

### Method 1 — 1-Click Startup (Recommended · No Admin Rights Required)

This runs the server silently in the background via `pythonw.exe` (no black terminal window pops up).

1. Double-click:
   ```
   web\install-autostart.cmd
   ```
2. That's it! Every time Windows starts, the companion is live at **http://localhost:8080**.

**To stop & remove auto-start:**
Double-click:
```
web\uninstall-autostart.cmd
```

---

### Method 2 — Windows Task Scheduler (Runs as Administrator)

To register a background scheduled task via PowerShell (Run as Administrator):

```powershell
# Open PowerShell as Administrator, navigate to repo folder:
cd C:\Apps\Mynoo

$action = New-ScheduledTaskAction `
    -Execute "$PWD\.venv\Scripts\pythonw.exe" `
    -Argument "web\server.py" `
    -WorkingDirectory "$PWD"

$trigger = New-ScheduledTaskTrigger -AtLogOn

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask `
    -TaskName "MynooWebCompanion" `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Mynoo Web Companion Assessment Server" `
    -Force
```

**To remove the scheduled task:**
```powershell
Unregister-ScheduledTask -TaskName "MynooWebCompanion" -Confirm:$false
```

---

### Method 3 — True Windows Service via NSSM (Runs Before User Login)

If you want Mynoo to run as an official Windows Service visible in `services.msc` that starts even before anyone logs into Windows:

1. Download NSSM (Non-Sucking Service Manager) from https://nssm.cc/download (free, open source) and extract `nssm.exe` to a folder in your PATH (e.g. `C:\Windows\System32` or `C:\Apps\Mynoo\bin`).
2. Open **Command Prompt as Administrator** and run:
   ```cmd
   nssm install MynooWebCompanion "C:\Apps\Mynoo\.venv\Scripts\python.exe" "web\server.py"
   nssm set MynooWebCompanion AppDirectory "C:\Apps\Mynoo"
   nssm set MynooWebCompanion Description "Mynoo Web Companion Assessment Player"
   nssm set MynooWebCompanion Start SERVICE_AUTO_START
   nssm start MynooWebCompanion
   ```
3. The service is now running. You can manage it via:
   - `net start MynooWebCompanion` / `net stop MynooWebCompanion`
   - Or open Windows **Services** (`services.msc`) → find **MynooWebCompanion**

**To remove the NSSM service:**
```cmd
nssm stop MynooWebCompanion
nssm remove MynooWebCompanion confirm
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `Python not found` | Install Python 3.9+, check "Add to PATH" |
| `Firebase service account not found` | Ensure `mynoo-1e880-serviceaccount.json` is in repo root |
| `GEMINI_API_KEY missing` | Add key to `local.properties` at repo root |
| `port 8080 already in use` | Edit `server.py` last line: change `port=8080` to another port |
| AI validation shows error | Check Gemini API key quota at https://aistudio.google.com |
