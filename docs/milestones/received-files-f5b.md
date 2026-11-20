# Received files F5b: Save, Share and Open on screen

F5b puts [F5a](received-files-f5a.md)'s transfers on screen, following the PL's answers and the TL's notes of
2026-11-20; the presentation detail is in [files-media.md](../design/files-media.md) ("In the transcript", "Full-screen
viewer", "Share out" and "Save, Share and Open notices"). The disclosures' last step ships: "Not in the app yet: profile
pictures and guest invite links." The viewer captures follow in a stacked F5c.

## Where it shows

- **File cards:** Open and Save (48dp text buttons) under the name and "PDF · 2.4 MB"; an APK-like file has Save only.
  The card stays one unclickable TalkBack node labelled "{name}, {type}, {size}, Open, Save" (or ", Save"); the buttons
  are their own nodes. The name drops bidi controls and the label wraps it as an isolated run.
- **The viewer:** Save then Share as 48dp icons at the end of the top bar; absent on gone and not allowed, disabled
  offline (with the new offline line); no Share for an APK-like file. Its bottom block shows the transfer's notice above
  the action row.
- **Message actions:** Save and Share after Reply and Copy ("… photo" for images, "… file" otherwise); no Share for an
  APK-like file.
- **Notices:** the conversation's stack (room and DM) and the viewer's bottom block: progress with Cancel, Saved, and
  every stop line in the PL's copy. A finished line clears when the person leaves the conversation.

## How it works

- `FileTransfersUi` (provided through `LocalFileTransfers` by `FileTransfersHost` in MainActivity) holds what needs the
  Activity:
  - **Save:** `requestSave` first (F5a binds the request to the session and attachment), then the app's own
    `ACTION_CREATE_DOCUMENT` with the server's MIME type and the sanitized title; the result goes to `saveChosen`.
  - **Handoff:** only when F5a reports a verified copy (`Ready`): `handedOff` first, then the share sheet or the viewer
    app; `ActivityNotFoundException` is "no app" (the copy is deleted).
  - **Background:** ON_STOP stops a transfer only when it isn't a configuration change and isn't the app's own picker
    in front, so the picker never cancels the Save it was opened for (TL condition).
  - **First-use note:** Share and Open ask once per install (a boolean in the backup-excluded settings store, recorded on
    Continue). Cancel starts nothing. The dialog counts in both screens' `composerDialog` (no read proof, no typing).
- Disabled states come from F5a: one transfer at a time, and nothing during the session's 429 wait.
- **Configuration changes (#318 review):** the picker's result is matched to `FileTransfers.pendingSave`, never to a
  copy kept in a composition, so a rotation, theme or font change while the picker is open still delivers the Save and
  never leaves the transfers busy. `FileTransfersUi` is an Activity-scoped ViewModel, so an open first-use note survives
  too, and a finished line doesn't clear on a configuration change, only when the person leaves the conversation.
- **Levels:** Saved, a cancelled Save and no app are level 1; every failure is level 2 (the shared warning notice).
- **Type label:** the real extension, but "APK" for any APK-like file (`FileNames.typeLabel`).

## Verification

- **Instrumented: `ReceivedTransfersJourneyTest`:**
  - a file card offers Open and Save (48dp, label ", Open, Save"); an APK card offers Save only;
  - message actions: "Save photo"/"Share photo", "Save file"/"Share file"; an APK-like name under an image type is a
    card and its actions have Save and no Share;
  - the viewer: Save then Share (Share outermost), both disabled offline, neither on gone, and no Share for an APK-like
    attachment;
  - the first-use note: Cancel leaves no transfer state, no download, no copy and no picker, and records nothing;
    Continue records it and starts the copy;
  - during a transfer the other card's buttons are disabled and the notice with Cancel shows; Saved then shows "Saved
    {name}" and the buttons return;
  - a bidi override never reaches the card's shown name or label, and the APK rule runs on the sanitized name;
  - a Save the person cancels is level 1, and a pre-byte 410 is level 2.
- **`ReceivedTransfersRecreationTest`:** a Save whose picker result arrives after a real `recreate()` streams into the
  chosen document and leaves nothing busy; the first-use note survives a recreation and its Cancel still starts nothing.
- **JVM:** `FileNamesTest` adds the type label (`report.pdf` under the APK MIME reads "APK").
- **`ReceivedFilesJourneyTest`:** the disclosures assert the final wording.
