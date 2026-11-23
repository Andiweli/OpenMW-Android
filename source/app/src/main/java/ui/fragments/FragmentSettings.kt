/*
    Copyright (C) 2016 sandstranger
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

package ui.fragments

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceGroup
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.libopenmw.openmw.R
import constants.Constants

import ui.activity.ConfigureControls
import utils.MyApp
import java.util.*

/**
 * The launcher's settings lists. Each launcher area (Graphics & performance,
 * Controls, Audio) shows only its own categories of settings.xml, chosen by
 * [ARG_CATEGORIES]; the keys and stored values are the same as before.
 */
class FragmentSettings : PreferenceFragment(), OnSharedPreferenceChangeListener {

    private var page: ui.launcher.PreferencePageView? = null

    /** The settings in the launcher's own style; the preferences stay the model. */
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        val view = ui.launcher.PreferencePageView(
            activity,
            preferenceScreen,
            sliders = mapOf(
                "pref_uiScaling" to ui.launcher.PreferencePageView.SliderSpec(
                    minimum = 0.5f, maximum = 3.0f, step = 0.1f,
                    defaultValue = MyApp.app.defaultScaling.coerceIn(0.5f, 3.0f),
                    autoLabel = {
                        MyApp.app.getString(R.string.uiScaling_auto).format(Locale.ROOT, MyApp.app.defaultScaling)
                    }
                ),
                "pref_gamma" to ui.launcher.PreferencePageView.SliderSpec(
                    minimum = 1.0f, maximum = 3.0f, step = 0.1f, defaultValue = 1.0f
                )
            ),
            navigationKeys = setOf("pref_controls")
        )
        page = view
        return view
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.settings)
        keepOnlyCategories(arguments?.getStringArrayList(ARG_CATEGORIES))
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        prepareControllerTriggerPreference()
        updateGammaState()

        // Make launcher entries that open another screen visually distinct.
        listOf("pref_controls").forEach { key ->
            findPreference(key)?.widgetLayoutResource = R.layout.preference_widget_chevron
        }

        findPreference("pref_uiScaling")?.setOnPreferenceClickListener {
            showSteppedSliderPreference(
                key = "pref_uiScaling",
                title = getString(R.string.pref_uiScaling),
                minimum = 0.5f,
                maximum = 3.0f,
                step = 0.1f,
                defaultValue = MyApp.app.defaultScaling.coerceIn(0.5f, 3.0f),
                neutralLabel = getString(R.string.slider_auto)
            )
            true
        }

        findPreference("pref_customResolution")?.setOnPreferenceClickListener {
            showCustomResolutionDialog()
            true
        }

        findPreference("pref_gamma")?.setOnPreferenceClickListener {
            showSteppedSliderPreference(
                key = "pref_gamma",
                title = getString(R.string.pref_gamma),
                minimum = 1.0f,
                maximum = 3.0f,
                step = 0.1f,
                defaultValue = 1.0f
            )
            true
        }

        findPreference("pref_controls")?.setOnPreferenceClickListener {
            val intent = Intent(activity, ConfigureControls::class.java)
            this.startActivity(intent)
            true
        }

    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun prepareControllerTriggerPreference() {
        val key = "pref_omw051_controller_trigger_thresholds"
        val preference = findPreference(key) as? ListPreference ?: return
        val stored = preferenceScreen.sharedPreferences
            .getString(key, "30720,26624")
            ?: "30720,26624"

        if (preference.findIndexOfValue(stored) >= 0) {
            return
        }

        val values = stored.split(',', limit = 2)
        val triggerPress = values.getOrNull(0)?.trim()?.toIntOrNull()
        val triggerRelease = values.getOrNull(1)?.trim()?.toIntOrNull()
        val customLabel = if (triggerPress != null && triggerRelease != null) {
            getString(R.string.controller_trigger_custom_values, triggerPress, triggerRelease)
        } else {
            getString(R.string.controller_trigger_custom)
        }

        preference.entries = preference.entries
            .toMutableList()
            .apply { add(customLabel) }
            .toTypedArray()
        preference.entryValues = preference.entryValues
            .toMutableList()
            .apply { add(stored) }
            .toTypedArray()
    }

    private fun showSteppedSliderPreference(
        key: String,
        title: String,
        minimum: Float,
        maximum: Float,
        step: Float,
        defaultValue: Float,
        neutralLabel: String? = null
    ) {
        val sharedPref = preferenceScreen.sharedPreferences
        val current = sharedPref.getString(key, "")
            ?.toFloatOrNull()
            ?.coerceIn(minimum, maximum)
            ?: defaultValue.coerceIn(minimum, maximum)

        val steps = kotlin.math.round((maximum - minimum) / step).toInt()
        val initialProgress = kotlin.math.round((current - minimum) / step)
            .toInt()
            .coerceIn(0, steps)

        val valueLabel = TextView(activity).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val seekBar = SeekBar(activity).apply {
            max = steps
            progress = initialProgress
        }

        fun valueForProgress(progress: Int): Float =
            (minimum + progress * step).coerceIn(minimum, maximum)

        fun updateLabel(progress: Int) {
            valueLabel.text = String.format(Locale.ROOT, "%.1f", valueForProgress(progress))
        }

        updateLabel(initialProgress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(
                valueLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )

            val limits = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(activity).apply {
                        text = String.format(Locale.ROOT, "%.1f", minimum)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    TextView(activity).apply {
                        text = String.format(Locale.ROOT, "%.1f", maximum)
                        gravity = Gravity.END
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            addView(
                limits,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                sharedPref.edit()
                    .putString(
                        key,
                        String.format(Locale.ROOT, "%.1f", valueForProgress(seekBar.progress))
                    )
                    .apply()
                updatePreference(findPreference(key), key)
            }

        if (neutralLabel != null) {
            builder.setNeutralButton(neutralLabel) { _, _ ->
                sharedPref.edit().putString(key, "").apply()
                updatePreference(findPreference(key), key)
            }
        }

        builder.show()
    }

    private fun showCustomResolutionDialog() {
        val sharedPref = preferenceScreen.sharedPreferences
        val current = sharedPref.getString("pref_customResolution", "") ?: ""
        val match = Regex("""^\s*(\d+)\s*[xX×]\s*(\d+)\s*$""").matchEntire(current)

        val widthInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.resolution_width_hint)
            gravity = Gravity.CENTER
            setSingleLine(true)
            match?.groupValues?.getOrNull(1)?.let { setText(it) }
        }

        val separator = TextView(activity).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
        }

        val heightInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.resolution_height_hint)
            gravity = Gravity.CENTER
            setSingleLine(true)
            match?.groupValues?.getOrNull(2)?.let { setText(it) }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(
                widthInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                separator,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                heightInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(getString(R.string.pref_customResolution))
            .setView(row)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.resolution_native) { _, _ ->
                sharedPref.edit().putString("pref_customResolution", "").apply()
                updatePreference(findPreference("pref_customResolution"), "pref_customResolution")
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val width = widthInput.text.toString().toIntOrNull()
                val height = heightInput.text.toString().toIntOrNull()

                if (width == null || height == null || width <= 0 || height <= 0) {
                    Toast.makeText(
                        activity,
                        getString(R.string.resolution_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                sharedPref.edit()
                    .putString("pref_customResolution", "${width}x${height}")
                    .apply()
                updatePreference(findPreference("pref_customResolution"), "pref_customResolution")
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            if (preference is PreferenceGroup) {
                for (j in 0 until preference.preferenceCount) {
                    val singlePref = preference.getPreference(j)
                    updatePreference(singlePref, singlePref.key)
                }
            } else {
                updatePreference(preference, preference.key)
            }
        }
        page?.refresh()
    }

    override fun onDestroy() {
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    /** Removes every category of the screen whose key is not in [keys]; null keeps all. */
    private fun keepOnlyCategories(keys: List<String>?) {
        if (keys == null)
            return
        val screen = preferenceScreen
        val remove = (0 until screen.preferenceCount)
            .map { screen.getPreference(it) }
            .filter { it.key == null || it.key !in keys }
        remove.forEach { screen.removePreference(it) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null)
            return
        updatePreference(findPreference(key), key)
        updateGammaState()
        if (key == Constants.HANDHELD_MODE) {
            // The handheld controls rely on controller menus; they can still be turned off.
            // The touch buttons start hidden, but can be shown again (e.g. for Pause).
            if (sharedPreferences.getBoolean(key, false)) {
                (findPreference("pref_omw050_controller_menus") as? CheckBoxPreference)?.isChecked = true
                (findPreference(Constants.HIDE_CONTROLS) as? CheckBoxPreference)?.isChecked = true
            }
        }
        page?.refresh()
    }

    private fun updatePreference(preference: Preference?, key: String) {
        if (preference == null)
            return
        val sharedPref = preference.sharedPreferences
        when (key) {
            "pref_uiScaling" -> {
                val value = sharedPref.getString(key, "") ?: ""
                preference.summary = if (value.isEmpty()) {
                    MyApp.app.getString(R.string.uiScaling_auto)
                        .format(Locale.ROOT, MyApp.app.defaultScaling)
                } else {
                    value
                }
            }
            "pref_customResolution" -> {
                val value = sharedPref.getString(key, "") ?: ""
                preference.summary = if (value.isEmpty()) getString(R.string.resolution_native) else value.replace('x', '×')
            }
            "pref_gamma" -> {
                val value = sharedPref.getString(key, "") ?: ""
                preference.summary = if (value.isEmpty()) "1.0" else value
            }
            else -> if (preference is EditTextPreference) {
                preference.summary = preference.text
            }
        }
        // Show selected value as a summary for game_files
        if (key == "game_files") {
            preference.summary = preference.sharedPreferences.getString("game_files", "")
        }
    }

    /**
     * Update launcher controls whose availability depends on another option.
     * GLES1 is no longer exposed by the OpenMW 0.51 Android launcher.
     */
    private fun updateGammaState() {
        val sharedPref = preferenceScreen.sharedPreferences
        findPreference("pref_gamma")?.isEnabled = true

	var isnohighpenabled = false;
        if(sharedPref.getString("pref_shadersDir_v2", "") == "modified")
		isnohighpenabled = true
        findPreference("pref_nohighp")?.isEnabled = isnohighpenabled
/*
	var isAdditionalAnimSourcesEnabled = false;
        if(sharedPref.getBoolean("gs_use_additional_animation_sources", false) == true) isAdditionalAnimSourcesEnabled = true
        findPreference("gs_weapon_sheating").isEnabled = isAdditionalAnimSourcesEnabled
        findPreference("gs_shield_sheating").isEnabled = isAdditionalAnimSourcesEnabled
*/
    }

    companion object {
        const val ARG_CATEGORIES = "categories"

        fun newInstance(categories: List<String>): FragmentSettings =
            FragmentSettings().apply {
                arguments = Bundle().apply { putStringArrayList(ARG_CATEGORIES, ArrayList(categories)) }
            }
    }

}
