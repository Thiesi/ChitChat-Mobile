# Policy documents O3a1: protocol and the bounded document parser

Protocol and pure parsing for installation policies: released ChitChat v3.10.0
[docs/api/policies.md](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/policies.md) and
[play-readiness.md](../design/play-readiness.md) §1 (P-176). The native document screen, the About and privacy rows
and Server found's privacy link are O3a2. Rules acceptance (P-170/171/175, `accepted_rules_version`) is O3b.

## Discovery

`session.php`'s `policies` becomes `PublicSession.policies`, presence-gated:
- a missing key is **unknown** (an older server), so `policies` is null;
- each of `rules`, `privacy` and `legal_notice` is a `PolicyLink(url, version?, updatedAt?)`, or null when None;
- a malformed entry also reads as None, never a guess. A quoted version isn't a version, and a URL that isn't a
  non-empty string of at most 2,048 characters without whitespace or controls is dropped.

Its scheme is judged where it opens (O3a2: `https` only, through an explicit choice).

## Reading a document

`SessionHttp.policyDocument(RULES | PRIVACY)` makes one public `GET policies/show.php?document=…`:
- **Hosted:** `markdown`, `version`, `updated_at` and `change_summary`.
- **External:** the URL only.
- **`404 policy_document_not_found`:** **NotPublished**, not an error. Every other refusal stays typed: a 400
  `validation_error`, or a different 404.
- **Server HTML is never decoded.** Anything outside the documented shape is INCOMPATIBLE.

Hosted documents are read through `show.php`; only discovery's absolute URLs, for external documents and the legal
notice, are ever opened outside the app. Nothing is cached on disk.

## The display subset (`PolicyDocumentFormatting`)

A new bounded pure parser, separate from the v3.2.1 chat grammar (`MessageFormatting` is unchanged):
- **Blocks:** headings (level 1 becomes 2; setext headings included), paragraphs (soft breaks become spaces, hard
  breaks are kept), list items (ordered numbers kept, nesting capped at 3), block quotes flattened, fenced and indented
  code as plain text, thematic breaks dropped.
- **Inlines:** emphasis, strong, code as plain text, escapes, and links (`[text](url "title")`, `<autolinks>`, bare
  http(s) URLs without trailing punctuation).
- **Links are classified, never opened:** HTTPS, HTTP or MAILTO (http(s) through the chat grammar's own safe-target
  check, so no credentials or controls). Any other scheme keeps only its visible text. In O3a2 every link goes through
  the explicit external choice; http shows the insecure-address line.
- **Images** are dropped with their alt text and never fetched.
- **Raw HTML** stays literal text.
- **Bounds:** 200,000 characters, a work budget proportional to the input, 20,000 nodes, and depth 8 (quotes and inline
  nesting). Exceeding any of them gives the **literal fallback**: paragraphs of the original text, never a blank
  document.

## Verification

- `PolicyProtocolTest`:
  - discovery: unknown vs None vs entries, malformed entries as None;
  - a hosted document carries Markdown and never the HTML (with the request path);
  - external; 404 NotPublished; other refusals typed;
  - malformed shapes INCOMPATIBLE.
- `PolicyDocumentFormattingTest`:
  - headings, paragraphs, lists, quotes, code, emphasis and escapes;
  - link classification (http, https, mailto vs javascript, file and credentials);
  - autolinks and bare URLs; images dropped with alt;
  - raw HTML and script literal;
  - hostile inputs: an oversized document, deep quote nesting, deep inline nesting, pathological delimiters within the
    work budget, an unclosed fence.
