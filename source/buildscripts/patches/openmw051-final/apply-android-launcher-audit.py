#!/usr/bin/env python3
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-android-launcher-audit.py <project-root>")

root = Path(sys.argv[1]).resolve()

main_activity = root / "app/src/main/java/ui/activity/MainActivity.kt"
game_activity = root / "app/src/main/java/ui/activity/GameActivity.kt"
fragment_settings = root / "app/src/main/java/ui/fragments/FragmentSettings.kt"
settings_activity = root / "app/src/main/java/ui/activity/SettingsActivity.kt"
settings_xml = root / "app/src/main/res/xml/settings.xml"
engine_xml = root / "app/src/main/res/xml/gs_engine.xml"
game_mechanics_xml = root / "app/src/main/res/xml/gs_game_mechanics.xml"
build_gradle = root / "app/build.gradle"

required = [
    main_activity, game_activity, fragment_settings, settings_activity,
    settings_xml, engine_xml, game_mechanics_xml, build_gradle,
]
for path in required:
    if not path.is_file():
        raise SystemExit(f"Patch 49 requires current OpenMW 0.51 project. Missing: {path}")

MARKER = "OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1"

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")

def write(path: Path, text: str) -> None:
    path.write_text(text.replace("\r\n", "\n"), encoding="utf-8", newline="\n")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)

def regex_once(text: str, pattern: str, repl: str, label: str, flags=0) -> str:
    result, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return result

