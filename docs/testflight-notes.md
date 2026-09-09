# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build adds a routing choice. Settings → Routing: "All traffic through the
tunnel" (what every build so far did) or "Bypass Russia". Under Bypass Russia,
Russian sites, .ru domains and your local network go straight out; everything
else — and every name lookup for it — rides the tunnel.

WHAT CHANGED

• Bypass Russia routes by three lists bundled with the app: v2fly's
  category-ru, the Russian top-level domains, and the Russian IP ranges.
  Nothing is downloaded; the lists ship inside the app.
• Names on those lists are resolved by the resolver of the network you were on
  when you connected. Everything else resolves through the tunnel, over TCP
  when the room is an olcRTC one.
• Changing the choice while connected reconnects.
• Name lookups through an olcRTC room no longer wait on the relay: a name
  bound for the tunnel gets an address at once and the exit resolves it, and
  the DNS the tunnel announces to the system is now its own, so iOS stops
  upgrading lookups to encrypted DNS at Cloudflare behind the tunnel's back.
  Pages through a room should start loading in a second or two rather than
  twenty.

WHAT TO TEST

1. Bypass Russia on, connect to any location. Open sberbank.ru, gosuslugi.ru,
   ozon.ru — they should open, and yandex.ru/internet should show your real
   address. whatismyip.com should show the exit's. (2ip.ru only works for
   this from inside Russia: from abroad it hands you to 2ip.io, which goes
   through the tunnel.)
2. Same, on an olcRTC room: Russian sites should be noticeably quicker than
   foreign ones — they no longer share the room's bandwidth.
3. Wi-Fi → cellular, or back, while connected with Bypass Russia on. Known
   limitation: Russian names may stop resolving until you reconnect (the
   resolver is the one captured at connect). Tell us if it happens and on
   which carrier.
4. Switch back to "All traffic through the tunnel": yandex.ru/internet should
   now show the exit's address too.
5. Memory: nothing should change, but if the tunnel drops on its own under
   Bypass Russia, say so — the extension's ceiling is the one thing the lists
   could push on.

