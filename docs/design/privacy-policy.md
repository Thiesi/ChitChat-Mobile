# ChitChat Android app: privacy policy (draft)

Status: **draft for maintainer approval**, requested by the technical lead (issue #1 and PR #108).
It is built from the approved data safety facts (`play-readiness.md` §4) and the existing
in-app disclosures. It will be published at **https://ChitChat.NetWorkXXIII.de/app/privacy** (maintainer
decision, 2026-11-08). Publisher (NetWork XXIII) and contact
(chitchat@NetWorkXXIII.de) are set; only the publication date remains, set at approval. Every factual statement must be re-verified against the shipped app before
publication. Placeholders are written as **[PLACEHOLDER]**.

Editorial notes for reviewers are in *italic brackets* and aren't part of the published text.

---

## Privacy policy for the ChitChat Android app

*Last updated: [DATE OF PUBLICATION]*

### The short version

- ChitChat is an app for **self-hosted** chat servers. Your account and your messages live on
  the server you choose, run by that server's operators. The people who make this app don't
  run your server and don't receive your messages or account data.
- The app sends your data **only** to the server you chose, and, if you turn on notifications,
  encrypted notification payloads through the delivery app you chose.
- The app has **no analytics, no advertising and no crash-reporting services**.
- **Messages aren't end-to-end encrypted.** Your server's operators can technically access what
  is stored on their server.

### Who is responsible for your data

ChitChat servers are run independently by their operators, for example a family, club or
company. **The operator of your server decides how your data is stored, kept and used, and is
responsible for it** (in GDPR terms, the operator is the controller for the data on their
server). Questions or requests about your account and messages go to them.

The makers of this app (**NetWork XXIII**) provide the app only. We don't operate
servers for you and don't receive your account or message data. *[Reviewer note: if the
publisher also runs a public ChitChat server, that server needs its own privacy policy. This
document covers the app.]*

### What the app sends, and where

**To the server you choose:**
- **Account details:** your username and password when you sign in or register, and an optional
  birth date, which the server uses for age-restricted rooms.
- **What you create:** messages, replies, reactions, edits and deletions; files and photos you
  send; reports you submit about messages.
- **Session data:** cookies and security tokens the server uses to keep you signed in.
- **Notification settings:** if you turn notifications on, the delivery address and encryption
  keys the server needs to send you notifications.

Before a photo is uploaded, the app **removes location information** (GPS data) from it by
default.

All connections to your server use **HTTPS**. The app connects only to the server address you
entered, and doesn't follow redirects.

**To the notification delivery app you choose** (only if you turn notifications on):
ChitChat doesn't run in the background. It uses UnifiedPush: a separate delivery app you choose
receives notifications from your server and wakes ChitChat. The notification content is
**encrypted** between your server and this app (Web Push encryption, RFC 8291), so the delivery
service can't read it. If no UnifiedPush delivery app is installed, ChitChat falls back to
Google's Firebase Cloud Messaging, and Google then transports these encrypted messages. Google
can see that a notification was delivered to your device and when, but not what it says. The
app doesn't include Google's Firebase software; it uses UnifiedPush's standard Web Push
delivery.

**To anyone else:** nothing. The app contains no analytics, advertising or crash-reporting
software, and it doesn't sell or share your data.

### What the app stores on your phone

- **Your sign-in session**, encrypted with a key held in Android's secure hardware-backed
  keystore. The key can't be exported.
- **App settings:** the server address, appearance (light, dark, pure black) and similar
  preferences.
- **Unsent drafts and temporary display data** (for example images you opened) are kept **in
  memory only** while the app runs. They aren't saved to storage.
- When you **share** a file to another app, a temporary copy is made for that app. It is kept
  for 24 hours so the other app can finish opening it. After that it's deleted the next time
  you open the app or share a file. Signing out deletes it straight away.
- Files you **save** or **export** go to a location you choose, and stay there under your
  control. Exports aren't encrypted, and the app tells you so.
- The app **turns off Android cloud backup** for its data.

Signing out removes your session from the phone.

### Your server's own rules and practices

What happens to your data **on the server** (how long messages are kept, who can moderate, and
whether administrators can inspect direct messages) is decided by your server's operator. The
app shows what the server reports, for example the direct-message retention period and whether
inspection is enabled, under **Direct → What this means**.

Editing or deleting a message doesn't necessarily erase earlier versions that the server
keeps. *[Matches the existing in-app disclosure.]*

### Your choices and rights

- **Export your data:** You → Export my data saves a copy of your account data from your server
  to a file you choose.
- **Delete your account:** You → Privacy and security → Delete account closes your account on
  that server. You can restore it for 14 days. After that, your name is replaced with "Closed
  account" and your personal details are cleared. Messages you sent stay visible to the people
  who received them, under that server's retention policies. You can also close your account
  through your server's website or by contacting its operator.
- **Notifications:** turn them off at any time in You → Notifications or in Android's settings.
- **Other requests** (access, correction, erasure beyond the above) go to **your server's
  operator**, who holds the data. The app makers can't fulfil them, because we don't have your
  data.

### Children

The app isn't directed at children. Each server's operator decides who may join and may restrict
rooms by age.

### Changes to this policy

If this policy changes, we'll publish the new version here with a new date, and point to it from
the app.

### Contact

Questions about the app: **chitchat@NetWorkXXIII.de**.
Questions about your account or messages: the operator of your ChitChat server.

---

## Verification checklist before publication (not part of the published text)

- [ ] No analytics, ads or crash SDKs in the release build's dependency set.
- [ ] Session vault uses the Android Keystore AES-GCM key; backups are disabled
      (`allowBackup=false`, data extraction rules).
- [ ] Photo location removal ships as specified in `files-media.md`.
- [ ] Drafts and images aren't written to disk, and share copies expire after 24 hours, are deleted at the next start or share after that, and are deleted on sign-out.
- [x] Embedded FCM distributor ships (maintainer, 2026-11-08); the FCM sentence is final. Verify it's used only when no UnifiedPush distributor is installed.
- [x] Publisher (NetWork XXIII) and contact (chitchat@NetWorkXXIII.de) filled in; confirm the mail alias works before publishing. URL: https://ChitChat.NetWorkXXIII.de/app/privacy.
- [ ] Wording consistent with the data safety form answers.
