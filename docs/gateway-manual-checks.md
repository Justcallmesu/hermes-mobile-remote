# Gateway sign-in: manual checks

Checks that need a person with a real phone and a real signed-in gateway (WorkLab,
`http://100.65.118.116:9119`, over Tailscale). Nothing here has been run yet. Tick a box only when you
have seen the result; leave it unticked if you did not run it.

Each line is: action → what you should see.

- [ ] Add host → **Sign in to a gateway** → type the WorkLab address → the gateway's login page opens in a Custom Tab → sign in → the app returns with a host named after the address.
- [ ] Send a message on the gateway host → the reply streams in token by token.
- [ ] Trigger a command approval → it appears on the phone → answer it there → the turn carries on and the question leaves the desktop too.
- [ ] Session list on the gateway host → sessions are listed → pin one and rename one; both changes show on the desktop app.
- [ ] Open a chat that has a picture → the picture loads.
- [ ] Turn on airplane mode during a session, then turn it off → the app reconnects and the chat is live again (a fresh ticket is used).
- [ ] Token expiry and refresh (wait, or revoke the session on the gateway) → chat keeps working, or the app shows **Sign in again** and the host stays in the list.
- [ ] Wrong password on the login page → the gateway refuses it and no new host appears in the app.
- [ ] Cancel in the browser (or the app's cancel button) → the host list is unchanged.
- [ ] Log out or remove the gateway host → the host is gone from the list and its tokens are gone (the app no longer uses them).
- [ ] On a gateway host, look for Scheduled, chat file download, and the project description field → none of them is there. Settings → Vault is there and lists your saved items (it runs over the same socket as chat).
- [ ] Bridge host still shows everything and still works after a gateway host is added.
- [ ] Switch between a bridge host and a gateway host → only one live connection is open at a time.
- [ ] Add host → **Pair a bridge** still pairs by QR code.
- [ ] Pair a bridge while a gateway host already exists → pairing works.
- [ ] After updating the app over an existing install, an existing bridge pairing still works (the Keystore key is unchanged).
- [ ] Browser that blocks the loopback redirect → note what the app shows. This is an open question; there is no fallback yet.
- [ ] Plain `http` to a public address → refused, with a reason shown.
- [ ] Real WorkLab responses for sessions, profiles, status and media match the shapes in `docs/GATEWAY-DESIGN.md` (W10–W13).
- [ ] The APK delivered via Telegram installs over the previous debug build.
