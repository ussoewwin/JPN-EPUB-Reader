package com.jpnepub.reader.renderer

import android.content.Context
import android.content.SharedPreferences

data class ReaderConfig(
    val verticalWriting: Boolean = true,
    val fontSizePx: Int = 18,
    val lineHeight: Float = 1.8f,
    val marginPx: Int = 16,
    val textColor: String = "#1A1A1A",
    val backgroundColor: String = "#F5F0E8",
    val darkMode: Boolean = false,
    val rtlPageTurn: Boolean = true,
    val bold: Boolean = false
) {
    fun withDarkMode(): ReaderConfig {
        return if (darkMode) {
            copy(textColor = "#E0E0E0", backgroundColor = "#1E1E1E")
        } else {
            this
        }
    }

    companion object {
        private const val PREFS_NAME = "jpnepub_reader_prefs"
        private const val KEY_VERTICAL = "vertical_writing"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_RTL_PAGE = "rtl_page_turn"
        private const val KEY_BOLD = "bold"

        fun load(context: Context): ReaderConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ReaderConfig(
                verticalWriting = prefs.getBoolean(KEY_VERTICAL, true),
                fontSizePx = prefs.getInt(KEY_FONT_SIZE, 18),
                lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, 1.8f),
                darkMode = prefs.getBoolean(KEY_DARK_MODE, false),
                rtlPageTurn = prefs.getBoolean(KEY_RTL_PAGE, true),
                bold = prefs.getBoolean(KEY_BOLD, false)
            ).withDarkMode()
        }

        fun save(context: Context, config: ReaderConfig) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VERTICAL, config.verticalWriting)
                .putInt(KEY_FONT_SIZE, config.fontSizePx)
                .putFloat(KEY_LINE_HEIGHT, config.lineHeight)
                .putBoolean(KEY_DARK_MODE, config.darkMode)
                .putBoolean(KEY_RTL_PAGE, config.rtlPageTurn)
                .putBoolean(KEY_BOLD, config.bold)
                .apply()
        }
    }
}
