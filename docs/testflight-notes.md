# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about one failure: "no route to host" when joining a room on a
mobile carrier, and the red message that could not be closed afterwards.

WHAT CHANGED

• Joining a room on cellular. Before dialing out, the app now checks that the
  network interface it is about to use can actually reach the internet. On
  some carriers it used to pick a cellular interface that has an address but
  no route — the one the phone keeps for VoLTE — and every connection died at
  once with "no route to host".
• A dial that finds no route is retried instead of ending the whole attempt on
  the first try. A phone moving between towers, or between Wi-Fi and cellular,
  sees exactly that error for a moment.
• The red box under the status strip can be closed: tap it. Pressing stop
  clears it too. Before, only relaunching the app removed it.
• Every attempt now writes one line naming the interface it used into the log
  you can share from the app. When a connect fails, that line is the first
  thing we read.

WHAT TO TEST

1. Cellular only. Turn Wi-Fi OFF and join a room. Try a few times. If it
   fails, open Diagnostics (the icon on the home screen) and share the log
   right away — before relaunching the app.
2. If you have a Megafon SIM, especially in St. Petersburg, test on it. This
   build exists because of that case.
3. Handover. Connect on Wi-Fi, then turn Wi-Fi off while connected, then back
   on. It should come back on its own within a minute each time.
4. The red message. Make it appear (Airplane Mode on, then try to join), then
   tap it — it should go away. Do it again and press stop instead of tapping.
5. Everything from the previous build still applies: the bar names the room
   you actually picked, and refreshing a list while connected leaves you where
   you were.

KNOWN AND EXPECTED

• The text inside the red box is still technical — a long line of addresses.
  Closing it is fixed; making it readable is next.
• Battery use in the background stays "High" in Xcode's energy report. That is
  the tunnel keeping a connection alive, not a bug.
• A card may say "KEY NO LONGER VALID · REFRESH THIS LIST". The refresh button
  on the list header fixes it.

Please report anything that looks wrong with a screenshot — and, for a
connection that did not come up, the shared log file.
