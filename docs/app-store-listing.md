# App Store listing — copy, answers, and the things review will ask for

Everything below is ready to paste. English only, which is deliberate: the app
has no translations, and a localised listing over an untranslated app reads as
a bait.

Field lengths are Apple's and are enforced; the counts in brackets are what the
text below actually uses.

---

## Name and identity

**App Name** (30 max) — [8]

```
ProofKit
```

> Check availability first. If `ProofKit` is taken, `ProofKit VPN` [12] is the
> fallback, and the bundle's display name should be changed to match so the
> home screen and the listing agree.

**Subtitle** (30 max) — [28]

```
Reality, Hysteria2 and XHTTP
```

**Category** — Primary: *Utilities*. Secondary: *Productivity*.

> Not "Social Networking" and not "Travel", both of which VPN clients sometimes
> pick and both of which invite a category-mismatch rejection.

---

## Promotional text (170 max) — [148]

Editable without a new build, so this is the line to change when something
ships.

```
A client for your own VPN subscription. Paste a link, pick an exit, connect.
Reality, Hysteria2, XHTTP and olcRTC — no account here, nothing logged.
```

---

## Description (4000 max)

```
ProofKit connects to a VPN subscription you already have.

It is a client, not a service: you bring a subscription link from your
provider, and the app turns it into a list of servers you can connect to. There
is no account to create here and nothing to sign up for.

WHAT IT SPEAKS

• VLESS with Reality
• VLESS over TLS, including through a CDN
• Hysteria2, with Salamander obfuscation
• XHTTP
• olcRTC — an encrypted transport that rides inside a video call

One subscription usually carries several of these. The app groups them by
provider, filters them by protocol, and remembers which exit you last used.

HOW IT WORKS

Add a subscription by pasting its link, scanning its QR code, or importing a
file. The app fetches the server list, shows what the provider says about your
plan — how much traffic is left, when it expires — and keeps it up to date on a
schedule you choose.

Connecting routes the whole device through the exit you picked, using Apple's
own packet tunnel. The status screen shows how long the session has been up and
how much has gone through it.

MEASURING

Latency is measured where it can honestly be measured: through the tunnel for
the connection you are on, and by ICMP echo for a server you are not connected
to. Where neither is possible the app says so instead of showing a number it
guessed.

PRIVACY

Nothing is collected. No account, no analytics, no advertising identifier, no
crash reporting service. Your subscriptions, your server list and the app's own
log stay on the device and are never sent anywhere.

The app talks to exactly two kinds of address: your provider's subscription URL,
and the VPN servers in it. The one exception is a partner link that has to be
resolved into a subscription URL, and that request carries the link and nothing
about you.

REQUIREMENTS

A subscription from a VPN provider. ProofKit does not sell one and does not
include one. If you do not have a provider yet, the app can open proofkit.org,
where you can get a subscription for the ProofKit network.

OPEN SOURCE

github.com/romanpodpriatov/olcbox
```

---

## Keywords (100 max, comma-separated, no spaces after commas) — [96]

```
vless,reality,hysteria2,xhttp,proxy,subscription,tunnel,sing-box,xray,client,socks,relay,privacy
```

> Do not repeat the app name or subtitle — Apple already indexes those, and the
> characters are scarce.

---

## What's New (first release)

```
First release.
```

---

## URLs

| Field | Value |
|---|---|
| Support URL | `https://proofkit.org/help` — **must resolve and must answer questions**; a 404 here is a rejection |
| Marketing URL | `https://proofkit.org` |
| Privacy Policy URL | `https://proofkit.org/privacy` — required, and required to match the App Privacy answers below |

---

## App Privacy answers

Every question, answered "no data collected", because that is what the app
does. This section is the one Apple checks against behaviour, so it must stay
true if analytics or crash reporting is ever added.

- **Do you or your third-party partners collect data from this app?** → **No**

That single answer ends the questionnaire. It is consistent with
`PrivacyInfo.xcprivacy`, which declares no collected data types, no tracking
domains, and one required-reason API (`NSUserDefaults`, `CA92.1`).

> If a crash reporter or analytics SDK is ever added, both this answer and the
> privacy manifest have to change in the same commit as the SDK. A mismatch
> between them is a rejection that arrives after the build is processed.

---

## Export compliance

Answered in `Info.plist` as `ITSAppUsesNonExemptEncryption = true`, so App Store
Connect stops asking per upload.

**Why true.** The exemptions cover apps whose encryption is the operating
system's, or HTTPS, or protects only credentials or DRM. This app is none of
those: it carries traffic inside Reality, Hysteria2 with Salamander, and
olcRTC's own encrypted transport, and it decrypts its own subscription links.

**What that obliges, once, at company level:**

1. A **BIS Encryption Registration Number (ERN)** — one form, one time,
   `snap-r` at BIS.
2. An **annual self-classification report** for **ECCN 5D992.c** (mass-market
   encryption software), emailed to BIS and the NSA each January for the
   preceding year.

**Questionnaire answers, when asked:**

- Uses encryption → **Yes**
- Qualifies for an exemption → **No**
- *"Does your app implement any proprietary encryption algorithms?"* → **No**.
  The question is about algorithms, not protocols. Ours are AES,
  ChaCha20-Poly1305 and TLS; the protocols around them are bespoke, which is a
  different thing and not what is being asked.
- CCATS → none. Do not invent a number; the ERN plus self-classification is the
  route for 5D992.c.

---

## App Review notes — the part that actually decides this

Paste into "Notes" in App Store Connect:

```
ProofKit is a client for a VPN subscription the user already has. It does not
sell or include one, so it cannot be tested without a subscription link.

A working test subscription is below. Paste it into the app: tap + in the top
right, choose "Paste link or URI", then tap START.

Subscription link:
  <PASTE A LIVE LINK HERE>

The link carries several servers over different protocols. Any of them will
connect. Traffic is routed through Apple's NEPacketTunnelProvider.

The app collects no data. There is no account and no sign-in. The camera is
used only to scan a subscription QR code, and only when the user taps that
button.
```

> **This is the single most likely cause of a rejection.** A reviewer who
> cannot connect cannot review, and "we could not test the app's core
> functionality" is 2.1. The link must be live on the day of review and for
> some days after — a subscription that expires mid-review fails it.

---

## Age rating

All questions "None". The result is 4+.

> There is an argument for 17+ on "Unrestricted Web Access", and some VPN apps
> carry it. This app has no browser and no content of its own — it routes the
> system's traffic — so 4+ is defensible. If review disagrees, changing the
> rating does not need a new build.

---

## Screenshots

Required: 6.9" (iPhone 17 Pro Max or equivalent). Everything else Apple scales.

Five, in this order, because it is the order a new user meets the app:

1. **Empty state** — "Add relay setup", the three ways to add a subscription.
2. **The list** — a subscription expanded, protocol filter chips visible, one
   exit selected.
3. **Connected** — the lime dial on STOP, session timer running, traffic
   counters underneath.
4. **Subscription header** — provider name, quota, expiry, the two links.
5. **Subscription settings** — the switches, so it is clear the app is
   configurable rather than a black box.

> Take them from a build with the admin gate **on**, so no configurator or
> "create custom location" is visible. A screenshot showing plumbing invites
> questions about what else is hidden.
>
> No device frames, no marketing text over the top: Apple allows both, and both
> date badly.
