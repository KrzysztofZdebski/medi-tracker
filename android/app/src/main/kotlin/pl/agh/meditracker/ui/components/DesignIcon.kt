package pl.agh.meditracker.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.caverock.androidsvg.SVG

/** Original SVG icons from the supplied MedTracker design exports. */
@Composable
fun DesignIcon(@RawRes resource: Int, size: Dp, description: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(resource, pixels) {
        val svg = context.resources.openRawResource(resource).use { SVG.getFromInputStream(it) }
        Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.scale(pixels / svg.documentWidth, pixels / svg.documentHeight)
            svg.renderToCanvas(canvas)
        }.asImageBitmap()
    }
    Image(bitmap, description, modifier.size(size))
}

@Composable
fun DesignBadge(@RawRes resource: Int, size: Dp = 88.dp, iconSize: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size >= 72.dp) 24.dp else 16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        DesignIcon(resource, iconSize)
    }
}
