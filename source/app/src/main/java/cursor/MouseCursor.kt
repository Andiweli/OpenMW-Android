/*
    Copyright (C) 2018, 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package cursor

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout

import com.libopenmw.openmw.R

import org.libsdl.app.SDLActivity

import ui.activity.GameActivity
import ui.activity.MainActivity
import ui.activity.MouseMode
import ui.controls.Osc

import android.graphics.BitmapFactory
import constants.Constants
import java.io.File

/**
 * An image view which doesn't downsize itself when moved to the border of a RelativeLayout
 * (not sure if original behavior is intended)
 */
internal class FixedSizeImageView : androidx.appcompat.widget.AppCompatImageView {

    // Just something so that it's visible
    private var fixedWidth = 10
    private var fixedHeight = 10

    // Default constructors for studio UI editor
    constructor(context: Context): super(context)
    constructor(context: Context, attrs: AttributeSet): super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyle: Int):
        super(context, attrs, defStyle)

    /**
     * @param w Fixed width of this view
     * @param h Fixed height of this view
     */
    constructor(context: Context, w: Int, h: Int): super(context) {
        fixedWidth = w
        fixedHeight = h
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(fixedWidth, fixedHeight)
    }
}

class MouseCursor(activity: GameActivity, private val osc: Osc?) : Choreographer.FrameCallback {

    private val choreographer: Choreographer
    private val cursor: FixedSizeImageView
    private var prevMouseShown = -1
    private var shownFrames = 0
    private var lastMouseX = 0
    private var lastMouseY = 0
    private val useChromeOsSystemCursor =
        Build.VERSION.SDK_INT >= 24 && SDLActivity.isChromebook() // OPENMW_CHROMEOS_NATIVE_MOUSE_V1

    init {
        // Handheld screens are small and close to the eye, so the arrow is half
        // size there. The bitmap is 30x45 and the view keeps that aspect, so the
        // tip stays the hotspot.
        val arrowDp = if (SDLActivity.sHandheldMode) 15f else 30f
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, arrowDp,
            activity.resources.displayMetrics).toInt()
        val width = Math.round(height / 1.5).toInt()

        cursor = FixedSizeImageView(activity, width, height)
        cursor.scaleType = ImageView.ScaleType.FIT_XY

        if (!useChromeOsSystemCursor) {
            if (File(Constants.USER_FILE_STORAGE + "/icons/pointer_arrow.png").exists())
                cursor.setImageBitmap(BitmapFactory.decodeFile(Constants.USER_FILE_STORAGE + "/icons/pointer_arrow.png"))
            else
                cursor.setImageResource(R.drawable.pointer_arrow)

            cursor.layoutParams = RelativeLayout.LayoutParams(width, height)
            activity.layout.addView(cursor)
        }

        choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        // Check if we need to switch osc widgets visibility
        val mouseShown = SDLActivity.isMouseShown()
        if (osc != null && mouseShown != prevMouseShown) {
            if (GameActivity.mouseMode == MouseMode.Touch) {
                // If the player has default mouse-mode enabled, trigger it here
                osc.mouseVisible = mouseShown != 0
            }
            osc.showBasedOnState()
        }

        if (useChromeOsSystemCursor) {
            // SDL_ShowCursor() / Android PointerIcon owns the visible ChromeOS cursor.
            // Do not mirror SDL_GetMouseState() into an ImageView once per UI frame.
            prevMouseShown = mouseShown
            choreographer.postFrameCallback(this)
            return
        }

        val mouseX = SDLActivity.getMouseX()
        val mouseY = SDLActivity.getMouseY()
        val now = SystemClock.uptimeMillis()
        val pending = now < SDLActivity.sPointerPendingUntil
        if (!SDLActivity.sHandheldMode) {
            SDLActivity.sPointerShown = true
        } else if (mouseShown == 0) {
            // Each menu, and each controller focus jump, starts with the pointer hidden.
            if (!pending) SDLActivity.sPointerShown = false
            shownFrames = 0
        } else {
            if (pending) SDLActivity.sPointerPendingUntil = 0
            // Skip the first frames so a menu opening does not count as motion.
            val moved = Math.abs(mouseX - lastMouseX) > 1 || Math.abs(mouseY - lastMouseY) > 1
            if (shownFrames >= 5 && moved) {
                SDLActivity.sPointerShown = true
            }
            if (shownFrames < 5) shownFrames++
        }
        lastMouseX = mouseX
        lastMouseY = mouseY

        if (GameActivity.mouseMode == MouseMode.Touch || mouseShown == 0 || !SDLActivity.sPointerShown
                || (osc != null && osc.keyboardVisible)) {
            cursor.visibility = View.GONE
        } else {
            cursor.visibility = View.VISIBLE

            val surface = SDLActivity.getSurface()

            var translateX = 1.0f
            var translateY = 1.0f

            if (MainActivity.resolutionX > 0) {
                translateX = 1.0f * surface.width / MainActivity.resolutionX
                translateY = 1.0f * surface.height / MainActivity.resolutionY
            }

            // calling setX/setY here results in a bug cropping part of the mouse cursor
            // changing LayoutParams works as expected...
            val params = cursor.layoutParams as RelativeLayout.LayoutParams
            params.leftMargin = (mouseX * translateX + surface.left).toInt()
            params.topMargin = (mouseY * translateY + surface.top).toInt()
            cursor.layoutParams = params
        }

        prevMouseShown = mouseShown
        choreographer.postFrameCallback(this)
    }
}
