# Third-party licences

The in-app open-source licences screen (its own slice) shows these to people. This page records licence
facts that need more than the dependency's metadata.

## UnifiedPush embedded FCM distributor 3.1.0

- **Dependency:** `org.unifiedpush.android:embedded-fcm-distributor:3.1.0`, an unmodified, separate AAR
  (no shading, no copied source). Its only dependencies are kotlin-stdlib and kotlinx-coroutines-core.
- **Conflict:** the 3.1.0 POM declares "The Apache License, Version 2.0". The
  [LICENSE at tag 3.1.0](https://codeberg.org/UnifiedPush/android-embedded_fcm_distributor/src/tag/3.1.0/LICENSE)
  is the GNU LGPL v2.1. Commit `2fe24cc532` (2026-11-04, after the tag) changed the repository's LICENSE
  to Apache 2.0 "to match what is published on Maven".
- **What we do:** comply with the stricter LGPL-2.1 reading (owner decision, 2026-11-13). The app's
  open-source notice credits it under LGPL-2.1, with a link to the source at tag 3.1.0 and its copyright
  line ("Copyright (C) 2022 Simon Gougeon", from the licence appendix). ChitChat's own source is public
  under BSD-2.
- **Later:** when a release tagged after 2026-11-04 carries Apache-2.0, move to it as a normal dependency
  update and update this entry.
