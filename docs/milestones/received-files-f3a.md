# Received files F3a: the loader's owner and the screens' panes

F3 is split for review size (TL condition 10). F3a adds no UI: it gives the [F2](received-files-f2.md) loader its one
app-scoped owner and the handles the conversation screens will drive, and refines revalidation. Received files stay
unavailable in the app until F3b wires the transcript, so the availability disclosures stay accurate. It follows the
TL's F3 rulings of 2026-11-19.

## What it adds

- **One owner (TL, #308):** `AttachmentImagesViewModel`, to be created in MainActivity like RecoveryKeyViewModel (F3b
  wires it).
  - It survives configuration change, and the loader drops every image and job on any change of server, account,
    generation or phase.
  - Rooms and DMs share it, because the memory budget is process-wide. Conversation view models never own it.
  - **Held decoded images may use half the shared budget** (at most 48 MiB). That cap bounds only held images: the
    transient peak (the raw bytes, the decode and a rotation) comes from the shared ledger and is released when the
    producer ends, so the cap never blocks a large original on a small device (TL).
- **Panes (`ImagePanes`):** each conversation screen opens a pane with its owner token and its actual nav entry (the same
  rule as `leavePane(owner)`) and releases it when it stops.
  - Only the most recently opened pane is current, so a late call from a retired screen is a no-op.
  - Visibility is the union over panes. A pane's release un-marks only its own frames, so a late release from a retired
    screen never hides a frame its successor shows.
  - **Hidden or released never cancels** a load that has started; it only makes the held image evictable. A rotation
    never downloads twice.
  - **A new visit** is a pane for a different nav entry than the last one opened. A rotation or a return from the
    background reopens the same entry and isn't a new visit.
- **Revalidation refinement (TL):** a fresh 200 is the validation for the visit it was **sent** in. In that visit there's
  no conditional GET on a re-show (a rotation, a scroll back). A load that settles after a fresh entry leaves the new
  visit its one conditional GET, since the server could have changed meanwhile. Later visits still revalidate at most
  once each.

## Verification

- **JVM: `ImagePanesTest` (4):**
  - a retired pane is a no-op, and its late release never hides the successor's frame;
  - only a new entry is a new visit;
  - hidden un-marks only that pane's own mark, and a released pane stays inert;
  - two panes on one frame hide it only when both let go.
- **JVM: `AttachmentImagesTest` (24)**, adding:
  - a rotation through panes downloads once, even when released mid-load;
  - a load that settles after a fresh entry leaves that visit exactly one conditional GET;
  - **at a 16 MiB shared budget with an 8 MiB image cap:** a 10 MiB-raw original shows, and only the 32 KB kept bitmap
    stays charged; a 20 MiB-raw original is NoMemory, with Try again exactly one more attempt and no loop.
  - The two F2 revalidation tests now pin the refinement: no conditional GET in the fetching visit, one in a later
    visit however often the frame scrolls back, and one more after a fresh entry.
