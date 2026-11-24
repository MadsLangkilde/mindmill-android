package dk.langkilde.mindmill.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mindmill's palette, built around the app icon: a navy windmill on a warm cream tile. Light is cream paper with deep
 * navy; dark is a navy night with soft periwinkle. Coral is only a small accent for what needs you; teal marks what
 * runs, green what's done.
 */
object Brand {
    val Navy = Color(0xFF1F3A6E)
    val Cream = Color(0xFFFBF1E8)
    val Night = Color(0xFF10141D)
    val Coral = Color(0xFFD9473A)

    val Light = lightColorScheme(
        primary = Navy, onPrimary = Color.White,
        primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF0E1D3E),
        inversePrimary = Color(0xFFB0C6FF),
        secondary = Color(0xFF575C72), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE3E3EF), onSecondaryContainer = Color(0xFF1A1D2C),
        tertiary = Color(0xFF2F6A5B), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC6ECDD), onTertiaryContainer = Color(0xFF0A3A2E),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        background = Cream, onBackground = Color(0xFF1B1E29),
        surface = Cream, onSurface = Color(0xFF1B1E29),
        surfaceVariant = Color(0xFFEDE3DA), onSurfaceVariant = Color(0xFF4E5160),
        surfaceTint = Navy,
        inverseSurface = Color(0xFF2F3240), inverseOnSurface = Color(0xFFF1F0F7),
        outline = Color(0xFF7A7D8C), outlineVariant = Color(0xFFDFD3C8),
        scrim = Color.Black,
        surfaceBright = Color(0xFFFFF8F2), surfaceDim = Color(0xFFE6D9CD),
        surfaceContainerLowest = Color(0xFFFFFCFA), surfaceContainerLow = Color(0xFFFFF7F0),
        surfaceContainer = Color(0xFFF5EADF), surfaceContainerHigh = Color(0xFFF0E3D7),
        surfaceContainerHighest = Color(0xFFEADACD),
    )

    val Dark = darkColorScheme(
        primary = Color(0xFFB0C6FF), onPrimary = Color(0xFF0D2A5E),
        primaryContainer = Color(0xFF2B4478), onPrimaryContainer = Color(0xFFD9E2FF),
        inversePrimary = Navy,
        secondary = Color(0xFFC1C5DD), onSecondary = Color(0xFF2A3042),
        secondaryContainer = Color(0xFF3D4256), onSecondaryContainer = Color(0xFFDEE1F7),
        tertiary = Color(0xFF9DD3C2), onTertiary = Color(0xFF00382D),
        tertiaryContainer = Color(0xFF1F4D42), onTertiaryContainer = Color(0xFFC6ECDD),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        background = Night, onBackground = Color(0xFFE3E4EE),
        surface = Night, onSurface = Color(0xFFE3E4EE),
        surfaceVariant = Color(0xFF323848), onSurfaceVariant = Color(0xFFC5C6D3),
        surfaceTint = Color(0xFFB0C6FF),
        inverseSurface = Color(0xFFE3E4EE), inverseOnSurface = Color(0xFF2F3240),
        outline = Color(0xFF8E909C), outlineVariant = Color(0xFF3A3F4C),
        scrim = Color.Black,
        surfaceBright = Color(0xFF363B48), surfaceDim = Night,
        surfaceContainerLowest = Color(0xFF0B0E15), surfaceContainerLow = Color(0xFF171B25),
        surfaceContainer = Color(0xFF1B202B), surfaceContainerHigh = Color(0xFF252A36),
        surfaceContainerHighest = Color(0xFF303542),
    )
}

/** A status's chip: its fill, its text, and the dot before the text. */
@Immutable
data class StatusColors(val container: Color, val content: Color, val dot: Color)

/** Colours Material's scheme has no role for: statuses and the brand card. */
@Immutable
data class MindmillExtras(
    val needsYou: StatusColors,
    val running: StatusColors,
    val done: StatusColors,
    val failed: StatusColors,
    val idle: StatusColors,
    /** The Ask Mindmill card, a navy gradient; white text on it reads at 7:1 or better from end to end. */
    val brand: Brush,
    val onBrand: Color,
)

internal val LightExtras = MindmillExtras(
    needsYou = StatusColors(Color(0xFFFFE0D9), Color(0xFF5C130B), Brand.Coral),
    running = StatusColors(Color(0xFFC6ECDD), Color(0xFF0A3A2E), Color(0xFF2F8A70)),
    done = StatusColors(Color(0xFFD3EDBE), Color(0xFF163A05), Color(0xFF4C8A2B)),
    failed = StatusColors(Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFFBA1A1A)),
    idle = StatusColors(Color(0xFFEFE3D8), Color(0xFF3D4050), Color(0xFF8C8E9A)),
    brand = Brush.linearGradient(listOf(Color(0xFF152A55), Color(0xFF2B4C8C))),
    onBrand = Color.White,
)

internal val DarkExtras = MindmillExtras(
    needsYou = StatusColors(Color(0xFF6A2A22), Color(0xFFFFDAD4), Color(0xFFFF8A7A)),
    running = StatusColors(Color(0xFF1F4D42), Color(0xFFC6ECDD), Color(0xFF6FD6B5)),
    done = StatusColors(Color(0xFF29481A), Color(0xFFD3EDBE), Color(0xFF9BD57A)),
    failed = StatusColors(Color(0xFF93000A), Color(0xFFFFDAD6), Color(0xFFFFB4AB)),
    idle = StatusColors(Color(0xFF2A2F3B), Color(0xFFE1E2EC), Color(0xFF8E909C)),
    brand = Brush.linearGradient(listOf(Color(0xFF1B3263), Color(0xFF2F5196))),
    onBrand = Color.White,
)

val LocalExtras = staticCompositionLocalOf { LightExtras }

val extras: MindmillExtras
    @Composable @ReadOnlyComposable get() = LocalExtras.current

private val Serif = FontFamily.Serif

private val BaseType = Typography()

val MindmillType = BaseType.copy(
    displaySmall = BaseType.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = BaseType.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseType.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseType.bodyLarge.copy(lineHeight = 24.sp),
)

val MindmillShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The brand palette, or the wallpaper's colours when the user turns that on (Android 12 and later). */
@Composable
fun MindmillTheme(wallpaperColours: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        wallpaperColours && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Brand.Dark
        else -> Brand.Light
    }
    CompositionLocalProvider(LocalExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, typography = MindmillType, shapes = MindmillShapes, content = content)
    }
}
