package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject

data class PageTemplate(
    val type: String,
    val bgColor: Int,
    val density: Float,
    val brightness: Float,
    val thickness: Float,
    val lineColor: Int,
    val pageW: Int,
    val pageH: Int,
    val isCustom: Boolean,
    val customUri: String?
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        put("bgColor", bgColor)
        put("density", density.toDouble())
        put("brightness", brightness.toDouble())
        put("thickness", thickness.toDouble())
        put("lineColor", lineColor)
        put("pageSizeWidth", pageW)
        put("pageSizeHeight", pageH)
        put("isCustom", isCustom)
        put("customUri", customUri)
    }.toString()

    fun renderBitmap(ctx: Context, wPx: Int, hPx: Int): Bitmap {
        val w = wPx.coerceAtLeast(1)
        val h = hPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (isCustom && !customUri.isNullOrBlank()) {
            try {
                val src = decodeSoftwareBitmap(ctx, Uri.parse(customUri))
                    ?: throw IllegalStateException("decode failed")
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(src, null, Rect(0, 0, w, h), null)
                return bmp
            } catch (e: Exception) {
                canvas.drawColor(Color.WHITE)
                return bmp
            }
        }

        canvas.drawColor(bgColor)
        if (type != "BLANK") {
            val scaleX = w.toFloat() / pageW
            val scaleY = h.toFloat() / pageH
            val spacing = (80f - density * 5f).coerceAtLeast(8f) * scaleY
            val paint = Paint().apply {
                color = lineColor
                strokeWidth = (thickness * 0.5f) * scaleX
                style = Paint.Style.STROKE
                isAntiAlias = true
                alpha = ((brightness / 10f) * 255).toInt().coerceIn(0, 255)
            }
            when (type) {
                "RULE" -> {
                    var y = spacing
                    while (y < h) { canvas.drawLine(0f, y, w.toFloat(), y, paint); y += spacing }
                }
                "GRID" -> {
                    var y = spacing
                    while (y < h) { canvas.drawLine(0f, y, w.toFloat(), y, paint); y += spacing }
                    var x = spacing
                    while (x < w) { canvas.drawLine(x, 0f, x, h.toFloat(), paint); x += spacing }
                }
                "DOTS" -> {
                    val dot = Paint(paint).apply { style = Paint.Style.FILL }
                    val r = (thickness * 0.7f) * scaleX
                    var y = spacing
                    while (y < h) {
                        var x = spacing
                        while (x < w) { canvas.drawCircle(x, y, r, dot); x += spacing }
                        y += spacing
                    }
                }
            }
        }
        return bmp
    }

    companion object {
        fun fromJson(s: String?): PageTemplate? {
            if (s.isNullOrBlank()) return null
            return try {
                val j = JSONObject(s)
                PageTemplate(
                    type = j.optString("type", "BLANK"),
                    bgColor = j.optInt("bgColor", Color.WHITE),
                    density = j.optDouble("density", 4.0).toFloat(),
                    brightness = j.optDouble("brightness", 5.0).toFloat(),
                    thickness = j.optDouble("thickness", 5.0).toFloat(),
                    lineColor = j.optInt("lineColor", Color.parseColor("#D0D0D0")),
                    pageW = j.optInt("pageSizeWidth", 595),
                    pageH = j.optInt("pageSizeHeight", 842),
                    isCustom = j.optBoolean("isCustom", false),
                    customUri = j.optString("customUri", null)
                        ?.takeIf { it.isNotBlank() && it != "null" }
                )
            } catch (e: Exception) {
                null
            }
        }

        fun decodeSoftwareBitmap(ctx: Context, uri: Uri): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT < 28) {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                } else {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun copyTemplateImage(ctx: Context, src: Uri): Uri {
            return try {
                val dir = java.io.File(ctx.filesDir, "templates").apply { mkdirs() }
                val dest = java.io.File(dir, "tpl_${System.currentTimeMillis()}.png")
                ctx.contentResolver.openInputStream(src)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return src
                Uri.fromFile(dest)
            } catch (e: Exception) {
                src
            }
        }

        fun preset(type: String, pageW: Int, pageH: Int): PageTemplate =
            PageTemplate(
                type = type,
                bgColor = Color.WHITE,
                density = 4f,
                brightness = 6f,
                thickness = 5f,
                lineColor = Color.parseColor("#C8C8C8"),
                pageW = pageW,
                pageH = pageH,
                isCustom = false,
                customUri = null
            )
    }
}
