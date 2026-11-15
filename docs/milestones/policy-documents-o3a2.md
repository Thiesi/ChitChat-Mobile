# Policy documents O3a2: the document screen and its entry points

Native consumers for [O3a1](policy-documents-o3a1.md), following
[play-readiness.md](../design/play-readiness.md) §1 "Document screen" and "Legal notice" (P-176, P-173). Released ChitChat
v3.10.0 [docs/api/policies.md](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/policies.md). Rules acceptance
and the Create-account consent line are O3b.

## Reading

`PolicyReads.policyDocument(name, expected)`, implemented by `SessionRepository`, makes one public
`policies/show.php` read. It works signed in or signed out.
- It's fenced to the rendered session's server and generation: a missing server or a change during the read is a
  typed EXPIRED failure, so the screen offers Try again rather than staying on Loading.
- It's refused while the server-update state shows (nothing new starts, [server-update check](server-update-check.md)).

`PolicyDocumentViewModel` holds one document in memory while its screen is open. Retry is a fresh GET. A real departure
or a session change drops the document; a configuration change keeps it. Nothing is cached on disk.

## The document screen

It's a pushed screen with Back. The title is "{installation}'s rules" or "{installation}'s privacy policy"; the
installation is the application name, else the host with the display-only dot break.
- **Hosted:** the caption "Version {n} · Updated {date}" (rules) or "Updated {date}" (privacy). The body is the
  Markdown through `PolicyDocumentFormatting`: headings as Title (level 2) or Label (level 3 and below), body text,
  lists with markers, nesting (16dp per level) and 8dp between items, code as plain text, and no server HTML or WebView.
  Links are underlined in `steward`, like message links. The column is limited to the 640dp reading width.
- **External:** "{installation} publishes this document on its website.", the URL's host, and **Open in browser**,
  for `https` only. Anything else shows "This document's address isn't secure, so the app won't open it. Ask the person
  who runs the server." and no button.
- **NotPublished:** "{installation} hasn't published this document yet." This isn't an error.
- **Failure:** the error line with **Try again** (a GET).
- **Links:** every link in a document, and the external Open in browser, go through the shared **explicit external
  choice** (`ExternalLinkChoice`), which names the destination before anything opens:
  - `https` → the browser;
  - `mailto` → the mail app;
  - `http` (or anything else) → titled "Not a secure link": "This link to {host} isn't secure, so the app won't open
    it." with only Close.
  - The P-221 accessibility-feedback dialog now uses the same component, with its tags and copy unchanged.

## Entry points

Each row appears only when `policies` discovery publishes that document; missing (unknown) or None shows nothing.
- **Server found** (onboarding step 3): "{installation}'s privacy policy" and "{installation}'s rules", below the
  privacy line (the link O2a deferred). They're hidden while the server-update state shows.
- **You → About and privacy:** the same two rows (also hidden while the update state shows), plus "**{installation}'s legal notice**" with the open-external
  icon. The legal notice is a link only, with no document screen, and opens through the explicit choice.

Signed out, the screens are onboarding destinations (`policy/rules`, `policy/privacy`). Signed in, they're shell
destinations under You (`you/policy/…`), so neither path crosses the other graph.

## Verification

- `PolicyReadsTest` (JVM, a real `SessionRepository` over fixture TLS): the read signed out and signed in; EXPIRED
  for another server's or an earlier generation's session; refused (INCOMPATIBLE) while the update state shows.
- `PolicyDocumentViewModelTest` (JVM): reopening the same document and scope keeps it; a newer scope reloads; close drops
  it and drops a late result; Retry is a fresh read; a stray cancellation from the read becomes Failed with Try again,
  never a stuck Loading.

- `PolicyDocumentJourneyTest`:
  - Server found shows only published rows and opens a hosted document;
  - no discovery shows no rows;
  - document links go through the choice (https gets Open, http the insecure line with no Open);
  - per-link routing in a mixed paragraph: the second link, targeted by its own annotation, gets its own dialog in
    both orders (http after https, https after http);
  - external https opens through the choice, external http shows the insecure line;
  - NotPublished; a failure, then Retry as a fresh GET;
  - signed in, You shows both rows and the legal notice link through the choice, with a version caption.
- `PolicyDocumentPresentationTest`, light and dark: a hosted document with every element (H2, H3, a nested list,
  inline links including an http one, escaped HTML, a code span), external and not published at default and 200%;
  the http link's dialog, loading and failure at 200%. 18 captures (`ccm-policy-presentation`).
