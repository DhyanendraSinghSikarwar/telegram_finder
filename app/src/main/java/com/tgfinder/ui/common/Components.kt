package com.tgfinder.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.tgfinder.AppContainer
import com.tgfinder.TgFinderApp
import com.tgfinder.search.VideoVersion
import com.tgfinder.ui.theme.ImdbYellow
import java.io.File
import java.util.Locale

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

/** Creates a ViewModel that receives the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as TgFinderApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = ImdbYellow, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text(String.format(Locale.US, "%.1f", rating), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SmallBadge(text: String, modifier: Modifier = Modifier, background: Color = ImdbYellow, foreground: Color = Color.Black) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = foreground,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/**
 * The Telegram-provided thumbnail for a video: the tiny inline preview first, then the real
 * thumbnail once TDLib has fetched it.
 */
@Composable
fun TelegramThumb(version: VideoVersion?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val td = LocalContainer.current.td
    val thumbId = version?.thumbFileId ?: 0
    var path by remember(thumbId) { mutableStateOf<String?>(null) }
    LaunchedEffect(thumbId) {
        if (thumbId != 0) path = runCatching { td.downloadSmallFile(thumbId) }.getOrNull()
    }
    val mini = remember(version?.key) {
        version?.miniThumb?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            path != null -> AsyncImage(model = File(path!!), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
            mini != null -> Image(mini, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize().blur(6.dp))
            else -> Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
}

/** Poster-shaped card used in the results and downloads grids. */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    fallback: VideoVersion?,
    fallbackPath: String? = null,
    rating: Double? = null,
    topEndBadge: String? = null,
    bottomStartBadge: String? = null,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio2x3()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            when {
                posterUrl != null -> AsyncImage(model = posterUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                fallbackPath != null -> AsyncImage(model = File(fallbackPath), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> {
                    TelegramThumb(fallback, Modifier.fillMaxSize())
                }
            }
            if (posterUrl == null && !loading) {
                // Fallback card: cleaned file name over the Telegram thumbnail.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
                )
                Text(
                    title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            if (rating != null && rating > 0) RatingBadge(rating, Modifier.align(Alignment.TopStart).padding(4.dp))
            if (topEndBadge != null) SmallBadge(topEndBadge, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (bottomStartBadge != null && posterUrl != null) {
                SmallBadge(bottomStartBadge, Modifier.align(Alignment.BottomStart).padding(4.dp), Color.Black.copy(alpha = 0.75f), Color.White)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

fun Modifier.aspectRatio2x3(): Modifier = this.aspectRatio(2f / 3f)

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        BannerKind.WARNING -> Color(0xFF4A3B00) to ImdbYellow
        BannerKind.ERROR -> Color(0xFF4A1414) to Color(0xFFFFB4AB)
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun CenteredMessage(title: String, body: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (body != null) {
            Spacer(Modifier.size(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        content()
    }
}

fun formatDuration(seconds: Int): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${seconds % 60}s"
}

fun formatRuntime(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
