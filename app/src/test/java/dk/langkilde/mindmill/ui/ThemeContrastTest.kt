package dk.langkilde.mindmill.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text in the brand palette reads at 4.5:1 or better (WCAG AA) in light and dark. */
class ThemeContrastTest {
    private fun check(name: String, bg: Color, fg: Color, min: Double = 4.5) {
        val r = Contrast.ratio(bg.toArgb(), fg.toArgb())
        assertTrue("$name: %.2f < $min".format(r), r >= min)
    }

    @Test
    fun schemePairsRead() {
        listOf("light" to Brand.Light, "dark" to Brand.Dark).forEach { (n, cs) ->
            check("$n primary", cs.primary, cs.onPrimary)
            check("$n primary text on background", cs.background, cs.primary)
            check("$n primaryContainer", cs.primaryContainer, cs.onPrimaryContainer)
            check("$n secondaryContainer", cs.secondaryContainer, cs.onSecondaryContainer)
            check("$n tertiaryContainer", cs.tertiaryContainer, cs.onTertiaryContainer)
            check("$n errorContainer", cs.errorContainer, cs.onErrorContainer)
            check("$n surface", cs.surface, cs.onSurface)
            check("$n variant text on surface", cs.surface, cs.onSurfaceVariant)
            check("$n variant text on highest container", cs.surfaceContainerHighest, cs.onSurfaceVariant)
            check("$n inverse", cs.inverseSurface, cs.inverseOnSurface)
        }
    }

    @Test
    fun statusChipsAndTheAskCardRead() {
        listOf("light" to LightExtras, "dark" to DarkExtras).forEach { (n, x) ->
            listOf(x.needsYou, x.running, x.done, x.failed, x.idle).forEachIndexed { i, s -> check("$n status $i", s.container, s.content) }
        }
        listOf(Color(0xFF152A55), Color(0xFF2B4C8C), Color(0xFF1B3263), Color(0xFF2F5196)).forEach { stop ->
            check("Ask card stop", stop, Color.White, 7.0)
        }
    }
}
