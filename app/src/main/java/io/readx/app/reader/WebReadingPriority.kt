package io.readx.app.reader

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Hidden Chromium work is cancelled on user input, resumes after a bounded idle window. */
internal object WebReadingPriority {
    val epoch=MutableStateFlow(0L)
    private var touched=0L
    fun input() {val now=SystemClock.uptimeMillis();touched=now;if(now-epoch.value>=80)epoch.value=now}
    suspend fun awaitIdle() {while(SystemClock.uptimeMillis()-touched<600) delay(40)}
}
