# Third-party licences

Account → **Open-source licences** lists every project the app ships, from
`app/src/main/assets/licenses/oss_licenses.json`, with the full licence texts bundled next to it. Each
entry's `covers` rules (`group:artifact`, `*` allowed) must match every artifact on the release runtime
classpath:
- `:app:checkLicenceCoverage`, run in CI's build job, fails on any uncovered artifact, including
  transitive ones;
- `OssLicencesTest` checks that each runtime dependency in the version catalog has its own entry at the
  catalog's version.

When either fails, add or update an entry, with its licence text if a new licence appears. None of the
runtime Apache-2.0 dependencies ships a NOTICE file (checked 2026-11-13).

This page records licence facts that need more than the dependency's metadata.

## UnifiedPush embedded FCM distributor 3.1.0

- **Dependency:** `org.unifiedpush.android:embedded-fcm-distributor:3.1.0`, an unmodified, separate AAR
  (no shading, no copied source). Its only dependencies are kotlin-stdlib and kotlinx-coroutines-core.
- **Conflict:** the 3.1.0 POM declares "The Apache License, Version 2.0". The
  [LICENSE at tag 3.1.0](https://codeberg.org/UnifiedPush/android-embedded_fcm_distributor/src/tag/3.1.0/LICENSE)
  is the GNU LGPL v2.1. Commit `2fe24cc532` (2026-11-04, after the tag) changed the repository's LICENSE
  to Apache 2.0 "to match what is published on Maven".
- **What we do:** comply with the stricter LGPL-2.1 reading (owner decision, 2026-11-13). The app shows
  "LGPL-2.1"; the discrepancy is recorded only here. The app's
  open-source notice credits it under LGPL-2.1, with a link to the source at tag 3.1.0 and its copyright
  line ("Copyright (C) 2022 Simon Gougeon", from the licence appendix). ChitChat's own source is public
  under BSD-2.
- **Later:** when a release tagged after 2026-11-04 carries Apache-2.0, move to it as a normal dependency
  update and update this entry.

## JSR-305 annotations 3.0.2

- **Dependency:** `com.google.code.findbugs:jsr305:3.0.2`, transitive.
- **Conflict:** its POM declares Apache-2.0, while the JSR-305 annotations project has long described the
  code as BSD-3-Clause.
- **What we do:** meet both cheaply (technical lead, 2026-11-13). The app shows "BSD-3-Clause" and
  bundles both texts; the discrepancy is recorded only here. The published artifact carries no
  copyright line.

## Protocol Buffers

`com.google.protobuf:protobuf-java` (via Tink) and AndroidX's repackaged
`datastore-preferences-external-protobuf` are BSD-3-Clause. The entry reproduces the notice with
"Copyright 2008 Google Inc.".
