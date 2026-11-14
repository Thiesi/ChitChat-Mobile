# Room ownership transfer

Owners can't leave a room (`409 owner_cannot_leave`), so handing it on is how an owner gets out.
Design: [P-217](../design/administration.md) (presentation lead with the technical lead).

## B1: protocol (this slice; no native entry yet)

Contracts at the v3.10.0 minimum ([rooms.md](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/rooms.md),
[admin.md](https://github.com/Thiesi/ChitChat/blob/b2791a0/docs/api/admin.md); ChitChat#273 and #302).
`RoomOwnershipOperations` is a separate capability that `SessionRoomRepository` implements, so the existing room fakes
don't change.

- **`transferSnapshot(roomId)`:** one `GET admin/rooms/snapshot.php?room_id=…`.
  - **Who may load it:** the room's owner and global room managers (`super_admin`, `admin`, `chat_admin`). Anyone
    else gets `403 forbidden`.
  - **What's decoded:** only the room ID, plus each member's ID, username, room role and `can_transfer_ownership`.
    Invitations, join times and connection counts are never read or kept.
  - **Strict checks:** the room must be the requested one; member IDs must be positive and distinct; usernames
    non-blank; roles one of owner, moderator or member. There's at most one owner, and the owner's own row is never
    offered. A quoted Boolean, or anything else, is `INCOMPATIBLE`.
  - **Candidates:** the members whose flag is `true`. A `false`, missing or `null` flag means not offered, never
    guessed.
  - **The flags are hints.** They mean "allowed now, after step-up" and can go stale. For viewers who can't see bans
    (the owner, Chat Admins) they ignore bans, so a banned member can be listed and is then refused with
    `409 transfer_target_not_eligible`. The app must not reveal why.
- **`transferOwnership(roomId, targetUserId)`:** one CSRF `POST rooms/transfer-ownership.php` with
  `{room_id, target_user_id}`.
  - **Result:** it returns the room as the actor now sees it, and the room must be the requested one.
  - **Never replayed:** every refusal stays a typed `ApiFailure`: `403 step_up_required` (for the caller's
    `withStepUp`), `403 forbidden`, `404 room_not_found`, `404 membership_not_found`, `409 already_owner`,
    `409 transfer_target_not_eligible` and `409 ownership_changed`.
  - **Unknown outcomes:** a missing or ambiguous response is unknown, and only an explicit room re-read resolves it.
- **Never used:** `can_take_ownership` (an administrator's self-takeover stays on the website).
- **Privacy:** no logs and no saved state. The model classes are plain classes without generated string
  representations.

## B2: native entry

Built against [P-217](../design/administration.md) as merged (PR #234, #236). The decisions came from the technical
lead and presentation lead, 2026-11-14.

- **Where:** the Rooms directory's room detail dialog for v1. The room-presence header sheet adopts it when it ships.
  For an owner, "Owners can't leave their room. Hand it to someone else first." replaces the old
  `rooms_owner_immutable` line and Leave.
- **`RoomTransferFlow`:** owned by `RoomsViewModel`, memory only, with no saved state and no logs (its UI state has no
  generated string representation).
  - **When the snapshot loads:** one GET when the detail opens. Only for a viewer whose room role is owner, or whose
    current bootstrap `roles` contain a global room-management role (`super_admin`, `admin`, `chat_admin`). It's
    never inferred, missing means no fetch, and global moderators and ordinary members never trigger it.
  - **Retired by:** closing the dialog, background, navigation or an identity change. Every await is fenced by
    attempt and identity, so a late result never reaches a successor.
- **Load states:**
  - loading: the explanation alone;
  - network or server failure: "Couldn't check who can take over." with Try again, one explicit GET, never automatic;
  - `403`: hides the row with no Try again, and re-reads the room once;
  - `404 room_not_found`: the existing room-unavailable path;
  - loaded: Transfer ownership… if any row is flagged; otherwise, for an owner, "No one in this room can take it over
    right now." An administrator also sees "Owner: {name}".
- **Picker:** a full-screen list of the flagged members only, with pebble, name and room role, and a filter above 10.
  It replaces the dialog rather than stacking; the confirm dialog and step-up sit over the picker only.
- **Confirm:** P-217's actor-specific text. Make owner is in `signatureText`, never error-coloured.
- **Transfer:** one POST through `withStepUp`, so a definite `403 step_up_required` leads to the shared
  `StepUpDialog`, then exactly one more POST. Cancelling step-up sends nothing more and keeps the choice.
- **Outcomes:** P-217's lines, and nothing is ever resent.
  - `already_owner` and `forbidden` re-read the room. `membership_not_found` re-reads the list.
    `ownership_changed` offers Check room (one room GET, then the picker on the fresh list).
  - `transfer_target_not_eligible` gives no reason and keeps the person listed, because for an owner the flags ignore
    bans and must not reveal one.
- **Commit before response** (technical lead):
  - The transfer commits server-side before the reply. A POST without a definite answer (network, timeout, 5xx, or
    an unusable reply such as a room mismatch) is UNKNOWN: "Not sure the transfer went through." with Check room.
  - Check room re-reads the room and closes to the dialog's actual state. The picker is locked until then, and there's
    no blind "Try again" on the POST.
  - A definite refusal (step-up, verification, rate limit or an unlisted code) committed nothing, keeps the choice and
    shows its error.
- **Success:** the returned room is adopted, then the room and directory are re-read explicitly and the outcome is
  never assumed.
  - An owner returns to the dialog with "**{name}** now owns {room}. You're a moderator now." and Leave room.
  - An administrator sees the new owner after the snapshot reloads.
- **Not covered:** a cancellation mid-POST (leaving the dialog) can't undo a committed transfer. The next open re-reads
  the room.
- **Captures:** 24 for J review: row, none, load failure, picker, confirm and success, at default and 200% font,
  light and dark (`RoomTransferPresentationTest`, `ccm-transfer-presentation`).