def remove_preference_by_key(text: str, key: str, label: str) -> str:
    pattern = rf'^[ \t]*<(?:ListPreference|CheckBoxPreference|EditTextPreference|Preference)\b[^>]*android:key="{re.escape(key)}"[^>]*/>[ \t]*\n?'
    result, count = re.subn(pattern, "", text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{label}: expected preference {key!r}, found {count}")
    return result

def replace_android_string(text: str, key: str, value: str, label: str) -> str:
    pattern = rf'(<string\s+name="{re.escape(key)}"[^>]*>).*?(</string>)'
    result, count = re.subn(
        pattern,
        lambda m: m.group(1) + value + m.group(2),
        text,
        count=1,
        flags=re.DOTALL,
    )
    if count != 1:
        raise SystemExit(f"{label}: string key {key!r} not found exactly once")
    return result

def append_android_strings(text: str, pairs, label: str) -> str:
    additions = []
    for key, value in pairs:
        if re.search(rf'<string\s+name="{re.escape(key)}"', text):
            continue
        additions.append(f'    <string name="{key}">{value}</string>')
    if not additions:
        return text
    if text.count("</resources>") != 1:
        raise SystemExit(f"{label}: expected one </resources>")
    return text.replace("</resources>", "\n".join(additions) + "\n</resources>", 1)

# ---------------------------------------------------------------------------
# MainActivity: native OpenMW 0.51 Groundcover + safe performance defaults.
# ---------------------------------------------------------------------------
text = read(main_activity)
if MARKER not in text:
    text = replace_once(
        text,
        "        migrateOpenMw051SettingsPreferences()\n",
        "        migrateOpenMw051SettingsPreferences()\n"
        "        migrateOpenMw051GroundcoverPreferences()\n",
        "MainActivity/groundcover migration call",
    )

    migration = r'''
    /**
     * OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1
     *
     * OpenMW 0.51 no longer has the CaveBros Groundcover Paging/Instancing
     * selector. Groundcover is a native instanced renderer controlled by
     * [Groundcover] enabled, density and rendering distance.
     *
     * Import the old launcher choice once and then retire the legacy string key.
     */
    private fun migrateOpenMw051GroundcoverPreferences() {
        val migrationKey = "migration_openmw051_groundcover_toggle_v1"
        if (prefs.getBoolean(migrationKey, false)) {
            return
        }

        val settingsFile = File(Constants.USER_CONFIG, "settings.cfg")
        val legacyMode = prefs.getString("gs_groundcover_handling", "0") ?: "0"
        val configuredEnabled = readOpenMwSetting(
            settingsFile,
            "Groundcover",
            "enabled"
        )

        val groundcoverEnabled = when {
            configuredEnabled.equals("true", ignoreCase = true) -> true
            configuredEnabled.equals("false", ignoreCase = true) -> false
            else -> legacyMode == "1" || legacyMode == "2"
        }

        val groundcoverDensity = readOpenMwSetting(
            settingsFile,
            "Groundcover",
            "density"
        )?.toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 1.0f

        val groundcoverRenderingDistance = readOpenMwSetting(
            settingsFile,
            "Groundcover",
            "rendering distance"
        )?.toFloatOrNull()?.coerceAtLeast(0.0f) ?: 6144.0f

        prefs.edit()
            .putBoolean("gs_groundcover_enabled", groundcoverEnabled)
            .putString(
                "gs_groundcover_density",
                String.format(Locale.ROOT, "%.2f", groundcoverDensity)
            )
            .putString(
                "gs_groundcover_rendering_distance",
                String.format(Locale.ROOT, "%.0f", groundcoverRenderingDistance)
            )
            .remove("gs_groundcover_handling")
            .putBoolean(migrationKey, true)
            .apply()

        Log.i(
            TAG,
            "Migrated OpenMW 0.51 Groundcover launcher settings: " +
                "enabled=$groundcoverEnabled, " +
                "density=$groundcoverDensity, " +
                "renderingDistance=$groundcoverRenderingDistance"
        )
    }

'''
    text = replace_once(
        text,
        "    private fun controllerTriggerThresholds(): Pair<Int, Int> {\n",
        migration + "    private fun controllerTriggerThresholds(): Pair<Int, Int> {\n",
        "MainActivity/insert groundcover migration",
    )

    text = replace_once(
        text,
        '''        val groundcoverPointLighting =
            prefs.getBoolean("gs_groundcover_point_lighting", true)
''',
        '''        val groundcoverEnabled =
            prefs.getBoolean("gs_groundcover_enabled", false)
        val groundcoverDensity =
            prefs.getString("gs_groundcover_density", "1.00")
                ?.toFloatOrNull()
                ?.coerceIn(0.0f, 1.0f)
                ?: 1.0f
        val groundcoverRenderingDistance =
            prefs.getString("gs_groundcover_rendering_distance", "6144")
                ?.toFloatOrNull()
                ?.coerceAtLeast(0.0f)
                ?: 6144.0f
        val groundcoverPointLighting =
            prefs.getBoolean("gs_groundcover_point_lighting", true)
''',
        "MainActivity/groundcover values",
    )

    text = replace_once(
        text,
        '''            linkedMapOf(
                "point lighting" to if (groundcoverPointLighting) "true" else "false"
            )
''',
        '''            linkedMapOf(
                "enabled" to if (groundcoverEnabled) "true" else "false",
                "density" to String.format(Locale.ROOT, "%.2f", groundcoverDensity),
                "rendering distance" to String.format(
                    Locale.ROOT,
                    "%.0f",
                    groundcoverRenderingDistance
                ),
                "point lighting" to if (groundcoverPointLighting) "true" else "false"
            )
''',
        "MainActivity/section-aware groundcover write",
    )

    text = replace_once(
        text,
        '''                "cameraListener=$cameraListener, " +
                "groundcoverPointLighting=$groundcoverPointLighting"
''',
        '''                "cameraListener=$cameraListener, " +
                "groundcoverEnabled=$groundcoverEnabled, " +
                "groundcoverDensity=$groundcoverDensity, " +
                "groundcoverRenderingDistance=$groundcoverRenderingDistance, " +
                "groundcoverPointLighting=$groundcoverPointLighting"
''',
        "MainActivity/groundcover log",
    )

    text = regex_once(
        text,
        r'^[ \t]*"timeplayed"\s+to\s+if\(prefs\.getBoolean\("gs_add_time_to_saves", false\)\)\s+"true"\s+else\s+"false",?\n',
        "",
        "MainActivity/remove obsolete timeplayed",
        flags=re.MULTILINE,
    )
    text = regex_once(
        text,
        r'^[ \t]*"enabled"\s+to\s+if\(prefs\.getString\("gs_groundcover_handling", "0"\)\s*==\s*"2"\)\s+"true"\s+else\s+"false",?\n',
        "",
        "MainActivity/remove legacy groundcover enabled",
        flags=re.MULTILINE,
    )
    text = regex_once(
        text,
        r'^[ \t]*"paging"\s+to\s+if\(prefs\.getString\("gs_groundcover_handling", "0"\)\s*==\s*"1"\)\s+"true"\s+else\s+"false",?\n',
        "",
        "MainActivity/remove legacy groundcover paging",
        flags=re.MULTILINE,
    )

    text = replace_once(
        text,
        '"write to navmeshdb" to if(prefs.getBoolean("gs_write_navmesh", false)) "true" else "false"',
        '"write to navmeshdb" to if(prefs.getBoolean("gs_write_navmesh", true)) "true" else "false"',
        "MainActivity/navmesh disk-cache default",
    )

    text = replace_once(
        text,
        '"preload num threads" to prefs.getString("gs_preload_threads", "1").toString()',
        '''"preload num threads" to ((prefs.getString("gs_preload_threads", "2")
                            ?.toIntOrNull() ?: 2).coerceIn(1, 3)).toString()''',
        "MainActivity/preload thread policy",
    )
    write(main_activity, text)

# ---------------------------------------------------------------------------
# Engine page: native 0.51 Groundcover controls + proper navmesh default.
# ---------------------------------------------------------------------------
text = read(engine_xml)
if 'android:key="gs_groundcover_enabled"' not in text:
    match = re.search(
        r'^[ \t]*<ListPreference\b[^>]*android:key="gs_groundcover_handling"[^>]*/>[ \t]*\n',
        text,
        flags=re.MULTILINE,
    )
    if not match:
        raise SystemExit("gs_engine.xml: legacy Groundcover ListPreference not found")

    replacement = '''        <CheckBoxPreference android:key="gs_groundcover_enabled" android:title="@string/gs_groundcover_handling_title" android:summary="@string/gs_groundcover_handling_summary" android:defaultValue="false" />
        <Preference android:key="gs_groundcover_density" android:title="@string/gs_groundcover_density_title" android:summary="@string/gs_groundcover_density_summary" android:dependency="gs_groundcover_enabled" />
        <Preference android:key="gs_groundcover_rendering_distance" android:title="@string/gs_groundcover_rendering_distance_title" android:summary="@string/gs_groundcover_rendering_distance_summary" android:dependency="gs_groundcover_enabled" />
'''
    text = text[:match.start()] + replacement + text[match.end():]

    text = replace_once(
        text,
        '<CheckBoxPreference android:key="gs_groundcover_point_lighting" android:title="@string/gs_groundcover_point_lighting_title" android:summary="@string/gs_groundcover_point_lighting_summary" android:defaultValue="true" />',
        '<CheckBoxPreference android:key="gs_groundcover_point_lighting" android:title="@string/gs_groundcover_point_lighting_title" android:summary="@string/gs_groundcover_point_lighting_summary" android:defaultValue="true" android:dependency="gs_groundcover_enabled" />',
        "gs_engine.xml/groundcover dependency",
    )
    text = replace_once(
        text,
        '<CheckBoxPreference android:key="gs_write_navmesh" android:title="@string/gs_write_navmesh_title" android:defaultValue="false" android:summary="@string/gs_write_navmesh_summary" />',
        '<CheckBoxPreference android:key="gs_write_navmesh" android:title="@string/gs_write_navmesh_title" android:defaultValue="true" android:summary="@string/gs_write_navmesh_summary" />',
        "gs_engine.xml/navmesh cache default",
    )
    write(engine_xml, text)

# ---------------------------------------------------------------------------
# SettingsActivity: Groundcover sliders and official preload recommendation.
# ---------------------------------------------------------------------------
text = read(settings_activity)
if MARKER not in text:
    old_block = '''        if (pageResource == R.xml.gs_engine) {
            updatePreference(preferenceScreen.sharedPreferences, "gs_build_navmesh")
            normalizeIntegerPreference("gs_navmesh_threads", 1, 8, 1)
            normalizeIntegerPreference("gs_physics_threads", 1, 8, 1)
            normalizeIntegerPreference("gs_preload_threads", 1, 8, 1)

            listOf(
                "gs_navmesh_threads",
                "gs_physics_threads",
                "gs_preload_threads"
            ).forEach { key ->
                findPreference(key).setOnPreferenceClickListener {
                    showSteppedSliderPreference(
                        key = key,
                        title = findPreference(key).title.toString(),
                        minimum = 1.0f,
                        maximum = 8.0f,
                        step = 1.0f,
                        defaultValue = 1.0f,
                        decimals = 0
                    )
                    true
                }
            }
        }
'''
    new_block = '''        if (pageResource == R.xml.gs_engine) {
            // OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1
            updatePreference(preferenceScreen.sharedPreferences, "gs_build_navmesh")

            normalizeFloatPreference("gs_groundcover_density", 0.0f, 1.0f, 1.0f, 2)
            normalizeFloatPreference(
                "gs_groundcover_rendering_distance",
                1024.0f,
                8192.0f,
                6144.0f,
                0
            )

            findPreference("gs_groundcover_density").setOnPreferenceClickListener {
                showSteppedSliderPreference(
                    key = "gs_groundcover_density",
                    title = findPreference("gs_groundcover_density").title.toString(),
                    minimum = 0.0f,
                    maximum = 1.0f,
                    step = 0.05f,
                    defaultValue = 1.0f,
                    decimals = 2
                )
                true
            }

            findPreference("gs_groundcover_rendering_distance").setOnPreferenceClickListener {
                showSteppedSliderPreference(
                    key = "gs_groundcover_rendering_distance",
                    title = findPreference("gs_groundcover_rendering_distance").title.toString(),
                    minimum = 1024.0f,
                    maximum = 8192.0f,
                    step = 256.0f,
                    defaultValue = 6144.0f,
                    decimals = 0
                )
                true
            }

            normalizeIntegerPreference("gs_navmesh_threads", 1, 8, 1)
            normalizeIntegerPreference("gs_physics_threads", 1, 8, 1)
            normalizeIntegerPreference("gs_preload_threads", 1, 3, 2)

            listOf(
                "gs_navmesh_threads",
                "gs_physics_threads"
            ).forEach { key ->
                findPreference(key).setOnPreferenceClickListener {
                    showSteppedSliderPreference(
                        key = key,
                        title = findPreference(key).title.toString(),
                        minimum = 1.0f,
                        maximum = 8.0f,
                        step = 1.0f,
                        defaultValue = 1.0f,
                        decimals = 0
                    )
                    true
                }
            }

            findPreference("gs_preload_threads").setOnPreferenceClickListener {
                showSteppedSliderPreference(
                    key = "gs_preload_threads",
                    title = findPreference("gs_preload_threads").title.toString(),
                    minimum = 1.0f,
                    maximum = 3.0f,
                    step = 1.0f,
                    defaultValue = 2.0f,
                    decimals = 0
                )
                true
            }
        }
'''
    text = replace_once(text, old_block, new_block, "SettingsActivity/engine settings block")
    write(settings_activity, text)

# ---------------------------------------------------------------------------
# Remove obsolete save-time toggle.
# ---------------------------------------------------------------------------
text = read(game_mechanics_xml)
if 'android:key="gs_add_time_to_saves"' in text:
    text = remove_preference_by_key(
        text,
        "gs_add_time_to_saves",
        "gs_game_mechanics.xml/remove obsolete time-played toggle",
    )
    write(game_mechanics_xml, text)

# ---------------------------------------------------------------------------
# Retire GLES1 selector; force tested GLES2/GL4ES final path.
# ---------------------------------------------------------------------------
text = read(settings_xml)
if 'android:key="pref_graphicsLibrary_v2"' in text:
    text = remove_preference_by_key(
        text,
        "pref_graphicsLibrary_v2",
        "settings.xml/remove GLES1/GLES2 selector",
    )
    write(settings_xml, text)

text = read(game_activity)
if MARKER not in text:
    text = replace_once(
        text,
        '        val graphicsLibrary = prefs!!.getString("pref_graphicsLibrary_v2", "")\n',
        "",
        "GameActivity/remove graphicsLibrary preference",
    )
    old = '''        System.loadLibrary("SDL2")
        if (graphicsLibrary != "gles1") {
            try {
                Os.setenv("OPENMW_GLES_VERSION", "2", true)
                Os.setenv("LIBGL_ES", "2", true)
            } catch (e: ErrnoException) {
                Log.e("OpenMW", "Failed setting environment variables.")
                e.printStackTrace()
            }

        }

'''
    new = '''        System.loadLibrary("SDL2")

        // OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1
        // OpenMW 0.51 final and this port's GL4ES/OMWFX compatibility shaders
        // use the GLES2 backend. The old GLES1 launcher selector is a CaveBros
        // legacy path and is no longer exposed.
        try {
            Os.setenv("OPENMW_GLES_VERSION", "2", true)
            Os.setenv("LIBGL_ES", "2", true)
        } catch (e: ErrnoException) {
            Log.e("OpenMW", "Failed setting GLES2 environment variables.")
            e.printStackTrace()
        }

'''
    text = replace_once(text, old, new, "GameActivity/force GLES2")
    write(game_activity, text)

text = read(fragment_settings)
old = '''    /**
     * @brief Disable gamma preference if GLES1 is selected
     */
    private fun updateGammaState() {
        val sharedPref = preferenceScreen.sharedPreferences
        findPreference("pref_gamma").isEnabled =
                sharedPref.getString("pref_graphicsLibrary_v2", "") != "gles1"

'''
if old in text:
    new = '''    /**
     * Update launcher controls whose availability depends on another option.
     * GLES1 is no longer exposed by the OpenMW 0.51 Android launcher.
     */
    private fun updateGammaState() {
        val sharedPref = preferenceScreen.sharedPreferences
        findPreference("pref_gamma").isEnabled = true

'''
    text = replace_once(text, old, new, "FragmentSettings/remove GLES1 gamma dependency")
    write(fragment_settings, text)

# ---------------------------------------------------------------------------
# Localisation + obsolete arrays cleanup.
# ---------------------------------------------------------------------------
locale_text = {
    "values": {
        "title": "Groundcover",
        "summary": "Enable OpenMW 0.51 native instanced groundcover rendering for mods loaded through the Groundcovers tab.",
        "density_title": "Groundcover Density",
        "density_summary": "Percentage of groundcover instances to render. 100% keeps the full mod density; lower values improve performance.",
        "distance_title": "Groundcover Rendering Distance",
        "distance_summary": "Distance at which groundcover is rendered. Larger values increase rendering cost.",
        "preload": "Number of background threads used for preloading. OpenMW 0.51 recommends 2–3 on multicore systems; 4 or more is not recommended.",
    },
    "values-de": {
        "title": "Bodenbewuchs",
        "summary": "Aktiviert den nativen instanzierten Bodenbewuchs-Renderer von OpenMW 0.51 für Mods aus dem Reiter „Bodenbewuchs“.",
        "density_title": "Bodenbewuchs-Dichte",
        "density_summary": "Anteil der gerenderten Bodenbewuchs-Instanzen. 100 % erhält die vollständige Mod-Dichte; niedrigere Werte verbessern die Leistung.",
        "distance_title": "Bodenbewuchs-Sichtweite",
        "distance_summary": "Entfernung, bis zu der Bodenbewuchs gerendert wird. Größere Werte erhöhen den Renderaufwand.",
        "preload": "Anzahl der Hintergrund-Threads zum Vorladen. OpenMW 0.51 empfiehlt auf Mehrkern-Systemen 2–3; 4 oder mehr werden nicht empfohlen.",
    },
    "values-fr": {
        "title": "Végétation au sol",
        "summary": "Active le rendu instancié natif d’OpenMW 0.51 pour les mods chargés via l’onglet de végétation au sol.",
        "density_title": "Densité de la végétation au sol",
        "density_summary": "Pourcentage d’instances de végétation affichées. 100 % conserve la densité complète du mod ; des valeurs plus basses améliorent les performances.",
        "distance_title": "Distance d’affichage de la végétation",
        "distance_summary": "Distance jusqu’à laquelle la végétation au sol est affichée. Des valeurs plus élevées augmentent le coût du rendu.",
        "preload": "Nombre de threads d’arrière-plan utilisés pour le préchargement. OpenMW 0.51 recommande 2 à 3 threads sur les systèmes multicœurs ; 4 ou plus ne sont pas recommandés.",
    },
    "values-pl": {
        "title": "Roślinność podłoża",
        "summary": "Włącza natywne renderowanie instancjonowane OpenMW 0.51 dla modów wczytanych przez kartę roślinności podłoża.",
        "density_title": "Gęstość roślinności podłoża",
        "density_summary": "Procent renderowanych instancji roślinności. 100% zachowuje pełną gęstość moda; niższe wartości zwiększają wydajność.",
        "distance_title": "Zasięg renderowania roślinności",
        "distance_summary": "Odległość, do której renderowana jest roślinność podłoża. Większe wartości zwiększają koszt renderowania.",
        "preload": "Liczba wątków w tle używanych do wstępnego ładowania. OpenMW 0.51 zaleca 2–3 na systemach wielordzeniowych; 4 lub więcej nie jest zalecane.",
    },
    "values-ru": {
        "title": "Наземная растительность",
        "summary": "Включает нативный инстансинг OpenMW 0.51 для модов, загруженных через вкладку наземной растительности.",
        "density_title": "Плотность наземной растительности",
        "density_summary": "Доля отображаемых экземпляров растительности. 100% сохраняет полную плотность мода; меньшие значения повышают производительность.",
        "distance_title": "Дальность отрисовки растительности",
        "distance_summary": "Расстояние, на котором отображается наземная растительность. Большие значения увеличивают нагрузку на рендеринг.",
        "preload": "Количество фоновых потоков предварительной загрузки. OpenMW 0.51 рекомендует 2–3 на многоядерных системах; 4 и более не рекомендуются.",
    },
    "values-sv": {
        "title": "Markvegetation",
        "summary": "Aktiverar OpenMW 0.51:s inbyggda instansrendering för markvegetationsmoddar som lästs in via fliken för markvegetation.",
        "density_title": "Markvegetationens täthet",
        "density_summary": "Andel markvegetationsinstanser som ritas. 100 % behåller moddarnas fulla täthet; lägre värden förbättrar prestandan.",
        "distance_title": "Ritavstånd för markvegetation",
        "distance_summary": "Avståndet som markvegetation ritas på. Högre värden ökar renderingskostnaden.",
        "preload": "Antal bakgrundstrådar för förinläsning. OpenMW 0.51 rekommenderar 2–3 på flerkärniga system; 4 eller fler rekommenderas inte.",
    },
}

for dirname, tr in locale_text.items():
    strings = root / f"app/src/main/res/{dirname}/strings.xml"
    prefs_strings = root / f"app/src/main/res/{dirname}/launcher_preferences.xml"
    if not strings.is_file() or not prefs_strings.is_file():
        raise SystemExit(f"Patch 49 requires complete launcher locale: {dirname}")

    s = read(strings)
    for array_name in (
        "gs_groundcover_handling_entries",
        "gs_groundcover_handling_values",
        "pref_graphicsLibrary_entries",
        "pref_graphicsLibrary_values",
    ):
        pattern = rf'\n?[ \t]*<string-array\s+name="{re.escape(array_name)}"[^>]*>.*?</string-array>[ \t]*'
        s = re.sub(pattern, "", s, flags=re.DOTALL)
    write(strings, s)

    p = read(prefs_strings)
    p = replace_android_string(
        p, "gs_groundcover_handling_title", tr["title"],
        f"{dirname}/groundcover title"
    )
    p = replace_android_string(
        p, "gs_groundcover_handling_summary", tr["summary"],
        f"{dirname}/groundcover summary"
    )
    p = replace_android_string(
        p, "gs_preload_threads_summary", tr["preload"],
        f"{dirname}/preload summary"
    )
    p = append_android_strings(
        p,
        [
            ("gs_groundcover_density_title", tr["density_title"]),
            ("gs_groundcover_density_summary", tr["density_summary"]),
            ("gs_groundcover_rendering_distance_title", tr["distance_title"]),
            ("gs_groundcover_rendering_distance_summary", tr["distance_summary"]),
        ],
        f"{dirname}/new Groundcover strings",
    )
    write(prefs_strings, p)

# ---------------------------------------------------------------------------
# Release verification gate.
# ---------------------------------------------------------------------------
text = read(build_gradle)
if "OPENMW_ANDROID_051_LAUNCHER_AUDIT_GATE_V1" not in text:
    text = replace_once(
        text,
        '''                !launcherMainActivity.contains('"gs_groundcover_point_lighting"') ||
''',
        '''                !launcherMainActivity.contains('"gs_groundcover_point_lighting"') ||
                !launcherMainActivity.contains('"gs_groundcover_enabled"') ||
                !launcherMainActivity.contains('"gs_groundcover_density"') ||
                !launcherMainActivity.contains('"gs_groundcover_rendering_distance"') ||
                launcherMainActivity.contains('"paging" to if(prefs.getString("gs_groundcover_handling"') ||
                launcherMainActivity.contains('"timeplayed" to') ||
''',
        "build.gradle/MainActivity audit gate",
    )

    text = replace_once(
        text,
        '''                hideOnScreenButtonsIndex <= onScreenControlsIndex) {
''',
        '''                hideOnScreenButtonsIndex <= onScreenControlsIndex ||
                launcherSettingsXml.contains('android:key="pref_graphicsLibrary_v2"')) {
''',
        "build.gradle/remove GLES1 selector gate",
    )

    old_engine_gate = '''        def launcherEngineXml = file('src/main/res/xml/gs_engine.xml').getText('UTF-8')
        if (!launcherEngineXml.contains('android:key="gs_groundcover_point_lighting"') ||
                !launcherEngineXml.contains('android:defaultValue="true"')) {
            throw new GradleException(
                    'OpenMW 0.51 Groundcover point-lighting control is missing.'
            )
        }
'''
    new_engine_gate = '''        // OPENMW_ANDROID_051_LAUNCHER_AUDIT_GATE_V1
        def launcherEngineXml = file('src/main/res/xml/gs_engine.xml').getText('UTF-8')
        if (!launcherEngineXml.contains('android:key="gs_groundcover_enabled"') ||
                !launcherEngineXml.contains('android:key="gs_groundcover_density"') ||
                !launcherEngineXml.contains('android:key="gs_groundcover_rendering_distance"') ||
                !launcherEngineXml.contains('android:key="gs_groundcover_point_lighting"') ||
                launcherEngineXml.contains('android:key="gs_groundcover_handling"')) {
            throw new GradleException(
                    'OpenMW 0.51 native Groundcover launcher controls are incomplete or legacy Paging remains.'
            )
        }

        def launcherGameActivity = file('src/main/java/ui/activity/GameActivity.kt').getText('UTF-8')
        if (!launcherGameActivity.contains('OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1') ||
                !launcherGameActivity.contains('Os.setenv("OPENMW_GLES_VERSION", "2", true)') ||
                !launcherGameActivity.contains('Os.setenv("LIBGL_ES", "2", true)') ||
                launcherGameActivity.contains('graphicsLibrary != "gles1"')) {
            throw new GradleException(
                    'OpenMW 0.51 Android final runtime must use the GLES2/GL4ES path.'
            )
        }
'''
    text = replace_once(
        text, old_engine_gate, new_engine_gate, "build.gradle/engine audit gate"
    )
    text = replace_once(
        text,
        "                'gs_groundcover_handling_entries',\n",
        "",
        "build.gradle/remove obsolete Groundcover localized array",
    )
    write(build_gradle, text)

# ---------------------------------------------------------------------------
# Final verification.
# ---------------------------------------------------------------------------
main = read(main_activity)
game = read(game_activity)
engine = read(engine_xml)
settings = read(settings_xml)
mechanics = read(game_mechanics_xml)
activity = read(settings_activity)
gradle = read(build_gradle)

checks = {
    "MainActivity audit marker": MARKER in main,
    "Groundcover migrated to boolean": '"gs_groundcover_enabled"' in main,
    "Groundcover density mapped": '"density" to String.format' in main,
    "Groundcover rendering distance mapped": '"rendering distance" to String.format' in main,
    "Legacy Groundcover paging writer removed": '"paging" to if(prefs.getString("gs_groundcover_handling"' not in main,
    "Obsolete timeplayed writer removed": '"timeplayed" to' not in main,
    "Preload threads clamped 1..3": 'coerceIn(1, 3)' in main and '"gs_preload_threads", "2"' in main,
    "Navmesh DB default true": 'prefs.getBoolean("gs_write_navmesh", true)' in main,
    "Groundcover checkbox present": 'android:key="gs_groundcover_enabled"' in engine,
    "Groundcover density control present": 'android:key="gs_groundcover_density"' in engine,
    "Groundcover distance control present": 'android:key="gs_groundcover_rendering_distance"' in engine,
    "Legacy Groundcover selector absent": 'android:key="gs_groundcover_handling"' not in engine,
    "GLES1 selector absent": 'android:key="pref_graphicsLibrary_v2"' not in settings,
    "GLES2 forced": MARKER in game and 'Os.setenv("LIBGL_ES", "2", true)' in game,
    "Obsolete time-played checkbox absent": 'android:key="gs_add_time_to_saves"' not in mechanics,
    "Preload slider max 3": 'maximum = 3.0f' in activity,
    "Release audit gate present": 'OPENMW_ANDROID_051_LAUNCHER_AUDIT_GATE_V1' in gradle,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Patch 49 verification failed:\n - " + "\n - ".join(failed))

print()
print("OpenMW 0.51 Patch 49 launcher audit/cleanup: PASS")
print("Groundcover: native 0.51 Off/On + Density + Rendering Distance")
print("Groundcover legacy Paging: removed")
print("Groundcover writes: section-aware [Groundcover]")
print("GLES1 launcher backend: removed; GLES2/GL4ES forced")
print("Obsolete Add Time Played option: removed")
print("Preload threads: 1-3, Android default 2")
print("Navmesh disk cache: OpenMW 0.51 default On")
print("Localized launcher strings: updated")
print("Native OpenMW library: unchanged")
print()
print("Build the APK normally in Android Studio.")
