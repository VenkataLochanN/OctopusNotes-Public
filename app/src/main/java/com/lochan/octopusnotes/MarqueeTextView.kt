package com.lochan.octopusnotes

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd

class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val holdMillis = 2000L

    private val endHoldMillis = 1000L

    private val millisPerViewport = 4000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollAnimator: ValueAnimator? = null
    private var startPending = false
    private var endHoldPending = false

    private var marqueeEnabled = false

    private val startRunnable = Runnable {
        startPending = false
        scrollOnce()
    }

    private val endHoldRunnable = Runnable {
        endHoldPending = false
        scrollX = 0
        if (marqueeEnabled) maybeScheduleStart()
    }

    fun setMarqueeEnabled(enabled: Boolean) {
        if (marqueeEnabled == enabled) return
        marqueeEnabled = enabled
        if (enabled) {

            setHorizontallyScrolling(true)
            ellipsize = null
            maybeScheduleStart()
        } else {
            stopMarquee()

            ellipsize = TextUtils.TruncateAt.END
            setHorizontallyScrolling(false)
            scrollX = 0
            requestLayout()
        }
    }

    private fun textOverflows(): Boolean {
        if (width <= 0) return false
        val textW = paint.measureText(text?.toString() ?: "")
        return textW > width - paddingLeft - paddingRight + 1f
    }

    private fun maybeScheduleStart() {
        if (!marqueeEnabled) return
        if (!textOverflows()) return
        if (startPending || endHoldPending || scrollAnimator?.isRunning == true) return

        scrollX = 0
        startPending = true
        mainHandler.removeCallbacks(startRunnable)
        mainHandler.postDelayed(startRunnable, holdMillis)
    }

    private fun scrollOnce() {
        if (!marqueeEnabled || width <= 0) return
        val textW = paint.measureText(text?.toString() ?: "")
        val scrollable = textW - (width - paddingLeft - paddingRight)
        if (scrollable <= 1f) return
        val viewport = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val scrollDuration = (millisPerViewport * (scrollable / viewport)).toLong()
        scrollAnimator?.cancel()
        val anim = ValueAnimator.ofInt(0, scrollable.toInt()).apply {
            this.duration = scrollDuration
            interpolator = LinearInterpolator()
            addUpdateListener { scrollX = it.animatedValue as Int }
            doOnEnd {

                if (marqueeEnabled) {
                    endHoldPending = true
                    mainHandler.removeCallbacks(endHoldRunnable)
                    mainHandler.postDelayed(endHoldRunnable, endHoldMillis)
                }
            }
        }
        scrollAnimator = anim
        anim.start()
    }

    private fun stopMarquee() {
        mainHandler.removeCallbacks(startRunnable)
        mainHandler.removeCallbacks(endHoldRunnable)
        startPending = false
        endHoldPending = false
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)

        if (marqueeEnabled) {
            stopMarquee()
            scrollX = 0
            maybeScheduleStart()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (marqueeEnabled) {
            stopMarquee()
            scrollX = 0
            maybeScheduleStart()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (marqueeEnabled) maybeScheduleStart()
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        super.onDetachedFromWindow()
    }
}
