package com.explo.capstone.shared.data.log

import com.explo.capstone.shared.SecurityEvent
import com.explo.capstone.shared.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Append-only ring buffer for security events.
 * Capped at [MAX_EVENTS] entries — oldest discarded when full.
 */
class SecurityEventLog(private val maxEvents: Int = MAX_EVENTS) {

    companion object {
        const val MAX_EVENTS = 100
    }

    private val _events = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val events: StateFlow<List<SecurityEvent>> = _events.asStateFlow()

    fun emit(severity: Severity, source: String, text: String) {
        val event = SecurityEvent(
            id = "EVT-${UUID.randomUUID().toString().take(8).uppercase()}",
            tsMs = System.currentTimeMillis(),
            severity = severity,
            source = source,
            text = text,
        )
        _events.value = (_events.value + event).takeLast(maxEvents)
    }

    /** Returns the last [n] events, most recent first. */
    fun recent(n: Int = 3): List<SecurityEvent> =
        _events.value.takeLast(n).reversed()

    /** Serializes all events to a JSON string for SAF export. */
    fun exportJson(): String {
        val sb = StringBuilder("[\n")
        _events.value.forEachIndexed { i, evt ->
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(evt.tsMs))
            sb.append("  {\"id\":\"${evt.id}\",\"ts\":\"$ts\",\"severity\":\"${evt.severity}\",\"source\":\"${evt.source}\",\"text\":\"${evt.text.replace("\"","\\\"")}\"}${if (i < _events.value.lastIndex) "," else ""}\n")
        }
        sb.append("]")
        return sb.toString()
    }

    fun clear() {
        _events.value = emptyList()
    }
}
