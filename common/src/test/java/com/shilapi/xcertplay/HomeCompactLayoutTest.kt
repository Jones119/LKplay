package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeCompactLayoutTest {
    @Config(qualifiers = "en-w2667dp-h1333dp")
    @Test fun fullSizeMultiWindowKeepsTheFullHome() {
        assertFalse(homeIsCompact(multiWindow = true))
    }

    @Config(qualifiers = "en-w1333dp-h400dp")
    @Test fun shortWindowUsesTheCompactHome() {
        assertTrue(homeIsCompact(multiWindow = true))
    }

    @Config(qualifiers = "en-w500dp-h800dp")
    @Test fun narrowWindowUsesTheCompactHome() {
        assertTrue(homeIsCompact(multiWindow = false))
    }

    private fun homeIsCompact(multiWindow: Boolean): Boolean {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        shadowOf(activity).setInMultiWindowMode(multiWindow)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        // The wide home is the row of [gauge card | spacer | right column] with weighted children; the
        // compact home stacks the cards in one column, so no such row exists. Probing the weights survives a
        // copy change - the old probe looked for a headline string and broke when the design replaced it.
        return !descendants(activity.window.decorView).filterIsInstance<LinearLayout>().any { row ->
            row.orientation == LinearLayout.HORIZONTAL && row.childCount == 3 && row.getChildAt(0) is FrameLayout &&
                (row.getChildAt(0).layoutParams as? LinearLayout.LayoutParams)?.weight?.let { it > 0.4f } == true
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}



