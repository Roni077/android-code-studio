package com.tom.rv2ide.utils

import android.graphics.Typeface
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.managers.PreferenceManager
import java.io.File

const val SELECTED_CUSTOM_FONT = "idepref_selected_custom_font"
const val SELECTED_TERMINAL_CUSTOM_FONT = "idepref_selected_terminal_custom_font"

private val prefs by lazy {
    PreferenceManager(BaseApplication.getBaseInstance())
}

fun quicksand(): Typeface = TypefaceUtils.quicksand()

fun jetbrainsMono(): Typeface = TypefaceUtils.jetbrainsMono()

fun josefinSans(): Typeface = TypefaceUtils.josefinSans()

fun customOrJBMono(useCustom: Boolean = true): Typeface = TypefaceUtils.customOrJBMono(useCustom)

fun getAvailableCustomFonts(): List<String> = TypefaceUtils.getAvailableCustomFonts()

var selectedCustomFont: String?
    get() = TypefaceUtils.selectedCustomFont
    set(value) {
        TypefaceUtils.selectedCustomFont = value
    }

var selectedTerminalCustomFont: String?
    get() = TypefaceUtils.selectedTerminalCustomFont
    set(value) {
        TypefaceUtils.selectedTerminalCustomFont = value
    }

fun terminalFontOrJBMono(useCustom: Boolean = true): Typeface = TypefaceUtils.terminalFontOrJBMono(useCustom)

object TypefaceUtils {
    @JvmStatic
    fun quicksand(): Typeface =
        Typeface.createFromAsset(BaseApplication.getBaseInstance().assets, "fonts/quicksand.ttf")

    @JvmStatic
    fun jetbrainsMono(): Typeface =
        Typeface.createFromAsset(BaseApplication.getBaseInstance().assets, "fonts/jetbrains-mono.ttf")

    @JvmStatic
    fun josefinSans(): Typeface =
        Typeface.createFromAsset(BaseApplication.getBaseInstance().assets, "fonts/josefin-sans.ttf")

    @JvmStatic
    fun customOrJBMono(useCustom: Boolean = true): Typeface {
        if (!useCustom) return jetbrainsMono()

        val selectedFont = selectedCustomFont
        if (selectedFont.isNullOrEmpty()) return jetbrainsMono()

        val fontDir: File = Environment.ANDROIDIDE_UI
        val fontFile = File(fontDir, selectedFont)

        if (fontFile.exists() && fontFile.isFile && fontFile.length() > 0) {
            return Typeface.createFromFile(fontFile)
        }

        return jetbrainsMono()
    }

    @JvmStatic
    fun getAvailableCustomFonts(): List<String> {
        val fontDir: File = Environment.ANDROIDIDE_UI

        return fontDir.listFiles { file ->
            val ext = file.extension.lowercase()
            ext == "ttf" || ext == "otf"
        }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    var selectedCustomFont: String?
        get() = prefs.getString(SELECTED_CUSTOM_FONT, null)
        set(value) {
            prefs.putString(SELECTED_CUSTOM_FONT, value)
        }

    var selectedTerminalCustomFont: String?
        get() = prefs.getString(SELECTED_TERMINAL_CUSTOM_FONT, null)
        set(value) {
            prefs.putString(SELECTED_TERMINAL_CUSTOM_FONT, value)
        }

    @JvmStatic
    fun terminalFontOrJBMono(useCustom: Boolean = true): Typeface {
        if (!useCustom) return jetbrainsMono()

        val selectedFont = selectedTerminalCustomFont
        if (selectedFont.isNullOrEmpty()) {
            val termuxFont = File("${Environment.HOME}/.termux/font.ttf")
            if (termuxFont.exists() && termuxFont.isFile && termuxFont.length() > 0) {
                return Typeface.createFromFile(termuxFont)
            }
            return jetbrainsMono()
        }

        val fontDir: File = Environment.ANDROIDIDE_UI
        val fontFile = File(fontDir, selectedFont)

        if (fontFile.exists() && fontFile.isFile && fontFile.length() > 0) {
            return Typeface.createFromFile(fontFile)
        }

        return jetbrainsMono()
    }
}
