# A-floor fixes F1–F3

Presentation-only fixes for the three A-floor violations in [gap-audit.md](../design/gap-audit.md#a-floor-fixes-slice-brief-for-codex).
No navigation, request, read-proof, send, focus or IME behavior changes. The status slice still owns
the development disclosures, the header contents and the Details trigger conditions.

- **F1 (A-04):** top-bar titles never break mid-word. The "ChitChat" wordmark is one line at the
  display face's 1.0 size regardless of font scale, with heading semantics and content description.
  Every other bar title is one ellipsized line that follows the font scale and keeps its full text
  in semantics as a heading. Account, Settings and Back are unchanged.
- **F2 (A-04):** the room and DM headers keep their 30% bound and scrolling (shared
  `BoundedHeader`). When the content overflows, a 24dp bottom fade to `ground` shows it continues,
  and a 48dp **More** button scrolls one viewport, becoming **Back to top** at the end. When the
  content fits, neither appears. The composer never moves.
- **F3 (A-05, A-02):** the Details marker differs by shape: a `signatureText` dot (generic), the
  Lantern `help` question mark in `warning` (uncertain), or the `error` octagon (failed), on a
  ground-ringed 16dp badge. Uncertain still wins over failed. Each state has its own spoken
  description; the blocked-composer description is unchanged. The ⋮ text glyph becomes the Lantern
  `more` icon in `textMuted`, keeping the 48dp target. `LanternPalette.warning` is an alias of
  `unsureLine`.

Verification: the token contrast test adds the three markers and the more glyph at 3:1 against
`ground` in light, dark and Pure black. `AFloorPresentationTest` checks the wordmark is one uncut
line at 2.0, a long title is one line with full semantics text, More scrolls without moving a
composer and returns to top, a fitting header shows no More, and each marker state's description
and shape with uncertain precedence. The J review uses the capture workflow on this head.
