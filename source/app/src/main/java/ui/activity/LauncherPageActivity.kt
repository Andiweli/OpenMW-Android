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
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import ui.fragments.FragmentSettings

/**
 * One launcher area opened from the home screen (Graphics & performance,
 * Controls, Audio): the matching categories of the launcher settings.
 */
class LauncherPageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById(R.id.settings_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        intent.getStringExtra(EXTRA_TITLE)?.let { title = it }

        if (savedInstanceState == null) {
            val categories = intent.getStringArrayListExtra(EXTRA_CATEGORIES) ?: arrayListOf()
            fragmentManager.beginTransaction()
                .replace(R.id.settings_frame, FragmentSettings.newInstance(categories))
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORIES = "categories"

        fun intent(context: Context, title: String, vararg categories: String): Intent =
            Intent(context, LauncherPageActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_CATEGORIES, arrayListOf(*categories))
    }
}
