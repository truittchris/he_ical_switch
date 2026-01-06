# Changelog

All notable changes to this project are documented in this file.

The format follows Keep a Changelog and Semantic Versioning.

## [1.0.0] - 2026-01-06

### Added
- Full support for recurring Outlook / Microsoft 365 calendar events by expanding WEEKLY RRULE master VEVENTs into concrete instances within the poll window.
- Support for common Outlook RRULE fields including BYDAY, INTERVAL, UNTIL, and WKST.
- RECURRENCE-ID override handling so modified or cancelled instances in a series correctly replace generated occurrences.

### Changed
- Event evaluation pipeline now expands recurring events before window filtering and eligibility checks, ensuring recurring meetings can appear in `nextEvents`, `nextSummary`, and participate in switch state transitions.

### Debug
- Added explicit debug logging when a VEVENT is dropped due to an unparseable DTSTART, including DTSTART value, TZID, UID, and SUMMARY to aid troubleshooting.

---

## Initial development (pre-1.0)
- Hubitat iCal driver with hub-timezone-based scheduling and display
- Calendar-level timezone detection (X-WR-TIMEZONE, VTIMEZONE)
- Switch control based on eligible events with configurable filters and keyword matching
