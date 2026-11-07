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

fun getAvailableTerminalFonts(): List<String> = TypefaceUtils.getAvailableTerminalFonts()

fun applyTerminalFont(fontName: String?): Boolean = TypefaceUtils.applyTerminalFont(fontName)

fun getTermuxDir(): File = TypefaceUtils.getTermuxDir()

fun getTermuxFontsDir(): File = TypefaceUtils.getTermuxFontsDir()

fun getTermuxFontFile(): File = TypefaceUtils.getTermuxFontFile()

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

    @JvmStatic
    fun getTermuxDir(): File {
        return Environment.TERMUX_DIR ?: File(
            Environment.HOME ?: File(BaseApplication.getBaseInstance().filesDir, "home"),
            ".termux"
        )
    }

    @JvmStatic
    fun getTermuxFontsDir(): File {
        val fontsDir = Environment.TERMUX_FONTS_DIR ?: File(getTermuxDir(), "fonts")
        if (!fontsDir.exists()) {
            fontsDir.mkdirs()
        }
        return fontsDir
    }

    @JvmStatic
    fun getTermuxFontFile(): File {
        return Environment.TERMUX_FONT_FILE ?: File(getTermuxDir(), "font.ttf")
    }

    @JvmStatic
    fun getAvailableTerminalFonts(): List<String> {
        val fonts = linkedSetOf<String>()
        val searchDirs = listOfNotNull(
            getTermuxFontsDir(),
            getTermuxDir(),
            Environment.ANDROIDIDE_UI
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file ->
                    val ext = file.extension.lowercase()
                    (ext == "ttf" || ext == "otf") && file.name != "font.ttf"
                }?.forEach { file ->
                    fonts.add(file.name)
                }
            }
        }

        return fonts.sorted()
    }

    @JvmStatic
    fun applyTerminalFont(fontName: String?): Boolean {
        val targetFile = getTermuxFontFile()
        val targetDir = targetFile.parentFile
        if (targetDir != null && !targetDir.exists()) {
            targetDir.mkdirs()
        }

        if (fontName.isNullOrEmpty()) {
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                return true
            } catch (e: Exception) {
                return false
            }
        }

        val searchDirs = listOfNotNull(
            getTermuxFontsDir(),
            getTermuxDir(),
            Environment.ANDROIDIDE_UI
        )

        var sourceFile: File? = null
        for (dir in searchDirs) {
            val candidate = File(dir, fontName)
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                sourceFile = candidate
                break
            }
        }

        if (sourceFile == null) {
            return false
        }

        return try {
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
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
        if (!selectedFont.isNullOrEmpty()) {
            val searchDirs = listOfNotNull(
                getTermuxFontsDir(),
                getTermuxDir(),
                Environment.ANDROIDIDE_UI
            )
            for (dir in searchDirs) {
                val fontFile = File(dir, selectedFont)
                if (fontFile.exists() && fontFile.isFile && fontFile.length() > 0) {
                    try {
                        return Typeface.createFromFile(fontFile)
                    } catch (_: Exception) {
                        // ignore and try next or fallback
                    }
                }
            }
        }

        val termuxFont = getTermuxFontFile()
        if (termuxFont.exists() && termuxFont.isFile && termuxFont.length() > 0) {
            try {
                return Typeface.createFromFile(termuxFont)
            } catch (_: Exception) {
                // ignore and fall back to jetbrainsMono
            }
        }

        return jetbrainsMono()
    }
}
