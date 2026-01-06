# Changelog

All notable changes to this project are documented in this file.

The format follows Keep a Changelog and this project aims to follow Semantic Versioning.

## [1.0.1] - 2026-01-06

### Fixed
- Corrected a scheduling issue where transition polls (event start/end boundary checks) could be throttled by the regular poll interval, causing missed or delayed switch on/off behavior.

### Changed
- Split polling into separate regular and transition paths:
  - Regular polls run on the configured pollSeconds cadence.
  - Transition polls run at event boundaries and bypass cadence throttling.
- Updated scheduling so event boundary transitions trigger `pollTransition`, while background refresh uses `pollRegular`.

## [1.0.0] - 2026-01-06

### Added
- Initial stable release of the iCal Calendar Switch (Hub TZ) Hubitat driver.
- Hub-timezone-based scheduling and display with no per-device timezone override required.
- Calendar-level timezone detection for Outlook/Microsoft 365 feeds via `X-WR-TIMEZONE` and `VTIMEZONE`.
- Configurable eligibility filters: busy-only (TRANSP), tentative/declined exclusion, all-day toggle, include/exclude keywords, and start/end offsets.
- Next-events list output (`nextEvents`) with optional location display, plus `activeSummary` and `nextSummary` for dashboards/logging.
- Weekly recurring-event support for Outlook/Microsoft 365 feeds by expanding common `RRULE:FREQ=WEEKLY` series into concrete instances within the poll window.
- Support for WEEKLY RRULE fields: `BYDAY`, `INTERVAL`, `UNTIL`, and `WKST`.
- `RECURRENCE-ID` override handling so modified or cancelled instances replace generated occurrences.
- Debug logging buffer (`rawDebug`) with capped size for easier troubleshooting.
