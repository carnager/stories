package io.github.carnager.stories.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Progress
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded covers, by file and size, kept while there's room. */
object CoverCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(path: String, px: Int): ImageBitmap? = cache.get("$path@$px")

    suspend fun load(path: String, px: Int): ImageBitmap? = cached(path, px) ?: withContext(Dispatchers.IO) {
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            var sample = 1
            while (options.outWidth / (sample * 2) >= px && options.outHeight / (sample * 2) >= px) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        }.getOrNull()?.also { cache.put("$path@$px", it) }
    }
}

/**
 * A book's cover, square. A book without one gets one made up: its name
 * set on a colour of its own, so a shelf of them is still told apart at
 * a glance.
 */
@Composable
fun Cover(book: Book?, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    BoxWithConstraints(modifier.aspectRatio(1f).clip(RoundedCornerShape(corner))) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val asked = when {
            px <= 160 -> 160
            px <= 320 -> 320
            else -> 640
        }
        val path = book?.cover
        var image by remember(path, asked) { mutableStateOf(path?.let { CoverCache.cached(it, asked) }) }
        LaunchedEffect(path, asked) {
            if (path != null && image == null) image = CoverCache.load(path, asked)
        }
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MadeCover(book?.title.orEmpty(), book?.author.orEmpty(), maxWidth)
        }
    }
}

private val coverHues = listOf(
    Color(0xFF7A4B3A) to Color(0xFFE9C9A8), Color(0xFF2F4F5A) to Color(0xFFCFE3E0),
    Color(0xFF4A3F66) to Color(0xFFDCD2F0), Color(0xFF55603A) to Color(0xFFE3E7C4),
    Color(0xFF6B3446) to Color(0xFFF1CFD8), Color(0xFF3A4766) to Color(0xFFD3DCF2),
    Color(0xFF6A5530) to Color(0xFFF1E0B6), Color(0xFF33584A) to Color(0xFFCDE8DB),
)

@Composable
private fun MadeCover(title: String, author: String, side: Dp) {
    val (ground, ink) = coverHues[(title.hashCode() and 0x7fffffff) % coverHues.size]
    val small = side < 90.dp
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(ground, ground.copy(alpha = 0.82f).compositeOver(Color.Black)))),
    ) {
        // A spine's fold down the left edge.
        Box(Modifier.fillMaxHeight().width(side * 0.06f).background(Color.Black.copy(alpha = 0.18f)))
        Column(Modifier.fillMaxSize().padding(start = side * 0.14f, end = side * 0.09f, top = side * 0.1f, bottom = side * 0.1f)) {
            Box(Modifier.width(side * 0.18f).height(if (small) 1.dp else 2.dp).background(ink.copy(alpha = 0.7f)))
            Text(
                title,
                color = ink,
                fontFamily = BookFace,
                fontWeight = FontWeight.SemiBold,
                fontSize = (side.value * if (small) 0.15f else 0.12f).coerceIn(8f, 30f).sp,
                lineHeight = (side.value * if (small) 0.17f else 0.145f).coerceIn(9f, 36f).sp,
                maxLines = if (small) 3 else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = side * 0.06f).weight(1f),
            )
            if (!small && author.isNotEmpty()) {
                Text(
                    author.uppercase(), color = ink.copy(alpha = 0.75f), fontFamily = Manrope, fontWeight = FontWeight.Bold,
                    fontSize = (side.value * 0.055f).coerceIn(7f, 13f).sp, letterSpacing = 0.8.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Color.compositeOver(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

/**
 * A cover that says how far the book is heard, without a word: a bar along
 * its foot while listening, a tick and a quieter picture once finished, a
 * small dot while still new.
 */
@Composable
fun StatusCover(book: Book, progress: Progress?, modifier: Modifier = Modifier, corner: Dp = 10.dp, playing: Boolean = false) {
    val status = progress.status()
    val tones = LocalTones.current
    Box(modifier) {
        Cover(
            book,
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (status == Status.Finished) 0.55f else 1f }
                .then(if (playing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(corner)) else Modifier),
            corner,
        )
        when (status) {
            Status.Listening -> Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(6.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Box(Modifier.fillMaxWidth(progress!!.fraction.coerceAtLeast(0.03f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            Status.Finished -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(tones.done),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, "Finished", Modifier.size(15.dp), tint = Color.White)
            }
            Status.New -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(10.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * A folder of books: their covers fanned like a small stack, so a series is
 * known by its look, and how much of it is heard.
 */
@Composable
fun FolderCover(books: List<Book>, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val shown = remember(books) { books.distinctBy { it.cover ?: it.id }.take(3) }
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val side = maxWidth * 0.84f
        val step = (maxWidth - side) / 2
        val deepest = shown.lastIndex
        // Centred whatever the count: one cover sits in the middle, three fill the corner to corner.
        val base = step * (2 - deepest) / 2
        for (depth in deepest downTo 0) {
            Box(
                Modifier
                    .offset(x = base + step * depth, y = base + step * (deepest - depth))
                    .size(side)
                    .graphicsLayer { alpha = 1f - depth * 0.15f }
                    .shadow(if (depth == 0) 6.dp else 2.dp, RoundedCornerShape(corner)),
            ) {
                Cover(shown[depth], Modifier.fillMaxSize(), corner)
            }
        }
    }
}

/** A count over a folder's stack, as a small tab. */
@Composable
fun CountTab(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
