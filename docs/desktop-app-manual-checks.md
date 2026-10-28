# Desktop app: manual checks

Things a test cannot see. Run them on the Windows PC and on the Linux PC before the pull request,
tick each box that passed, and write a short note next to any that failed. The Linux notes
refer to the desktop environment you ran them on.

Date: ______   Windows PC: ______   Linux PC (desktop): ______

## Windows

- [ ] Flyout looks right and sits at the taskbar corner on a **bottom** taskbar.
- [ ] Flyout looks right and sits at the taskbar corner on a **top** taskbar.
- [ ] Flyout looks right and sits at the taskbar corner on a **side** taskbar (left or right).
- [ ] Clicking anywhere else hides the flyout (focus loss).
- [ ] Right click menu: Open, Start/Stop bridge, Pair a device, Quit each do what they say.
- [ ] Firewall fix shows one UAC prompt; approving it adds the rule; cancelling shows "Not applied".
- [x] Pair a real phone: "Pair a device" shows the QR inline, the phone scans it and connects. *Windows 11, 2026-10-28: Galaxy Tab S8 paired over Tailscale and chatting (no firewall rule needed over Tailscale).*
- [ ] Upgrade from a script install: after setup the old PowerShell tray and the "Hermes Mobile Remote"
      Scheduled Task are gone; paired devices and trusted networks are still there (D12).
- [x] App crash = bridge down: end the app in Task Manager; the bridge stops and nothing restarts
      it; starting Hermes Remote from the Start menu brings it back (D6). *Windows 11, 2026-10-28: `taskkill /F` on the app: supervise, serve and helpers gone, port 8650 freed.*
- [ ] SmartScreen on the unsigned installer: "More info > Run anyway" appears and the per-user
      install needs no admin (D24).
- [ ] Start at logon is on: "Hermes Remote" is listed under Task Manager > Startup (D5).
- [ ] Uninstall from Settings > Apps: bridge stopped, autostart and firewall rule removed (one UAC
      prompt); config kept unless "Delete the application data" is ticked.

## Linux

- [ ] AppImage starts from the launcher with a normal window; .deb installs and does the same.
- [ ] Run mode *Always on*: closing the app leaves `hermes-remote-bridge.service` running.
- [ ] Run mode *Only while the app is open*: closing the app stops the bridge and disables the unit.
- [ ] Switch both ways (Always on -> Only while open -> Always on): the bridge answers after each switch.
- [ ] Start at login on and off: `systemctl --user is-enabled hermes-remote-bridge` matches the setting.
- [ ] Linger on ("Keep running after logout"): log out and the bridge still answers. Linger off: it stops.
- [ ] Linger refused by polkit: the app tells you to run `loginctl enable-linger` in a terminal (D33).
- [ ] GNOME without the AppIndicator extension: no tray icon, the window still works (D20).
- [ ] KDE tray menu: Open, Start, Stop and Quit each work (D43).
- [ ] First untrusted network: "Trust this network?" appears and the window opens. Count the prompts;
      two is the known Linux limit (D44).
- [ ] Real update from a `desktop-v*` release: the banner (AppImage) or the new-version link (.deb) works.

## Both

- [ ] Click through every tab: Home, Devices, Pair, Settings (doctor, firewall fix, start at logon,
      terminal shell, run mode on Linux, "what this install changed").
- [ ] Bridge not running: Devices and doctor still show saved data with "bridge not running, showing
      saved data" (D21).
- [x] First run: the change list is shown before anything is installed, and `INSTALLED.txt` is saved
      in the config directory (D11, D16). *Windows 11, 2026-10-28: list shown before Continue; INSTALLED.txt written; Run key set.*
- [ ] Phone's Terminal opens the expected shell (Windows: `pwsh`, else `powershell`, else `cmd`;
      `terminal_shell` overrides it).

## Result

Windows: ______________________________________________

Linux: ________________________________________________
