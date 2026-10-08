package top.fifthlight.blazerod.runtime.renderer

import org.lwjgl.opengl.ARBTimerQuery
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.slf4j.LoggerFactory

/** Non-blocking GPU timing for the compute renderer. Results are read only after the GPU reports them ready. */
internal object BlazeRodGpuTimer {
    private enum class Segment(val label: String) {
        TRANSFORM("transform"),
        IRIS_ATTRIBUTES("irisAttributes"),
        DRAW("draw"),
    }

    private class QuerySlot {
        val start = GL15.glGenQueries()
        val end = GL15.glGenQueries()
        var pending = false
    }

    private const val SLOT_COUNT = 6
    private const val REPORT_INTERVAL_NANOS = 5_000_000_000L
    private val logger = LoggerFactory.getLogger("BlazeRodPerformance")
    private var capabilityChecked = false
    private var supported = false
    private var querySlots: Array<Array<QuerySlot>>? = null
    private var reportStartNanos = 0L
    private val elapsedNanos = LongArray(Segment.values().size)
    private val completedSamples = IntArray(Segment.values().size)

    private fun initialize() {
        if (capabilityChecked) return
        capabilityChecked = true
        supported = GL.getCapabilities().GL_ARB_timer_query
        if (!supported) {
            logger.info("[BlazeRodPerf] GPU timer queries are unavailable; CPU timings remain enabled")
            return
        }
        querySlots = Array(Segment.values().size) {
            Array(SLOT_COUNT) { QuerySlot() }
        }
        reportStartNanos = System.nanoTime()
    }

    private fun collectCompletedQueries() {
        val slots = querySlots ?: return
        for (segmentIndex in slots.indices) {
            for (slot in slots[segmentIndex]) {
                if (!slot.pending || GL15.glGetQueryObjecti(slot.end, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    continue
                }
                val startNanos = ARBTimerQuery.glGetQueryObjecti64(slot.start, GL15.GL_QUERY_RESULT)
                val endNanos = ARBTimerQuery.glGetQueryObjecti64(slot.end, GL15.GL_QUERY_RESULT)
                elapsedNanos[segmentIndex] += (endNanos - startNanos).coerceAtLeast(0L)
                completedSamples[segmentIndex]++
                slot.pending = false
            }
        }
    }

    fun <T> measure(segment: Int, block: () -> T): T {
        initialize()
        val slot = if (supported) {
            collectCompletedQueries()
            querySlots?.get(segment)?.firstOrNull { !it.pending }
        } else {
            null
        }
        if (slot != null) {
            ARBTimerQuery.glQueryCounter(slot.start, ARBTimerQuery.GL_TIMESTAMP)
        }
        try {
            return block()
        } finally {
            if (slot != null) {
                ARBTimerQuery.glQueryCounter(slot.end, ARBTimerQuery.GL_TIMESTAMP)
                slot.pending = true
            }
        }
    }

    fun reportIfReady() {
        initialize()
        if (!supported) return
        collectCompletedQueries()
        val now = System.nanoTime()
        if (now - reportStartNanos < REPORT_INTERVAL_NANOS) return
        val values = Segment.values().mapIndexed { index, segment ->
            val samples = completedSamples[index]
            val averageMs = if (samples == 0) 0.0 else elapsedNanos[index] / samples / 1_000_000.0
            "${segment.label}=${String.format(java.util.Locale.ROOT, "%.2f", averageMs)}ms/$samples"
        }
        logger.info("[BlazeRodPerf] GPU averages over {}ms: {}", (now - reportStartNanos) / 1_000_000L, values.joinToString(" "))
        reportStartNanos = now
        elapsedNanos.fill(0L)
        completedSamples.fill(0)
    }

    fun close() {
        querySlots?.forEach { segment ->
            segment.forEach { slot ->
                GL15.glDeleteQueries(slot.start)
                GL15.glDeleteQueries(slot.end)
            }
        }
        querySlots = null
        capabilityChecked = false
        supported = false
    }

    const val TRANSFORM = 0
    const val IRIS_ATTRIBUTES = 1
    const val DRAW = 2
}
