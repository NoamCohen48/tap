package io.github.noamcohen48.tap.fixture

import android.content.ClipData
import android.os.Bundle
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Targets for the multi-step gestures, each recognised the way apps usually do it, so a test
 * proves the gesture was received as that gesture and not just as touches: a `GestureDetector`
 * double tap, a long-press `startDragAndDrop` onto a drop target, and a `ScaleGestureDetector`
 * pinch. Every result is shown as text.
 */
class GestureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture)

        val doubleTapStatus = findViewById<TextView>(R.id.double_tap_status)
        var doubleTaps = 0
        val doubleTapDetector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        doubleTapStatus.text = "Single tap"
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        doubleTapStatus.text = "Double taps: ${++doubleTaps}"
                        return true
                    }
                },
            )
        findViewById<View>(R.id.double_tap_target).setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            doubleTapDetector.onTouchEvent(event)
        }

        val dragStatus = findViewById<TextView>(R.id.drag_status)
        findViewById<View>(R.id.drag_source).setOnLongClickListener { view ->
            dragStatus.text = "Dragging"
            view.startDragAndDrop(ClipData.newPlainText("card", "Card"), View.DragShadowBuilder(view), null, 0)
        }
        findViewById<View>(R.id.drop_target).setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> dragStatus.text = "Over the drop target"
                DragEvent.ACTION_DROP -> dragStatus.text = "Dropped ${event.clipData.getItemAt(0).text}"
            }
            true
        }

        val pinchStatus = findViewById<TextView>(R.id.pinch_status)
        var scale = 1f
        val pinchDetector =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        scale = 1f
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        scale *= detector.scaleFactor
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        pinchStatus.text =
                            when {
                                scale > 1.1f -> "Zoomed in"
                                scale < 0.9f -> "Zoomed out"
                                else -> "Pinch too small"
                            }
                    }
                },
            )
        findViewById<View>(R.id.pinch_target).setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            pinchDetector.onTouchEvent(event)
        }
    }
}
