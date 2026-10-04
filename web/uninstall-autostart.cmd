@echo off
:: Mynoo Web Companion — Auto-start Uninstaller
:: Removes the auto-start shortcut from the Windows Startup folder.

echo.
echo  ======================================================
echo    Mynoo Web Companion - Remove Auto-Start
echo  ======================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$scPath = Join-Path ([System.Environment]::GetFolderPath('Startup')) 'MynooWebCompanion.lnk'; " ^
    "if (Test-Path $scPath) { " ^
    "    Remove-Item $scPath -Force; " ^
    "    Write-Host '  [OK] Removed autostart shortcut:' $scPath; " ^
    "} else { " ^
    "    Write-Host '  [INFO] No autostart shortcut found.'; " ^
    "}"

echo.
echo  Mynoo Web Companion will no longer start automatically with Windows.
echo.
pause
