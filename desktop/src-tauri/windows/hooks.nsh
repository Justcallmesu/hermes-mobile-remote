; Hermes Remote NSIS hooks (ticket 34, D12). The app does the work; NSIS only calls it.

; Before removing files: stop the bridge, remove autostart, the old Scheduled Task and the firewall rule (one UAC).
; "Delete app data" in the uninstaller maps to --remove-data. Hermes' own .env is never touched.
!macro NSIS_HOOK_PREUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
    ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall --remove-data'
  ${Else}
    ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall'
  ${EndIf}
!macroend
