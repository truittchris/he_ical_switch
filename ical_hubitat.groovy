/**
 * iCal (ICS) – Hubitat Driver
 * - Pulls an ICS URL, parses VEVENTs, exposes:
 *   - switch (on when "busy"/inMeeting)
 *   - inMeeting (bool)
 *   - currentSummary
 *   - nextSummary
 *   - nextEvents (multi-line list)
 *   - rawDebug (helpful parse + time window diagnostics)
 *
 * Notes:
 * - "Include all-day events in next list" affects nextEvents/nextSummary display only.
 * - "All-day events count as busy" affects inMeeting/switch logic.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat

metadata {
    definition(name: "Dayforce iCal", namespace: "ctc", author: "ChatGPT") {
        capability "Switch"
        capability "Refresh"
        capability "Polling"

        attribute "inMeeting", "bool"
        attribute "currentSummary", "string"
        attribute "nextSummary", "string"
        attribute "nextEvents", "string"
        attribute "rawDebug", "string"
        attribute "charCount", "number"
        attribute "lastFetch", "string"

        command "initialize"
        command "poll"
    }
}

preferences {
    input name: "enabled", type: "bool", title: "Enabled", defaultValue: true, required: true

    input name: "icsUrl", type: "string", title: "iCal / ICS URL", required: true

    input name: "updateFreq", type: "number", title: "Update frequency (seconds)",
            defaultValue: 1800, required: true

    input name: "includePastHours", type: "number", title: "Include past hours",
            defaultValue: 4, required: true

    input name: "horizonDays", type: "number", title: "Horizon days (how far ahead to consider events)",
            defaultValue: 2, required: true

    input name: "maxEvt", type: "number", title: "Max events to parse (safety cap)",
            defaultValue: 15, required: true

    input name: "nextListCount", type: "number", title: "Next events list count",
            defaultValue: 15, required: true

    input name: "includeAllDayInNextList", type: "bool", title: "Include all-day events in next list",
            defaultValue: false, required: true

    input name: "allDayCountAsBusy", type: "bool", title: "All-day events count as busy",
            defaultValue: false, required: true

    input name: "showLocation", type: "bool", title: "Show location",
            defaultValue: true, required: true

    input name: "busyKeywords", type: "string", title: "Busy keywords (comma-separated)",
            defaultValue: "Busy,Tentative,Away", required: false

    input name: "freeKeywords", type: "string", title: "Free keywords (comma-separated)",
            defaultValue: "Free", required: false

    input name: "debugLogging", type: "bool", title: "Enable debug logging",
            defaultValue: true, required: true

    input name: "traceLogging", type: "bool", title: "Enable trace logging (very noisy)",
            defaultValue: false, required: true
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    if (!enabled) {
        logInfo("Initialize called but driver is disabled.")
        setStateIdle("Disabled")
        return
    }
    scheduleNextPoll()
    poll()
}

def refresh() {
    poll()
}

def poll() {
    if (!enabled) return
    fetchAndProcess()
}

def on() {
    // Manual override is not meaningful here; treat as poll.
    poll()
}

def off() {
    // Manual override is not meaningful here; treat as poll.
    poll()
}

private void scheduleNextPoll() {
    unschedule("scheduledPoll")
    Long seconds = safeLong(updateFreq, 1800L)
    if (seconds < 60L) seconds = 60L

    // Use runIn so we can reliably reschedule after each run.
    runIn((int)seconds, "scheduledPoll")
    if (traceLogging) logTrace("Scheduled next poll in ${seconds}s")
}

def scheduledPoll() {
    if (!enabled) return
    fetchAndProcess()
    scheduleNextPoll()
}

private void fetchAndProcess() {
    try {
        if (!icsUrl) {
            logWarn("No ICS URL configured.")
            setStateIdle("No ICS URL")
            return
        }

        def params = [
            uri: icsUrl,
            timeout: 20,
            contentType: "text/calendar"
        ]

        httpGet(params) { resp ->
            String body = coerceBodyToString(resp)
            if (body == null || body.trim().isEmpty()) {
                logWarn("ICS fetch returned empty body.")
                setStateIdle("Empty ICS")
                return
            }

            sendEvent(name: "charCount", value: body.length())
            sendEvent(name: "lastFetch", value: formatHubNow())

            List<Map> events = parseIcs(body)
            if (debugLogging) logDebug("Parsed events raw=${events.size()}")

            processEvents(events)
        }
    } catch (e) {
        logWarn("Fetch exception: ${e?.toString()}")
        sendEvent(name: "rawDebug", value: "Fetch exception: ${e?.toString()}")
        setStateIdle("Fetch exception")
    }
}

private String coerceBodyToString(resp) {
    try {
        def d = resp?.data
        if (d == null) return null

        // byte[]
        if (d instanceof byte[]) {
            return new String((byte[])d, "UTF-8")
        }

        // Some Hubitat responses expose 'text'
        if (d?.metaClass?.hasProperty(d, "text")) {
            return d.text?.toString()
        }

        // InputStream-like objects sometimes stringify OK, otherwise fall back to resp.getData()
        def s = d.toString()
        return s
    } catch (ignored) {
        try {
            // As a last resort
            return resp?.getData()?.toString()
        } catch (ignored2) {
            return null
        }
    }
}

private void processEvents(List<Map> rawEvents) {
    Date nowDt = new Date()
    TimeZone hubTz = location?.timeZone ?: TimeZone.getTimeZone("UTC")

    Long pastMs = safeLong(includePastHours, 4L) * 60L * 60L * 1000L
    Long horizonMs = safeLong(horizonDays, 2L) * 24L * 60L * 60L * 1000L

    Date windowStart = new Date(nowDt.time - pastMs)
    Date windowEnd = new Date(nowDt.time + horizonMs)

    // Filter cancelled + outside window, then dedupe
    List<Map> filtered = rawEvents
        .findAll { Map ev ->
            if (ev.cancelled) return false
            if (!ev.start || !ev.end) return false
            // Keep if overlaps window
            return (ev.end.time > windowStart.time) && (ev.start.time < windowEnd.time)
        }

    filtered = dedupeEvents(filtered)

    // Sort by start
    filtered.sort { a, b -> a.start.time <=> b.start.time }

    // Determine "current" and "next"
    Map current = filtered.find { Map ev ->
        isNowWithinEvent(nowDt, ev)
    }

    // Determine busy state
    boolean inMeet = false
    if (current) {
        inMeet = eventCountsAsBusy(current)
    } else {
        // Some users want "busy if next is soon" – not enabled here.
        inMeet = false
    }

    // Build upcoming list (from now forward, plus current if it’s not all-day excluded)
    List<Map> upcoming = filtered.findAll { Map ev ->
        // Include current and future events for list
        return ev.end.time >= nowDt.time
    }

    // Apply display rules for all-day events
    if (!includeAllDayInNextList) {
        upcoming = upcoming.findAll { !it.allDay }
    }

    // Limit list count
    int listMax = (int)Math.max(1, safeLong(nextListCount, 15L))
    if (upcoming.size() > listMax) upcoming = upcoming.take(listMax)

    // Choose next event for nextSummary (first item in upcoming list)
    Map next = upcoming ? upcoming[0] : null

    // Attributes
    sendEvent(name: "inMeeting", value: inMeet)

    if (inMeet) {
        sendEvent(name: "switch", value: "on")
    } else {
        sendEvent(name: "switch", value: "off")
    }

    sendEvent(name: "currentSummary", value: current ? formatEventSummary(current) : null)
    sendEvent(name: "nextSummary", value: next ? formatEventSummary(next) : null)
    sendEvent(name: "nextEvents", value: formatNextEventsList(upcoming))

    // Raw debug text to validate dates/times quickly
    String dbg = buildDebug(nowDt, hubTz, windowStart, windowEnd, current, next, filtered, upcoming)
    sendEvent(name: "rawDebug", value: dbg)

    if (traceLogging) {
        logTrace("Now busy=${inMeet}; current=${current?.summary}; next=${next?.summary}; upcoming=${upcoming.size()}")
    }
}

private boolean isNowWithinEvent(Date nowDt, Map ev) {
    if (!ev?.start || !ev?.end) return false
    // All-day events: DTSTART (date) to DTEND (date, exclusive). This still works with Date times at midnight.
    return (nowDt.time >= ev.start.time) && (nowDt.time < ev.end.time)
}

private boolean eventCountsAsBusy(Map ev) {
    // All-day handling
    if (ev.allDay && !allDayCountAsBusy) return false

    // Transparency / status logic
    if (ev.transparent) return false

    // Keyword-based busy/free (uses event "busyState" if we infer it)
    if (ev.freeByKeyword) return false
    if (ev.busyByKeyword) return true

    // Default: timed events count as busy
    return true
}

private List<Map> dedupeEvents(List<Map> events) {
    Map<String, Map> seen = [:]
    events.each { Map ev ->
        String uid = ev.uid ?: ""
        String key = uid ? "${uid}|${ev.start?.time}" : "${ev.summary ?: ''}|${ev.start?.time}"
        // Keep earliest end if duplicates clash
        if (!seen.containsKey(key)) {
            seen[key] = ev
        } else {
            Map existing = seen[key]
            if (existing?.end && ev?.end && ev.end.time > existing.end.time) {
                seen[key] = ev
            }
        }
    }
    return seen.values().toList()
}

private String formatNextEventsList(List<Map> upcoming) {
    if (!upcoming || upcoming.isEmpty()) return "None"
    StringBuilder sb = new StringBuilder()
    int i = 1
    upcoming.each { Map ev ->
        sb.append("${i}. ").append(formatEventSummary(ev)).append("\n")
        i++
    }
    return sb.toString().trim()
}

private String formatEventSummary(Map ev) {
    if (!ev?.start || !ev?.end) return ev?.summary ?: "Event"

    if (ev.allDay) {
        return "${formatDateOnly(ev.start)} (All-day) ${safeStr(ev.summary)}${formatLocation(ev)}"
    }

    return "${formatDateTime(ev.start)} – ${formatTimeOnly(ev.end)} ${safeStr(ev.summary)}${formatLocation(ev)}"
}

private String formatLocation(Map ev) {
    if (!showLocation) return ""
    String loc = (ev.location ?: "").trim()
    return loc ? " @ ${loc}" : ""
}

private String formatDateTime(Date dt) {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d, yyyy h:mm a z")
    sdf.setTimeZone(tz)
    return sdf.format(dt)
}

private String formatTimeOnly(Date dt) {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    SimpleDateFormat sdf = new SimpleDateFormat("h:mm a z")
    sdf.setTimeZone(tz)
    return sdf.format(dt)
}

private String formatDateOnly(Date dt) {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d, yyyy")
    sdf.setTimeZone(tz)
    return sdf.format(dt)
}

private String formatHubNow() {
    return formatDateTime(new Date())
}

private String buildDebug(Date nowDt, TimeZone hubTz, Date windowStart, Date windowEnd,
                          Map current, Map next, List<Map> filtered, List<Map> upcoming) {
    StringBuilder sb = new StringBuilder()
    sb.append("hubTz=${hubTz?.ID} hubNow=${formatDateTime(nowDt)}\n")
    sb.append("windowStart=${formatDateTime(windowStart)}\n")
    sb.append("windowEnd=${formatDateTime(windowEnd)}\n")
    sb.append("parsedInWindow=${filtered.size()} upcomingShown=${upcoming.size()}\n")
    sb.append("CURRENT: ").append(current ? rawEventLine(current) : "none").append("\n")
    sb.append("NEXT[1]: ").append(next ? rawEventLine(next) : "none")
    return sb.toString().trim()
}

private String rawEventLine(Map ev) {
    String tzid = ev.tzid ?: ""
    String st = ev.allDay ? formatDateOnly(ev.start) : formatDateTime(ev.start)
    String en = ev.allDay ? formatDateOnly(ev.end) : formatDateTime(ev.end)
    return "TZID=${tzid} DTSTART=${st} DTEND=${en} SUMMARY=${safeStr(ev.summary)}"
}

private List<Map> parseIcs(String icsText) {
    // Unfold lines (RFC5545): lines that begin with space or tab continue previous line
    List<String> lines = unfoldLines(icsText)

    List<Map> events = []
    Map current = null
    boolean inEvent = false

    lines.each { String line ->
        if (line == "BEGIN:VEVENT") {
            inEvent = true
            current = [:]
            current.cancelled = false
            current.transparent = false
            current.allDay = false
            current.uid = null
            current.summary = null
            current.location = null
            current.tzid = null
            current.freeByKeyword = false
            current.busyByKeyword = false
            current.dtstartMeta = [:]
            current.dtendMeta = [:]
            return
        }

        if (line == "END:VEVENT") {
            if (current) {
                // Must have DTSTART; DTEND can be derived for some all-day events
                if (current.start && current.end) {
                    inferBusyFreeKeywords(current)
                    events << current
                }
            }
            inEvent = false
            current = null
            return
        }

        if (!inEvent || current == null) return

        // Key:params:value parsing
        int idx = line.indexOf(":")
        if (idx <= 0) return

        String left = line.substring(0, idx)
        String value = line.substring(idx + 1)

        String key
        Map params = [:]

        if (left.contains(";")) {
            def parts = left.split(";")
            key = parts[0].trim().toUpperCase()
            parts.drop(1).each { p ->
                def kv = p.split("=", 2)
                if (kv.size() == 2) params[kv[0].trim().toUpperCase()] = kv[1].trim()
                else params[p.trim().toUpperCase()] = true
            }
        } else {
            key = left.trim().toUpperCase()
        }

        switch (key) {
            case "UID":
                current.uid = value?.trim()
                break
            case "SUMMARY":
                current.summary = value?.trim()
                break
            case "LOCATION":
                current.location = value?.trim()
                break
            case "STATUS":
                if (value?.trim()?.toUpperCase() == "CANCELLED") current.cancelled = true
                break
            case "TRANSP":
                if (value?.trim()?.toUpperCase() == "TRANSPARENT") current.transparent = true
                break
            case "DTSTART":
                current.dtstartMeta = params
                applyDt(current, true, value, params)
                break
            case "DTEND":
                current.dtendMeta = params
                applyDt(current, false, value, params)
                break
            default:
                break
        }
    }

    // Safety cap
    int cap = (int)Math.max(1, safeLong(maxEvt, 15L))
    if (events.size() > cap * 50) {
        // if someone’s feed is huge, keep the most recent slice
        events.sort { a, b -> a.start.time <=> b.start.time }
        events = events.takeRight(cap * 50)
    }

    return events
}

private void applyDt(Map ev, boolean isStart, String value, Map params) {
    if (!value) return

    String tzid = params?.get("TZID")
    if (tzid) ev.tzid = tzid

    boolean isDateOnly = false
    if ((params?.get("VALUE") ?: "").toString().toUpperCase() == "DATE") {
        isDateOnly = true
    } else if (value ==~ /^\d{8}$/) {
        isDateOnly = true
    }

    Date parsed = parseIcsDate(value.trim(), tzid, isDateOnly)
    if (!parsed) return

    if (isDateOnly) {
        ev.allDay = true
    }

    if (isStart) {
        ev.start = parsed
    } else {
        ev.end = parsed
    }

    // If it's an all-day event and only DTSTART exists, some feeds omit DTEND.
    // If DTEND is missing, set end = start + 1 day (exclusive).
    if (ev.allDay && ev.start && !ev.end) {
        ev.end = new Date(ev.start.time + 24L * 60L * 60L * 1000L)
    }
}

private Date parseIcsDate(String raw, String tzid, boolean dateOnly) {
    try {
        TimeZone tz = tzid ? TimeZone.getTimeZone(tzid) : (location?.timeZone ?: TimeZone.getTimeZone("UTC"))

        if (dateOnly) {
            // DTSTART;VALUE=DATE:YYYYMMDD
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd")
            sdf.setTimeZone(tz)
            return sdf.parse(raw)
        }

        // Date-time forms:
        // YYYYMMDDTHHMMSSZ
        // YYYYMMDDTHHMMSS
        // YYYYMMDDTHHMMZ
        // YYYYMMDDTHHMM
        boolean utc = raw.endsWith("Z")
        String val = utc ? raw.substring(0, raw.length() - 1) : raw

        String pattern
        if (val.size() == 15) pattern = "yyyyMMdd'T'HHmmss"
        else if (val.size() == 13) pattern = "yyyyMMdd'T'HHmm"
        else return null

        SimpleDateFormat sdf = new SimpleDateFormat(pattern)
        sdf.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : tz)
        Date dt = sdf.parse(val)

        // Convert from UTC to hub timezone if UTC input
        if (utc) return dt
        return dt
    } catch (e) {
        if (debugLogging) logDebug("Failed to parse date raw=${raw} tzid=${tzid} dateOnly=${dateOnly} err=${e}")
        return null
    }
}

private List<String> unfoldLines(String text) {
    List<String> rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n") as List
    List<String> out = []
    String current = null

    rawLines.each { String ln ->
        if (ln == null) return
        if (ln.startsWith(" ") || ln.startsWith("\t")) {
            if (current != null) current = current + ln.substring(1)
        } else {
            if (current != null) out << current
            current = ln.trim()
        }
    }
    if (current != null) out << current
    return out
}

private void inferBusyFreeKeywords(Map ev) {
    String sum = (ev.summary ?: "").toLowerCase()

    List<String> busy = splitKeywords(busyKeywords)
    List<String> free = splitKeywords(freeKeywords)

    ev.busyByKeyword = busy.any { kw -> kw && sum.contains(kw.toLowerCase()) }
    ev.freeByKeyword = free.any { kw -> kw && sum.contains(kw.toLowerCase()) }
}

private List<String> splitKeywords(String csv) {
    if (!csv) return []
    return csv.split(",").collect { it.trim() }.findAll { it }
}

private Long safeLong(def v, Long dflt) {
    try {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number)v).longValue()
        return Long.parseLong(v.toString().trim())
    } catch (ignored) {
        return dflt
    }
}

private String safeStr(String s) {
    return (s == null) ? "" : s
}

private void setStateIdle(String reason) {
    sendEvent(name: "inMeeting", value: false)
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "currentSummary", value: null)
    sendEvent(name: "nextSummary", value: null)
    sendEvent(name: "nextEvents", value: "None")
    if (reason) sendEvent(name: "rawDebug", value: reason)
}

private void logDebug(String msg) { if (debugLogging) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg) { log.info "${device.displayName}: ${msg}" }
private void logWarn(String msg) { log.warn "${device.displayName}: ${msg}" }
private void logTrace(String msg) { if (traceLogging) log.trace "${device.displayName}: ${msg}" }
