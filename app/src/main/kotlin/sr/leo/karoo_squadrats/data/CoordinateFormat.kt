package sr.leo.karoo_squadrats.data

import android.annotation.SuppressLint
import java.text.NumberFormat
import java.util.Locale

object CoordinateFormat {
    @SuppressLint("DefaultLocale")
    fun formatCoordinate(value: Double): String {
        return String.format("%.6f", value)
    }

    fun parseCoordinate(text: String): Double? {
        return try {
            NumberFormat.getInstance().parse(text.trim())?.toDouble()
        } catch (_: Exception) {
            null
        }
    }

    fun formatCoordinate(value: Double, locale: Locale): String {
        return String.format(locale, "%.6f", value)
    }

    fun parseCoordinate(text: String, locale: Locale): Double? {
        return try {
            NumberFormat.getInstance(locale).parse(text.trim())?.toDouble()
        } catch (_: Exception) {
            null
        }
    }
}
