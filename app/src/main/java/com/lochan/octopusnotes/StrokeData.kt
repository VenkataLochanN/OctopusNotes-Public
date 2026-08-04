package com.lochan.octopusnotes

import android.graphics.Paint
import android.graphics.Path
import java.util.UUID

data class StrokeData(
    val id: String = UUID.randomUUID().toString(), // Added unique ID
    val path: Path,
    val paint: Paint,
    val isPixelEraser: Boolean = false,
    val type: String = StrokeType.PEN,
    /** Pen line style: "SOLID" (default), "DOTTED" or "DASHED". Pen strokes only. */
    val lineStyle: String = PenLineStyle.SOLID,
    /**
     * IMAGE strokes only: filename (relative to the app's images dir) of the inserted
     * picture. The stroke's [path] is the destination rectangle in page space — all the
     * generic machinery (lasso hit-test, move/resize matrices, undo, codec bounds)
     * operates on that rect; rendering draws the bitmap into the rect's bounds instead
     * of stroking the path.
     */
    val imageFile: String? = null,
    /**
     * IMAGE strokes only: degrees the picture is turned clockwise about the centre of its
     * [path] rect when drawn. The rect itself always stays axis-aligned and keeps the
     * picture's *unturned* width and height — so a quarter-turned image has a rect that
     * looks "sideways" and is drawn spun into place. Keeping the frame axis-aligned is what
     * lets hit-testing, resizing and the codec's bounds keep working unchanged.
     *
     * A `var` because the live selection spins its working copies in place during a drag.
     */
    var imageRotation: Float = 0f
) {
    /**
     * The exact contour points this stroke was last encoded from / decoded to.
     * When present and the path hasn't changed, the save file gets these points verbatim —
     * WITHOUT this, every save re-samples the path at fixed arc-length steps, and the
     * sampling error compounds across save/load cycles, progressively eroding corners
     * and curves ("strokes get more deformed every time I reopen").
     *
     * Deliberately a body property, not a constructor parameter: `copy()` — which every
     * path-replacing operation uses — resets it to null, forcing a fresh one-time
     * flatten of the new geometry on the next save. In-place path mutations must null
     * it explicitly (see StrokeManager's rotate/flip).
     */
    var savedContours: List<FloatArray>? = null
}

/** Pen stroke line styles. Kept as string constants for stable serialization. */
object PenLineStyle {
    const val SOLID = "SOLID"
    const val DOTTED = "DOTTED"
    const val DASHED = "DASHED"

    /**
     * Builds the [android.graphics.PathEffect] for a given style at a given stroke width,
     * or null for [SOLID]. Dash metrics scale with width so the look is consistent across sizes.
     */
    fun pathEffect(style: String, width: Float): android.graphics.PathEffect? {
        val w = width.coerceAtLeast(1f)
        return when (style) {
            // Round-capped zero-length "on" segments render as dots.
            DOTTED -> android.graphics.DashPathEffect(floatArrayOf(0.01f, w * 2.2f), 0f)
            DASHED -> android.graphics.DashPathEffect(floatArrayOf(w * 2.6f, w * 2.2f), 0f)
            else -> null
        }
    }
}