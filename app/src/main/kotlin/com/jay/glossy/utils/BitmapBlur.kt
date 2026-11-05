/**
 * Glossy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.utils

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Software Gaussian-approximation blur that works identically on every Android
 * API level.
 *
 * Compose's `Modifier.blur` relies on `RenderEffect`, which only exists from
 * Android 12 (API 31) onwards and silently becomes a no-op on older versions.
 * Rather than ship two visual designs — real blur on newer phones and a
 * pixelated upscale or flat tint on older ones — we blur a downscaled copy of
 * the bitmap on the CPU with three passes of a separable box filter (which
 * converges tightly to a true Gaussian by the central limit theorem) and let
 * the GPU bilinearly upscale the smooth result.
 *
 * Performance architecture for older devices:
 *   - All 6 blur passes execute strictly row-major (`stride = 1`) via a pair of
 *     16x16 cache-blocked matrix transposes, eliminating L1 cache thrashing on
 *     vertical passes.
 *   - Per-pixel division is replaced with 24-bit fixed-point reciprocal
 *     multiplication, avoiding millions of integer `sdiv` stalls on Cortex-A53
 *     cores.
 *   - Scratch `IntArray` buffers are pooled thread-locally when possible to
 *     avoid multi-megabyte heap churn during rapid track skips.
 */
private const val BLUR_CACHE_ENTRIES = 12
private const val TRANSPOSE_TILE = 16

private val blurCache = object : LruCache<String, ImageBitmap>(BLUR_CACHE_ENTRIES) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

private class BlurScratchBuffers {
    var bufA: IntArray = IntArray(0)
    var bufB: IntArray = IntArray(0)

    fun ensureCapacity(totalPixels: Int) {
        if (bufA.size < totalPixels) {
            bufA = IntArray(totalPixels)
        }
        if (bufB.size < totalPixels) {
            bufB = IntArray(totalPixels)
        }
    }
}

private val threadScratch = ThreadLocal.withInitial { BlurScratchBuffers() }

/**
 * Synchronous fast-path lookup so Composables can seed `remember(cacheKey)`
 * directly on remount without flashing `null` or scheduling a redundant IO
 * coroutine.
 */
fun getCachedBlurredImageBitmap(cacheKey: String): ImageBitmap? =
    synchronized(blurCache) { blurCache.get(cacheKey) }

/**
 * Returns a blurred [ImageBitmap] for [source], memoised under [cacheKey] so
 * recompositions, tab switches, and repeated visits to the same song never
 * recompute the blur.
 *
 * @param targetLongEdge Longest edge of the working bitmap in pixels before
 *   blurring. Because the output only contains low spatial frequencies, 256-400
 *   is indistinguishable from blurring at full resolution while costing ~30x
 *   less work.
 * @param radius Blur kernel radius in working-bitmap pixels. Effective visual
 *   sigma is roughly `radius * 0.95` in that coordinate space.
 * @param passes Number of box-blur iterations. 3 is the sweet spot where box
 *   blur stops looking boxy and matches a Gaussian within ~2%.
 */
fun blurBitmapCached(
    cacheKey: String,
    source: Bitmap,
    targetLongEdge: Int = 320,
    radius: Int = 28,
    passes: Int = 3,
): ImageBitmap {
    synchronized(blurCache) {
        blurCache.get(cacheKey)?.let { return it }
    }
    val blurred = blurBitmap(
        source = source,
        targetLongEdge = targetLongEdge,
        radius = radius,
        passes = passes,
    ).asImageBitmap()
    synchronized(blurCache) {
        blurCache.put(cacheKey, blurred)
    }
    return blurred
}

