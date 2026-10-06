package io.readx.app.conversion

/** One native recognizer + bounded 4M-pixel bitmap per slot; no unbounded page queue. */
internal object OcrParallelism {
    private const val MIB=1024L*1024
    private const val SLOT_BYTES=80L*MIB
    private const val BITMAP_BYTES=16L*MIB
    fun workers(processors:Int,memoryClassMb:Int,availableBytes:Long,lowMemory:Boolean,pages:Int):Int {
        if(lowMemory)return 1
        // Native model memory is not the Java heap. Bound these budgets separately so
        // a 256MiB heap on a high-RAM eight-core phone can still use all eight cores.
        val nativeBudget=minOf(availableBytes/4,640L*MIB)
        val bitmapBudget=(memoryClassMb.toLong()*MIB-64L*MIB).coerceAtLeast(BITMAP_BYTES)
        return minOf(processors.coerceAtLeast(1),(nativeBudget/SLOT_BYTES).toInt().coerceAtLeast(1),
            (bitmapBudget/BITMAP_BYTES).toInt().coerceAtLeast(1),pages.coerceAtLeast(1))
    }
}
