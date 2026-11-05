/**
 * Glossy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.ui.component

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import com.jay.glossy.utils.blurBitmapCached
import com.jay.glossy.utils.getCachedBlurredImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-bleed blurred artwork backdrop that looks identical on Android 8-11 and
 * Android 12+.
 *
 * Instead of relying on `Modifier.blur(...)` (a silent no-op below API 31) or
 * feeding Coil a tiny thumbnail and hoping bilinear upscale hides the grid,
 * this loads a medium-resolution software bitmap off the main thread, runs a
 * multi-pass Gaussian-approximation blur in [blurBitmapCached], caches the
 * result in an LRU keyed on `(url, blurStrength)`, and crossfades smoothly
 * when the track changes.
 *
 * @param url Artwork URL. Passing `null` or blank renders nothing.
 * @param blurStrength Normalised blur intensity in `0f..1f`. `0.65f` matches a
 *   soft atmospheric player backdrop; `0.85f` dissolves detail into pure
 *   ambient colour washes.
 */
@Composable
fun BlurredArtworkBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    blurStrength: Float = 0.68f,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    crossfadeMillis: Int = 550,
) {
    val context = LocalContext.current
    val clamped = blurStrength.coerceIn(0f, 1f)
    val isLightweightDevice = remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val fewCores = Runtime.getRuntime().availableProcessors() <= 4
        lowRam || fewCores || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    }
    // Lower strength keeps slightly more working resolution so gentle blurs stay
    // crisp-edged rather than muddy; heavy blurs can work on a smaller grid
    // because all high frequencies are wiped out anyway. Older/low-RAM devices
    // step down the working grid slightly for ~2x faster CPU blur with identical
    // smooth ambient gradients.
    val sourcePx = remember(clamped, isLightweightDevice) {
        val base = (380f - clamped * 110f).roundToInt().coerceIn(240, 380)
        if (isLightweightDevice) (base * 0.78f).roundToInt().coerceIn(192, 288) else base
    }
    val radius = remember(clamped, sourcePx) {
        val scaledRadius = (8f + clamped * 44f) * (sourcePx / 340f)
        scaledRadius.roundToInt().coerceIn(4, 56)
    }
    val cacheKey = remember(url, radius, sourcePx) {
        if (url.isNullOrBlank()) null else "$url|r=$radius|s=$sourcePx"
    }

    // Seed synchronously from the in-memory LRU cache so tab switches, sheet
    // expansions, and track revisits render immediately with zero flash or IO.
    var bitmap by remember(url) {
        mutableStateOf<ImageBitmap?>(cacheKey?.let { getCachedBlurredImageBitmap(it) })
    }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null || url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        getCachedBlurredImageBitmap(cacheKey)?.let { cached ->
            bitmap = cached
            return@LaunchedEffect
        }
        val produced = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(sourcePx, sourcePx)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .build()
                val result = withContext(Dispatchers.IO) {
                    context.imageLoader.execute(request)
                }
                if (result is SuccessResult) {
                    val raw = result.image.toBitmap()
                    blurBitmapCached(
                        cacheKey = cacheKey,
                        source = raw,
                        targetLongEdge = sourcePx,
                        radius = radius,
                        passes = 3,
                    )
                } else {
                    null
                }
            }.getOrNull()
        }
        if (produced != null) {
            bitmap = produced
        }
    }

    Crossfade(
        targetState = bitmap,
        animationSpec = tween(durationMillis = crossfadeMillis),
        label = "blurredArtworkCrossfade",
        modifier = modifier,
    ) { current ->
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                alignment = alignment,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}