fun blurBitmap(
    source: Bitmap,
    targetLongEdge: Int = 320,
    radius: Int = 28,
    passes: Int = 3,
): Bitmap {
    val srcW = source.width.coerceAtLeast(1)
    val srcH = source.height.coerceAtLeast(1)
    val scale = targetLongEdge.toFloat() / maxOf(srcW, srcH).toFloat()
    val w = (srcW * scale).toInt().coerceAtLeast(16)
    val h = (srcH * scale).toInt().coerceAtLeast(16)

    // Ensure a software ARGB_8888 bitmap we can read pixels from, even if the
    // caller handed us a HARDWARE bitmap from Coil.
    val readable = if (source.config == Bitmap.Config.ARGB_8888 && !source.isRecycled) {
        try {
            Bitmap.createScaledBitmap(source, w, h, true)
        } catch (_: IllegalArgumentException) {
            source.copy(Bitmap.Config.ARGB_8888, false).let { sw ->
                val scaled = Bitmap.createScaledBitmap(sw, w, h, true)
                if (scaled !== sw) sw.recycle()
                scaled
            }
        }
    } else {
        val sw = source.copy(Bitmap.Config.ARGB_8888, false)
        val scaled = Bitmap.createScaledBitmap(sw, w, h, true)
        if (scaled !== sw) sw.recycle()
        scaled
    }

    val r = radius.coerceIn(1, minOf(w, h) - 1)
    val total = w * h
    val iterCount = passes.coerceAtLeast(1)

    val scratch = threadScratch.get() ?: BlurScratchBuffers()
    scratch.ensureCapacity(total)
    var cur = scratch.bufA
    var tmp = scratch.bufB

    readable.getPixels(cur, 0, w, 0, 0, w, h)

    // Horizontal passes on (w x h) — strictly row-major.
    repeat(iterCount) {
        boxBlurRowMajor(cur, tmp, w, h, r)
        val swap = cur
        cur = tmp
        tmp = swap
    }

    // Transpose (w x h) -> (h x w) so vertical passes also run strictly row-major.
    transposeTiled(cur, tmp, w, h)
    run {
        val swap = cur
        cur = tmp
        tmp = swap
    }

    // Vertical passes on transposed (h x w) — strictly row-major.
    repeat(iterCount) {
        boxBlurRowMajor(cur, tmp, h, w, r)
        val swap = cur
        cur = tmp
        tmp = swap
    }

    // Transpose back (h x w) -> (w x h).
    transposeTiled(cur, tmp, h, w)
    cur = tmp

    val out = if (readable.isMutable && readable.config == Bitmap.Config.ARGB_8888) {
        readable
    } else {
        val copy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (readable !== source) readable.recycle()
        copy
    }
    out.setPixels(cur, 0, w, 0, 0, w, h)
    return out
}

/**
 * 16x16 cache-blocked matrix transpose from `(srcW x srcH)` into `(srcH x srcW)`.
 */
private fun transposeTiled(
    src: IntArray,
    dst: IntArray,
    srcW: Int,
    srcH: Int,
) {
    var y0 = 0
    while (y0 < srcH) {
        val yEnd = minOf(y0 + TRANSPOSE_TILE, srcH)
        var x0 = 0
        while (x0 < srcW) {
            val xEnd = minOf(x0 + TRANSPOSE_TILE, srcW)
            var y = y0
            while (y < yEnd) {
                val srcRow = y * srcW
                var x = x0
                while (x < xEnd) {
                    dst[x * srcH + y] = src[srcRow + x]
                    x++
                }
                y++
            }
            x0 += TRANSPOSE_TILE
        }
        y0 += TRANSPOSE_TILE
    }
}

/**
 * Sliding-window 1D horizontal box blur in O(1) ops per pixel, independent of
 * [radius]. Edges clamp to the border pixel so artwork colours bleed cleanly to
 * the frame instead of darkening at the perimeter.
 *
 * Uses 24-bit fixed-point reciprocal multiplication so the inner pixel loop has
 * zero integer division instructions.
 */
private fun boxBlurRowMajor(
    src: IntArray,
    dst: IntArray,
    w: Int,
    h: Int,
    radius: Int,
) {
    val div = radius * 2 + 1
    val invDiv = (1L shl 24) / div.toLong()
    val half = 1L shl 23
    val wm1 = w - 1

    for (y in 0 until h) {
        val rowOffset = y * w
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var aSum = 0

        val firstPx = src[rowOffset]
        val fA = (firstPx ushr 24) and 0xFF
        val fR = (firstPx ushr 16) and 0xFF
        val fG = (firstPx ushr 8) and 0xFF
        val fB = firstPx and 0xFF
        aSum += fA * (radius + 1)
        rSum += fR * (radius + 1)
        gSum += fG * (radius + 1)
        bSum += fB * (radius + 1)

        for (i in 1..radius) {
            val px = src[rowOffset + if (i < wm1) i else wm1]
            aSum += (px ushr 24) and 0xFF
            rSum += (px ushr 16) and 0xFF
            gSum += (px ushr 8) and 0xFF
            bSum += px and 0xFF
        }

        for (x in 0 until w) {
            val outA = ((aSum.toLong() * invDiv + half) ushr 24).toInt()
            val outR = ((rSum.toLong() * invDiv + half) ushr 24).toInt()
            val outG = ((gSum.toLong() * invDiv + half) ushr 24).toInt()
            val outB = ((bSum.toLong() * invDiv + half) ushr 24).toInt()
            dst[rowOffset + x] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB

            val addIdx = x + radius + 1
            val remIdx = x - radius
            val addPx = src[rowOffset + if (addIdx < wm1) addIdx else wm1]
            val remPx = src[rowOffset + if (remIdx > 0) remIdx else 0]

            aSum += ((addPx ushr 24) and 0xFF) - ((remPx ushr 24) and 0xFF)
            rSum += ((addPx ushr 16) and 0xFF) - ((remPx ushr 16) and 0xFF)
            gSum += ((addPx ushr 8) and 0xFF) - ((remPx ushr 8) and 0xFF)
            bSum += (addPx and 0xFF) - (remPx and 0xFF)
        }
    }
}
