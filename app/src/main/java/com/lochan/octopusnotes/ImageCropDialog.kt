package com.lochan.octopusnotes

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageCropDialog(
    context: Context,
    private val uri: Uri,
    private val onCropped: (Bitmap) -> Unit
) : Dialog(context, R.style.Theme_OctopusNotes) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var cropView: ImageCropView
    private var sourceBitmap: Bitmap? = null
    private var released = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.black)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(R.layout.dialog_image_crop)

        cropView = findViewById(R.id.cropView)
        findViewById<View>(R.id.cropCancelButton).setOnClickListener { dismiss() }
        findViewById<View>(R.id.cropRotateButton).setOnClickListener { rotate() }
        findViewById<View>(R.id.cropDoneButton).setOnClickListener { confirmCrop() }
        setCancelable(true)

        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeSubsampled() }
            if (bmp == null) {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }
            sourceBitmap = bmp
            cropView.setBitmap(bmp)
        }
    }

    private fun decodeSubsampled(): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                null
            } else {
                val maxDim = 2560
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun rotate() {
        val bmp = sourceBitmap ?: return
        val rotated = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            android.graphics.Matrix().apply { postRotate(90f) }, true
        )
        if (rotated !== bmp) {
            sourceBitmap = rotated
            bmp.recycle()
        }
        cropView.setBitmap(sourceBitmap!!)
    }

    private fun confirmCrop() {
        val bmp = sourceBitmap ?: return
        val r = cropView.cropRectInBitmap()
        if (r.width() < 2 || r.height() < 2) return
        val cropped = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())

        sourceBitmap = null
        cropView.clearBitmap()
        if (cropped !== bmp) bmp.recycle()
        onCropped(cropped)
        dismiss()
    }

    private fun release() {
        if (released) return
        released = true
        scope.cancel()
        cropView.clearBitmap()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }

    override fun dismiss() {
        super.dismiss()
        release()
    }
}
