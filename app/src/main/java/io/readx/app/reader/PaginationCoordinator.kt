package io.readx.app.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal data class PaginationState(val index: BookPageIndex, val error: String? = null)

/** Session-owned. The caller's structured Job cancels measurement, IO and stale state publication. */
internal class PaginationCoordinator(
    private val cache: PageIndexCache,
    private val book: String,
    private val key: String,
    private val chapterCount: Int,
) {
    private val mutableState = MutableStateFlow(PaginationState(BookPageIndex(List(chapterCount) { null })))
    val state = mutableState.asStateFlow()

    suspend fun calculate(current: Int, foregroundPages: Int, measure: suspend (Int) -> Int) {
        var index = BookPageIndex(List(chapterCount) { null })
        fun publish(error: String? = null) { mutableState.value = PaginationState(index, error) }
        suspend fun persist() {
            // Cache failure must not prevent reading or genuine in-memory totals. Cancellation is never swallowed.
            try { cache.save(book, key, index) } catch (_: IOException) { }
        }
        try {
            index = cache.load(book, key, chapterCount) ?: index
            coroutineContext.ensureActive()
            val cachedCurrent = index.counts[current]
            if (cachedCurrent != null && cachedCurrent != foregroundPages) {
                // Foreground is authoritative; invalidate the whole index if this renderer disagrees.
                index = BookPageIndex(List(chapterCount) { null })
            }
            val changed = index.counts[current] != foregroundPages
            index = index.withCount(current, foregroundPages)
            publish()
            if (changed) persist()
            for (chapter in chapterPriority(chapterCount, current)) {
                coroutineContext.ensureActive()
                if (index.counts[chapter] != null) continue
                val count = measure(chapter)
                coroutineContext.ensureActive()
                index = index.withCount(chapter, count)
                publish()
                persist() // Save every completed chapter: leaving/reformatting cannot lose an entire batch.
                yield()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { publish(error.message ?: "全书分页统计失败，可重试") }
    }
}

/** Current neighbours first, then expand outward. Every ordinal occurs exactly once. */
internal fun chapterPriority(count: Int, current: Int): List<Int> {
    require(count > 0 && current in 0 until count)
    return buildList {
        add(current)
        for (distance in 1 until count) {
            if (current + distance < count) add(current + distance)
            if (current - distance >= 0) add(current - distance)
        }
    }
}
