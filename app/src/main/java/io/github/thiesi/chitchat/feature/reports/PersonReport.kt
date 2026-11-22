package io.github.thiesi.chitchat.feature.reports

import io.github.thiesi.chitchat.core.profile.ProfileSnapshot
import io.github.thiesi.chitchat.core.reports.ReportTarget

/**
 * What Report {name}… sends and shows, frozen from the profile the card rendered when it was tapped (R3, TL). The room
 * context is the profile request's own (null for a DM card, or a room card whose response came without one): a null
 * context is a global report with the global copy, and the conversation's room is never filled in.
 */
internal class PersonReport(val target: ReportTarget.Person, val pictureAvailable: Boolean, val name: String) {
    override fun toString() = "PersonReport(${target.userId}, room=${target.roomId})"
}

internal fun personReport(snapshot: ProfileSnapshot) = PersonReport(
    ReportTarget.Person(snapshot.profile.id, snapshot.roomId),
    // "Offensive profile picture" only with a current picture: avatars on the server and a picture on this profile.
    pictureAvailable = snapshot.avatarsAvailable && snapshot.profile.hasAvatar,
    name = snapshot.profile.username)

/**
 * The conversation a profile card is shown over, where its person report lives (R3): cards open only from a room
 * conversation, the notification room route and a DM thread. Anything else has no origin, so the card offers no
 * Report entry.
 */
internal fun profileReportOrigin(room: Boolean, direct: Boolean, roomId: Long?, peerId: Long?): ReportOrigin? = when {
    room && !direct && roomId != null && roomId > 0 -> ReportOrigin.Room(roomId)
    direct && !room && peerId != null && peerId > 0 -> ReportOrigin.Direct(peerId)
    else -> null
}
