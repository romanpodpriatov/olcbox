# Google Play listing — copy, answers, and the declarations that decide it

Everything here is ready to paste. English only, for the same reason as the App
Store listing: the app has no translations, and a localised listing over an
untranslated app reads as bait.

Character limits are Google's and are enforced; counts in brackets are what the
text below actually uses.

---

## Store listing

**App name** (30 max) — [8]

```
ProofKit
```

**Short description** (80 max) — [74]

```
A client for your own VPN subscription. Reality, Hysteria2, XHTTP, olcRTC.
```

> This is the line shown in search results and it is the one most people read.
> It leads with "your own subscription" deliberately: the commonest complaint
> against clients like this is a user installing it expecting a VPN service.

**Full description** (4000 max)

```
ProofKit connects to a VPN subscription you already have.

It is a client, not a service: you bring a subscription link from your provider,
and the app turns it into a list of servers you can connect to. There is no
account to create here and nothing to sign up for.

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

Connecting routes the whole device through the exit you picked, using Android's
own VpnService. The status screen shows how long the session has been up and how
much has gone through it.

MEASURING

Latency is measured where it can honestly be measured: through the tunnel for
the connection you are on, and by ICMP or a TCP connect for a server you are not
connected to. Where neither is possible the app says so instead of showing a
number it guessed.

PRIVACY

Nothing is collected. No account, no analytics, no advertising identifier, no
crash reporting service. Your subscriptions, your server list and the app's own
log stay on the device and are never sent anywhere.

The app talks to exactly two kinds of address: your provider's subscription URL,
and the VPN servers in it. The one exception is a partner link that has to be
resolved into a subscription URL, and that request carries the link and nothing
about you.

Traffic is never redirected for advertising and never routed anywhere other than
the exit you chose.

REQUIREMENTS

A subscription from a VPN provider. ProofKit does not sell one and does not
include one. If you do not have a provider yet, the app can open proofkit.org,
where you can get a subscription for the ProofKit network.

OPEN SOURCE

github.com/romanpodpriatov/olcbox
```

**Category** — Tools. **Tags**: VPN, Privacy, Networking.

> Not "Communication" and not "Travel & Local", both of which VPN clients
> sometimes pick and both of which invite a category-mismatch review.

**Contact details** — support email, `https://proofkit.org` as the website,
`https://proofkit.org/privacy-policy` as the privacy policy.

> That last URL is the corrected one. `proofkit.org/privacy` serves the landing
> page: the site is a single-page app and answers 200 to any unknown path, so a
> wrong privacy URL does not look wrong, it looks like a marketing page — which
> is what a reviewer would have seen.

---

## Graphics

Play's sizes are its own; nothing from the App Store set fits.

| Asset | Size | Notes |
|---|---|---|
| App icon | 512 × 512 PNG, no alpha | 32-bit PNG, no transparency |
| Feature graphic | 1024 × 500 PNG/JPEG | Shown at the top of the listing. No essential text near the edges — it gets cropped in places |
| Phone screenshots | 2–8, min 1080 px on the short side, 16:9 or 9:16 | The same five states as the App Store set |

The five, in the order a new user meets the app:

1. **Empty state** — the three ways to add a subscription.
2. **The list** — a subscription expanded, protocol filter chips, one exit selected.
3. **Connected** — the dial on STOP, session timer, traffic counters.
4. **Subscription header** — provider name, quota, expiry, the two links.
5. **Subscription settings** — the switches, so it reads as configurable rather
   than a black box.

Two things to get right before pressing the shutter, same as last time: build
with the admin gate on, and use the demo subscription rather than a real one.

---

## Data safety

- **Does your app collect or share any of the required user data types?** → **No**

That ends the section. It is consistent with `PrivacyInfo.xcprivacy`, with the
App Store answers, and with what the app does.

> The two network calls the app makes are worth understanding rather than
> guessing at. Fetching a subscription sends its URL to the provider that issued
> it; resolving a partner link sends that link to proofkit.org. Neither carries a
> user identifier, and neither is one of Play's data types — but if analytics or
> crash reporting is ever added, this answer and the privacy manifest have to
> change in the same commit as the SDK.

**Data deletion** — nothing is collected, so there is nothing to request the
deletion of. Say so rather than leaving the URL field guessed at.

---

## App content

### VPN declaration — the one that actually decides this

- **Does your app use VpnService?** → **Yes**
- **Is VPN the app's core functionality?** → **Yes**
- **What data does the VPN service collect or transmit?** → None. The tunnel
  carries the user's traffic to the server in their own subscription. The app
  does not read, record or transmit its contents, its destinations, or DNS
  queries.
- **Does the app redirect or manipulate other apps' traffic for monetisation?**
  → **No.** There is no advertising in the app, no ad injection, no ad
  replacement, and no routing decision made for any reason other than the exit
  the user selected.

Two videos, 90 seconds each, unlisted on YouTube — **not private**, which a
reviewer cannot open:

1. Opening the app and using the VPN.
2. The prominent disclosure: the ordinary path to the screen, the whole text
   scrolled slowly enough to read, **declining** and what happens after it, and
   the screen being reached again. The decline path is the half that gets left
   out and the half that gets declarations returned.

> The disclosure screen itself is `VpnDisclosureScreen` in `commonMain`, shown
> before the first connection and before Android's own VPN prompt. It is its own
> screen and combined with no other consent, which the policy requires
> explicitly.

### App access

**Some functionality is restricted** — the app cannot be tested without a
subscription, and a reviewer who cannot connect cannot review.

```
ProofKit is a client for a VPN subscription the user already has. It does not
sell or include one, so it cannot be tested without a subscription link.

A working test subscription is below. Paste it into the app: tap + in the top
right, choose "Paste link or URI", then tap START.

Subscription link:
  <PASTE A LIVE LINK HERE>

The link carries several servers over different protocols. Any of them will
connect. Traffic is routed through Android's VpnService.

The app collects no data. There is no account and no sign-in. The camera is used
only to scan a subscription QR code, and only when the user taps that button.
```

> The link must be live on the day of review and for some days after. A
> subscription that expires mid-review fails it.

### The rest of App content

- **Ads** — the app contains no ads.
- **Content rating** — every question "No". The result is Everyone / PEGI 3.
  There is an argument for a higher rating on unrestricted internet access; the
  app has no browser and no content of its own, so this is defensible, and the
  rating can be changed without a new build.
- **Target audience** — 18+. Not because of content, but because a VPN client is
  not a product for children and a lower bracket brings Families policy
  requirements with it.
- **News app** → No. **Government app** → No. **Financial features** → None:
  payment happens entirely outside the app, so there is no in-app purchase and
  none of the external-billing programmes apply.

---

## Before the first upload

1. **Organisation account**, not personal. A personal account created after
   November 2023 must run a closed test with 12 testers for 14 consecutive days
   before it can reach production; organisations are exempt.
2. **App signing — choose "Use an existing key" and upload ours.** Play's default
   generates its own app signing key, which means the Play build and the
   sideloaded APK have different signatures and cannot update each other. Two
   channels that cannot cross is not what we want, and the choice is available
   **only when the app is created**. Commands for the `pepk` tool when you reach
   that screen.
3. Upload the **AAB**, not an APK — `ProofKit-<version>-android.aab`, built
   alongside the APKs since 1.0.240.
4. Keep publishing the APKs. Clients for circumventing blocks do get pulled from
   stores, and the day that happens is the wrong day to find the other channel
   had been dropped.
