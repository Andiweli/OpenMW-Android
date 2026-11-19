/*
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

package ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import ui.fragments.FragmentSettings
import ui.launcher.LauncherStyle

/**
 * One launcher area opened from the home screen (Graphics & performance,
 * Controls, Audio): the matching categories of the launcher settings.
 */
class LauncherPageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        title = pageTitle

        val container = FrameLayout(this).apply { id = R.id.settings_frame }
        setContentView(LauncherStyle.pageFrame(this, pageTitle, onBack = { finish() }, onPlay = { play(this) }, content = container))

        if (savedInstanceState == null) {
            val categories = intent.getStringArrayListExtra(EXTRA_CATEGORIES) ?: arrayListOf()
            fragmentManager.beginTransaction()
                .replace(R.id.settings_frame, FragmentSettings.newInstance(categories))
                .commit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // START starts the game from every launcher page, as the hint says.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.action == KeyEvent.ACTION_UP)
                play(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORIES = "categories"

        /** Back to the home screen and start the game from there. */
        fun play(activity: android.app.Activity) {
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_PLAY, true)
            )
        }

        fun intent(context: Context, title: String, vararg categories: String): Intent =
            Intent(context, LauncherPageActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_CATEGORIES, arrayListOf(*categories))
    }
}
