package com.openreplay.tracker.analytics

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openreplay.tracker.managers.DebugUtils
//import com.openreplay.tracker.analytics.AnalyticsManager
import java.lang.ref.WeakReference

class ViewTrackingManager(private val rootView: View) : DefaultLifecycleObserver {

    private val observedViews = mutableSetOf<View>()
    private val rootViewReference = WeakReference(rootView)

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        rootViewReference.get()?.viewTreeObserver?.addOnGlobalLayoutListener(globalLayoutListener)
        trackAllViews(rootView)
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        rootViewReference.get()?.viewTreeObserver?.removeOnGlobalLayoutListener(globalLayoutListener)
        clearListeners()
    }

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        // Triggered when the view hierarchy changes
        trackAllViews(rootView)
    }

    private fun trackAllViews(view: View?) {
        if (view == null || view in observedViews) return

        if (isCompositeView(view)) {
            addCompositeViewListener(view)
        } else {
            addListeners(view)
        }

        observedViews.add(view)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                trackAllViews(view.getChildAt(i))
            }
        }
    }

    private fun isCompositeView(view: View): Boolean {
        // Check if the view is a parent container for composite elements like tabs
        return view.tag?.toString()?.contains("tab") == true ||
                view is ViewGroup && view.childCount > 1 && containsSpecificChildViews(view)
    }

    private fun containsSpecificChildViews(viewGroup: ViewGroup): Boolean {
        // Detect if the ViewGroup contains children like icons and text
        var hasIcon = false
        var hasText = false

        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is TextView) hasText = true
            if (child is View && child.tag?.toString() == "icon") hasIcon = true
        }

        return hasIcon && hasText
    }

    private fun addCompositeViewListener(view: View) {
        view.setOnClickListener {
            val label = view.contentDescription?.toString() ?: "Composite View Clicked"
            DebugUtils.log("Composite view clicked: $label")
//            AnalyticsManager.sendClick(
//                MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, view.x, view.y, 0),
//                label
//            )
        }
    }

    private fun addListeners(view: View) {
        view.setOnClickListener {
            val label = view.contentDescription?.toString() ?: view.javaClass.simpleName
            DebugUtils.log("View clicked: $label")
//            AnalyticsManager.sendClick(
//                MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, view.x, view.y, 0),
//                label
//            )
        }
    }

    private fun clearListeners() {
        for (view in observedViews) {
            view.setOnClickListener(null)
        }
        observedViews.clear()
    }
}