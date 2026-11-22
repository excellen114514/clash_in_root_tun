package com.roottun.clash.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import com.roottun.clash.core.AppSettings

/** 由主题提供的卡片外观，设置页卡片跟随「卡片样式」切换。 */
val LocalCardStyle = staticCompositionLocalOf { AppSettings.CardStyle.FILLED }

/**
 * Material Design 3 主题（SPEC_2025）。
 *
 * 颜色角色由 MaterialKolor 的 Material 3 动态取色引擎生成：
 * [SchemeTonalSpot] + [ColorSpec.SpecVersion.SPEC_2025]，即 2025 版规范（不是 2021 默认）。
 * 所有 Composable 只通过 `MaterialTheme.colorScheme.*` 取色，不硬编码颜色。
 */
object Spec2025Schemes {

    const val SPEC_LABEL: String = "SPEC_2025"

    fun build(seedArgb: Long, isDark: Boolean, contrastLevel: Double = 0.0): ColorScheme {
        val scheme = SchemeTonalSpot(
            sourceColorHct = Hct.fromInt(Color(seedArgb).toArgb()),
            isDark = isDark,
            contrastLevel = contrastLevel,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(scheme.primary),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            inversePrimary = Color(scheme.inversePrimary),
            secondary = Color(scheme.secondary),
            onSecondary = Color(scheme.onSecondary),
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiary = Color(scheme.tertiary),
            onTertiary = Color(scheme.onTertiary),
            tertiaryContainer = Color(scheme.tertiaryContainer),
            onTertiaryContainer = Color(scheme.onTertiaryContainer),
            background = Color(scheme.background),
            onBackground = Color(scheme.onBackground),
            surface = Color(scheme.surface),
            onSurface = Color(scheme.onSurface),
            surfaceVariant = Color(scheme.surfaceVariant),
            onSurfaceVariant = Color(scheme.onSurfaceVariant),
            surfaceTint = Color(scheme.surfaceTint),
            inverseSurface = Color(scheme.inverseSurface),
            inverseOnSurface = Color(scheme.inverseOnSurface),
            error = Color(scheme.error),
            onError = Color(scheme.onError),
            errorContainer = Color(scheme.errorContainer),
            onErrorContainer = Color(scheme.onErrorContainer),
            outline = Color(scheme.outline),
            outlineVariant = Color(scheme.outlineVariant),
            scrim = Color(scheme.scrim),
            surfaceBright = Color(scheme.surfaceBright),
            surfaceDim = Color(scheme.surfaceDim),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
        )
    }
}

@Composable
fun RootTunClashTheme(
    settings: AppSettings.State,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.darkMode) {
        AppSettings.DarkMode.SYSTEM -> systemDark
        AppSettings.DarkMode.LIGHT -> false
        AppSettings.DarkMode.DARK -> true
    }
    val seed = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Keep the same SPEC_2025 roles for wallpaper colors and custom presets.
        colorResource(android.R.color.system_accent1_500).toArgb().toLong() and 0xffffffffL
    } else settings.seedArgb
    val contrast = if (settings.contrast == AppSettings.Contrast.HIGH) 0.5 else 0.0
    val colorScheme = remember(seed, dark, contrast) { Spec2025Schemes.build(seed, dark, contrast) }
    val shapes = remember(settings.cornerStyle) {
        when (settings.cornerStyle) {
            AppSettings.CornerStyle.DEFAULT -> Shapes()
            AppSettings.CornerStyle.ROUND -> Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(22.dp),
                large = RoundedCornerShape(30.dp),
                extraLarge = RoundedCornerShape(38.dp),
            )
            AppSettings.CornerStyle.SHARP -> Shapes(
                extraSmall = RoundedCornerShape(2.dp),
                small = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(12.dp),
                extraLarge = RoundedCornerShape(16.dp),
            )
        }
    }
    CompositionLocalProvider(LocalCardStyle provides settings.cardStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = shapes,
            content = content,
        )
    }
}
