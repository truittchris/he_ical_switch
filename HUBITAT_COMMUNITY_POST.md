Title: iCal Calendar Switch (Hub TZ) – Calendar-driven virtual switch with correct timezone handling

This driver maps a calendar’s “busy time” to a Hubitat virtual switch.

When an eligible calendar event is active, the switch turns ON.
When no eligible event is active, the switch turns OFF.

The goal is to provide a reliable automation signal, not a full calendar UI.

Key points:
- Uses the hub’s configured timezone for all evaluation and display
- Works with public iCal / ICS feeds
- Explicitly supports Outlook / Microsoft 365 feeds
- Handles UTC timestamps, calendar-level timezones, and floating times
- Schedules precise transitions at event start/end (not polling-only)

Filtering options:
- Busy vs free (TRANSP)
- Tentative events
- Declined events (when attendee data exists)
- Optional all-day events
- Keyword include / exclude filters
- Start and end offsets

The device also exposes upcoming eligible events directly on the device page
to make validation and debugging straightforward.

Typical uses:
- “In a meeting” virtual switch
- Suppressing announcements during meetings
- Driving Rule Machine automations
- Dashboard indicators

GitHub (driver, README, license):
https://github.com/truittgit/ical_hubitat

The driver is free for public use. Attribution must remain intact.

Feedback, issues, and pull requests are welcome.

– Chris Truitt
