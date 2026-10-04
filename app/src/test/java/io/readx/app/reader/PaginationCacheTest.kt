package io.readx.app.reader

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PaginationCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = LayoutConfig("book", listOf("a.xhtml", "b.xhtml"), 1080, 2200, 3f, 1f, 20f, 1.8f, 24f, true, "web:1", "system:1", "zh-CN")

    @Test fun everyLayoutDimensionInvalidatesTheFingerprint() {
        val variants = listOf(
            config.copy(bookFingerprint = "other"), config.copy(chapterHrefs = listOf("b.xhtml", "a.xhtml")),
            config.copy(viewportWidth = 1081), config.copy(viewportHeight = 2201), config.copy(density = 2f),
            config.copy(fontScale = 1.5f), config.copy(fontSize = 24f), config.copy(lineHeight = 2f),
            config.copy(margin = 32f), config.copy(serif = false), config.copy(webViewVersion = "web:2"),
            config.copy(systemVersion = "system:2"), config.copy(locales = "en-US"), config.copy(engineVersion = "next"),
            config.copy(chapterHrefs = listOf("a.xhtml|b.xhtml"))
        )
        assertEquals(config.generateKey(), config.copy().generateKey())
        variants.forEach { assertNotEquals(config.generateKey(), it.generateKey()) }
    }

    @Test fun cacheRoundTripsPartialAndExactIndicesAndRejectsCorruption() = runBlocking {
        val cache = PageIndexCache(folder.root)
        val key = config.generateKey()
        cache.save("book", key, BookPageIndex(listOf(3, null, 5)))
        assertEquals(listOf(3, null, 5), cache.load("book", key, 3)!!.counts)
        assertNull(cache.load("book", key, 2))
        assertNull(cache.load("other", key, 3))
        cache.save("book", key, BookPageIndex(listOf(3, 2, 5)))
        assertEquals(10, cache.load("book", key, 3)!!.total)
        val file = File(folder.root, "page-indices-v2").listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.load("book", key, 3))
        cache.save("book", key, BookPageIndex(listOf(1, 2, 3)))
        assertEquals(6, cache.load("book", key, 3)!!.total)
        assertFalse(File(folder.root, "page-indices-v2").listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun limitsFiveLayoutsPerBookAndTwentyFourOverall() = runBlocking {
        val cache = PageIndexCache(folder.root)
        repeat(9) { cache.save("one", config.copy(fontSize = it.toFloat()).generateKey(), BookPageIndex(listOf(1))) }
        val directory = File(folder.root, "page-indices-v2")
        assertEquals(5, directory.listFiles()!!.size)
        repeat(30) { cache.save("book-$it", config.generateKey(), BookPageIndex(listOf(1))) }
        assertEquals(24, directory.listFiles()!!.size)
    }

    @Test fun cancellationPreservesCompletedChaptersAndResumesOnlyMissingOnes() = runBlocking {
        val cache = PageIndexCache(folder.root)
        val key = config.generateKey()
        val coordinator = PaginationCoordinator(cache, "book", key, 4)
        val blocked = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.calculate(1, 7) { chapter ->
                if (chapter == 0) { blocked.complete(Unit); awaitCancellation() }
                3
            }
        }
        blocked.await()
        job.cancelAndJoin()
        val partial = cache.load("book", key, 4)!!
        assertEquals(listOf(null, 7, 3, null), partial.counts)
        assertNull(coordinator.state.value.index.total)
        val measured = mutableListOf<Int>()
        coordinator.calculate(1, 7) { measured += it; 2 }
        assertEquals(listOf(0, 3), measured)
        assertEquals(14, coordinator.state.value.index.total)
        assertNull(coordinator.state.value.error)
    }

    @Test fun exactCacheNeverCallsRendererAndForegroundMismatchInvalidatesIt() = runBlocking {
        val cache = PageIndexCache(folder.root)
        val key = config.generateKey()
        cache.save("book", key, BookPageIndex(listOf(2, 3, 4)))
        val coordinator = PaginationCoordinator(cache, "book", key, 3)
        coordinator.calculate(1, 3) { error("Should not construct a renderer for a cache hit") }
        assertEquals(9, coordinator.state.value.index.total)
        val measured = mutableListOf<Int>()
        coordinator.calculate(1, 5) { measured += it; 1 }
        assertEquals(listOf(2, 0), measured)
        assertEquals(7, coordinator.state.value.index.total)
    }

    @Test fun failureDoesNotPublishAnExactTotalAndCanRetry() = runBlocking {
        val coordinator = PaginationCoordinator(PageIndexCache(folder.root), "book", config.generateKey(), 3)
        coordinator.calculate(0, 3) { error("broken chapter") }
        assertNull(coordinator.state.value.index.total)
        assertEquals("broken chapter", coordinator.state.value.error)
        coordinator.calculate(0, 3) { 2 }
        assertEquals(7, coordinator.state.value.index.total)
        assertNull(coordinator.state.value.error)
    }

    @Test fun chapterPriorityIsBoundedUniqueAndNeighbourFirst() {
        assertEquals(listOf(2, 3, 1, 4, 0), chapterPriority(5, 2))
        assertEquals(listOf(0), chapterPriority(1, 0))
        assertEquals((0 until 10).toList(), chapterPriority(10, 0))
        assertEquals((0 until 10).toList().reversed(), chapterPriority(10, 9))
    }
}
