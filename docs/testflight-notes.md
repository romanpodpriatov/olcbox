# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about DNS on mobile networks, the two cellular interfaces some
phones have, and one honest message on the board. The interface itself is
unchanged.

WHAT CHANGED

• Name lookups now start with the resolvers of the network you are on — the
  carrier's own on cellular, the router's on Wi-Fi — with a public resolver
  behind them. Several mobile networks answer only their own servers and meet
  1.1.1.1 and 8.8.8.8 with silence; on those, every room timed out on cellular
  and worked on Wi-Fi. If the public resolvers go silent, the app remembers it
  and stops waiting on them for a while instead of paying the wait on every
  name a connection needs.
• When nobody can resolve a name, the message says which resolvers were asked
  and what each one said, rather than a timeout from one of them.
• Phones with two cellular interfaces — one carrying IPv6, the other IPv4 —
  had IPv4 pinned to the IPv6 one, and the connection died with "no route to
  host" and "network is unreachable". Each socket now takes the interface that
  carries its own family. (Thanks to the contributor who caught this on an
  iPhone 16 Pro Max.)
• A link that asks for a transport its provider cannot carry — vp8channel on a
  Jitsi room — used to import silently as DataChannel and then wait twenty
  seconds for a peer. The card now says LINK ASKS FOR VP8 · JITSI RUNS
  DATACHANNEL, and pressing connect explains what to change on the server.
• The log you can share gained a network-diagnostics section: which interface
  each family was pinned to, path changes, and how many resolvers the network
  offered. Interface names and counts only; no addresses.

WHAT TO TEST

1. Cellular only, Wi-Fi OFF: join a Telemost room, then a Jitsi room. This is
   the case that failed with "i/o timeout" before. If it still fails, share the
   log from Diagnostics before relaunching, and tell us the carrier.
2. Whitelisted mobile networks (MTS "white lists"): Telemost should connect.
   A self-hosted Jitsi that the carrier's own DNS refuses cannot, and the
   message should now say so in the "host resolver" half.
3. Dual-SIM phones, and any phone that showed "no route to host" on cellular:
   join with Wi-Fi off, each SIM in turn.
4. Handover: connect on Wi-Fi, turn Wi-Fi off while connected, then back on.
   Within a minute it should be back each time.
5. If you run your own olcRTC on Jitsi with transport vp8channel: import its
   link. Expect the notice on the card and a message on connect, not a hang.
6. Ten minutes connected with something streaming; note any pause longer than
   a few seconds.

KNOWN AND EXPECTED

• Background energy stays "High" in Xcode's report: a tunnel keeping a
  connection alive, not a bug.
• "KEY NO LONGER VALID · REFRESH THIS LIST" on a card from a server list: the
  refresh button on the list header fixes it.
• A whitelisted network resolves and reaches only what the carrier allows. A
  Jitsi outside that list will not work there in any build.

Please report anything that looks wrong with a screenshot — and, for a
connection that did not come up or dropped, the shared log file.
