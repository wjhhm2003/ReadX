package io.readx.app.reader

import android.os.SystemClock
import io.readx.app.BuildConfig

/** Debug-only diagnostics. Records durations, never titles, text, notes, paths or locators. */
object ReaderPerformance {
    private var origin = 0L
    private val events = linkedMapOf<String, Long>()
    @Synchronized fun begin() { if (BuildConfig.DEBUG) { origin=SystemClock.elapsedRealtime(); events.clear(); events["open"]=0 } }
    @Synchronized fun mark(stage: String) { if(BuildConfig.DEBUG && origin>0) events.putIfAbsent(stage,SystemClock.elapsedRealtime()-origin) }
    @Synchronized fun snapshot(): Map<String,Long> = events.toMap()
}
