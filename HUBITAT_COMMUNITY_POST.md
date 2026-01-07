Title: HE iCal Switch – Calendar-driven virtual switch with correct timezone handling



Summary:

This Hubitat device driver watches an iCal/ICS feed and turns a virtual switch on when an eligible event is active and off when no eligible event is active. It is designed to respect the hub’s configured timezone and includes calendar-level timezone detection for Outlook / Microsoft 365 feeds (X-WR-TIMEZONE and VTIMEZONE).



Key features:

\- Hub timezone display and scheduling (no per-device timezone override)

\- Calendar timezone detection (X-WR-TIMEZONE, VTIMEZONE)

\- Busy-only filtering (TRANSP != TRANSPARENT)

\- Optional filtering for tentative and declined events

\- Include/exclude keyword filters against SUMMARY/LOCATION

\- Configurable start/end offsets

\- Transition polling at event boundaries, plus regular background polling

\- Next events list output for debugging and dashboards



Install:

\- Paste driver code into Drivers Code

\- Create a Virtual Device

\- Select driver: HE iCal Switch

\- Set ICS URL and preferences

\- Save Preferences, then Initialize



GitHub:

https://github.com/truittchris/he_ical_switch



