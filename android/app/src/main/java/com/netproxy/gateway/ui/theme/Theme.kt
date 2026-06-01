package com.netproxy.gateway.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand palette
private val Blue40 = Color(0xFF2563EB)
private val Blue80 = Color(0xFF93C5FD)
private val Cyan40 = Color(0xFF0891B2)
private val Cyan80 = Color(0xFF67E8F9)

// Semantic status colors
private val SuccessLight = Color(0xFF10B981)
private val SuccessDark = Color(0xFF34D399)
private val WarningLight = Color(0xFFF59E0B)
private val WarningDark = Color(0xFFFBBF24)
private val ErrorLight = Color(0xFFEF4444)
private val ErrorDark = Color(0xFFF87171)
private val InfoLight = Color(0xFF3B82F6)
private val InfoDark = Color(0xFF60A5FA)

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Blue80,
    secondary = Cyan80,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Cyan80,
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFA78BFA),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = ErrorDark,
    onError = Color(0xFF0F172A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = ErrorDark,
    outline = Color(0xFF475569)
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Cyan40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFFCBD5E1)
)

@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val error: Color,
    val onError: Color,
    val info: Color,
    val onInfo: Color
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        success = SuccessLight,
        onSuccess = Color.White,
        warning = WarningLight,
        onWarning = Color.White,
        error = ErrorLight,
        onError = Color.White,
        info = InfoLight,
        onInfo = Color.White
    )
}

/**
 * 为应用提供 Material 3 颜色方案与语义状态色，并根据主题更新系统状态栏外观。
 *
 * 根据 `darkTheme` 和 `dynamicColor` 选择合适的 `ColorScheme`，构建与当前主题匹配的 `StatusColors`，
 * 将其通过 `LocalStatusColors` 下发到组合树中，并在非编辑模式下同步设置窗口的状态栏颜色与亮暗样式。
 *
 * @param darkTheme 当为 `true` 时使用深色主题，否则使用浅色主题。
 * @param dynamicColor 在 Android 12 及以上设备上为 `true` 时启用系统动态配色。
 * @param content 需要被该主题包裹的组合内容。
 */
@Composable
fun NetProxyGatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val statusColors = if (darkTheme) {
        StatusColors(
            success = SuccessDark,
            onSuccess = Color(0xFF0F172A),
            warning = WarningDark,
            onWarning = Color(0xFF0F172A),
            error = ErrorDark,
            onError = Color(0xFF0F172A),
            info = InfoDark,
            onInfo = Color(0xFF0F172A)
        )
    } else {
        StatusColors(
            success = SuccessLight,
            onSuccess = Color.White,
            warning = WarningLight,
            onWarning = Color.White,
            error = ErrorLight,
            onError = Color.White,
            info = InfoLight,
            onInfo = Color.White
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
