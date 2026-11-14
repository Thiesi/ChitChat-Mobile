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

## B2: native entry (follows; agreed, not built here)

Recorded here so B2 starts from the decisions (technical lead and presentation lead, 2026-11-14):
- **Where:** the Rooms directory's room detail dialog for v1. The room-presence header sheet adopts it when it ships.
  `rooms_owner_immutable` becomes "Owners can't leave their room. Hand it to someone else first."
- **When the snapshot loads:** one GET when the dialog opens. Only for a viewer who is the room's owner or whose
  current bootstrap role is a global room manager; it's never inferred, and missing means no fetch. Ordinary members
  never trigger it. The result lives in VM memory only, cleared on close, navigation or owner retirement under the
  original-session and entry guards.
- **Load states:**
  - loading: the explanation alone;
  - network or server failure: "Couldn't check who can take over." with Try again, one explicit GET;
  - `403`: hides the row and re-reads the room;
  - `404 room_not_found`: the existing room-unavailable path;
  - loaded: Transfer ownership… if any row is flagged, otherwise "No one in this room can take it over right now."
- **Picker and outcomes:** a full-screen picker of flagged members, replacing the dialog rather than stacking; the
  P-217 confirm dialog; `withStepUp`; P-217's outcome lines. On success, explicit room and directory re-reads.
- **Captures:** 24 for J review.
