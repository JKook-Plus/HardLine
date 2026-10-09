package dev.hardline.ui.theme

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

/** The orange of the icon and the wordmark. It stays the same in every colour scheme. */
val Signal = Color(0xFFFF5A1F)

private val Light = lightColorScheme(
    primary = Color(0xFF4355B9), onPrimary = Color.White, primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), secondaryContainer = Color(0xFFE0E1F9), tertiary = Color(0xFF77536D), tertiaryContainer = Color(0xFFFFD7F1),
    error = Color(0xFFBA1A1A), background = Color(0xFFFEFBFF), surface = Color(0xFFFEFBFF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08218A), primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC3C5DD), secondaryContainer = Color(0xFF43465A), tertiary = Color(0xFFE6BAD7), tertiaryContainer = Color(0xFF5D3C55),
    error = Color(0xFFFFB4AB), background = Color(0xFF1B1B1F), surface = Color(0xFF1B1B1F),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> LocalContext.current.let { if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it) }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
