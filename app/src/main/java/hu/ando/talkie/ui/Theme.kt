package hu.ando.talkie.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import hu.ando.talkie.core.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF), onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF535F70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F8), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF), onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFFDFCFF), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB), onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FF),
    surfaceContainer = Color(0xFFF1F3F9), surfaceContainerHigh = Color(0xFFEBEEF4), surfaceContainerHighest = Color(0xFFE5E8EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCAFF), onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF004A77), onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD7BDE4), onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F), onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF1A1C1E), onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E), onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E), onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199), outlineVariant = Color(0xFF42474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0E1012), surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022), surfaceContainerHigh = Color(0xFF292A2D), surfaceContainerHighest = Color(0xFF333537),
)

@Composable
fun AndoTheme(mode: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
