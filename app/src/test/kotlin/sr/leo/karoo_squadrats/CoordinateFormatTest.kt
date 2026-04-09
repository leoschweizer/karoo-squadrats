package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.CoordinateFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CoordinateFormatTest {

    private val testLocales = listOf(
        Locale.US,           // decimal separator: .
        Locale.GERMANY,      // decimal separator: ,
        Locale.FRANCE,       // decimal separator: ,
        Locale("tr", "TR"),  // Turkish locale
        Locale.ROOT,
    )

    @Test
    fun `format and parse round-trips correctly for all locales`() {
        val values = listOf(52.520008, -13.404954, 0.0, -90.0, 90.0, 180.0, -180.0, 48.858844)

        for (locale in testLocales) {
            for (original in values) {
                val formatted = CoordinateFormat.formatCoordinate(original, locale)
                val parsed = CoordinateFormat.parseCoordinate(formatted, locale)
                assertNotNull("Failed to parse '$formatted' for locale $locale", parsed)
                assertEquals(
                    "Round-trip failed for $original with locale $locale (formatted: '$formatted')",
                    original, parsed!!, 0.000001,
                )
            }
        }
    }

    @Test
    fun `parse returns null for invalid input`() {
        assertNull(CoordinateFormat.parseCoordinate("abc"))
        assertNull(CoordinateFormat.parseCoordinate(""))
    }

    @Test
    fun `parse handles whitespace`() {
        for (locale in testLocales) {
            val formatted = CoordinateFormat.formatCoordinate(52.520008, locale)
            val parsed = CoordinateFormat.parseCoordinate("  $formatted  ", locale)
            assertNotNull("Failed to parse with whitespace for locale $locale", parsed)
            assertEquals(52.520008, parsed!!, 0.000001)
        }
    }

    @Test
    fun `format produces expected decimal places`() {
        val formatted = CoordinateFormat.formatCoordinate(1.5, Locale.US)
        assertEquals("1.500000", formatted)
    }

    @Test
    fun `format uses locale-specific decimal separator`() {
        val us = CoordinateFormat.formatCoordinate(52.52, Locale.US)
        assertTrue("US format should use dot: $us", us.contains("."))

        val de = CoordinateFormat.formatCoordinate(52.52, Locale.GERMANY)
        assertTrue("German format should use comma: $de", de.contains(","))
    }
}
