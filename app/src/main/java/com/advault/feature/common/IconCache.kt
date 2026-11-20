package com.advault.feature.common

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide cache of rasterised app icons, keyed by package name + pixel size. */
private object IconBitmapCache {
    // ~250 icons at 48dp/xxhdpi is roughly 5 MB.
    val cache = LruCache<String, ImageBitmap>(250)
}

/**
 * Rasterises [drawable] to an [ImageBitmap] off the main thread and caches it.
 * Returns null while loading or if [drawable] is null.
 */
@Composable
fun rememberIconBitmap(key: String, drawable: Drawable?, size: Dp): ImageBitmap? {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val cacheKey = "$key@$px"
    val bitmap by produceState(initialValue = IconBitmapCache.cache.get(cacheKey), cacheKey, drawable) {
        if (value == null && drawable != null) {
            value = withContext(Dispatchers.Default) {
                runCatching { drawable.toBitmap(px, px).asImageBitmap() }.getOrNull()
            }?.also { IconBitmapCache.cache.put(cacheKey, it) }
        }
    }
    return bitmap
}
