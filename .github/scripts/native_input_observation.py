"""Opt-in traced control only: never a full-suite gate or a retry mechanism."""

import sys

import native_journeys


CONTROL = ("io.github.thiesi.chitchat.testing.NativeInputDialogJourneyTest"
           "#hundredNativeInputDialogCyclesPreserveBodyWithoutInventingEdits")
COMMAND = [*native_journeys.GRADLE,
           "-Pandroid.testInstrumentationRunnerArguments.class=" + CONTROL,
           "-Pandroid.testInstrumentationRunnerArguments.traceNativeInput=true"]
REPORT = native_journeys.ROOT / "app/build/reports/nativeInputObservation/ime-lifecycle.txt"


def main() -> int:
    print("DIAGNOSTIC ONLY: one traced 100-cycle control, no retries; not a full native gate.")
    return native_journeys.run_once(COMMAND, REPORT)


if __name__ == "__main__":
    sys.exit(main())
