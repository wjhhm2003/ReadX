package io.readx.app.ui

import io.readx.app.data.Book
import io.readx.app.data.progress
import java.text.Collator
import java.util.Locale

enum class ShelfSort(val label:String) { RECENT("最近阅读"), PROGRESS("阅读进度"), IMPORTED("导入时间"), TITLE("书名"), SIZE("文件大小") }
internal fun sortShelf(books:List<Book>,sort:ShelfSort,sizes:Map<String,Long>):List<Book> {
    val title=Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    val ordering=when(sort) {
        ShelfSort.RECENT->compareByDescending<Book> {it.lastReadAt}.thenByDescending {it.importedAt}
        ShelfSort.PROGRESS->compareByDescending<Book> {it.progress() ?: -1}.thenBy {it.title}
        ShelfSort.IMPORTED->compareByDescending<Book> {it.importedAt}
        ShelfSort.TITLE->Comparator<Book> {a,b->title.compare(a.title,b.title)}
        ShelfSort.SIZE->compareByDescending<Book> {sizes[it.id] ?: -1L}
    }
    return books.sortedWith(ordering.thenBy {it.id})
}
