package com.jj.nexusfloat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 一套配色。深色浅色各实例化一份，字段一一对应。
 *
 * 界面里的配色都是手写的，没走 MaterialTheme 取色，因为卡片、开关行的层次关系
 * 需要精确控制。v1.6.6 加浅色主题时，把这些常量从顶层 `val` 改成了带
 * `@Composable` getter 的属性（就是下面 [MdThemePrimary] 那些），这样 79 处调用点
 * 一行都不用改，取到的值自动跟着当前主题走。
 *
 * v1.8.7 起大项目卡片（SectionCard）用 [cardSurface] 50% 透明渲染，让壁纸/底色透出来；
 * 液态玻璃材质已经去掉了。
 */
data class NexusColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val surface: Color,
    val cardSurface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val cardStroke: Color,
    val statusConnected: Color,
    val statusConnectedBg: Color,
    val statusDisconnected: Color,
    val statusDisconnectedBg: Color,
    val toggleOnContainer: Color,
    val toggleOnBorder: Color,
    val toggleOffContainer: Color,
    val subItemGuide: Color,
    val isDark: Boolean
)

/**
 * 深色配色：v1.6.5 及以前唯一的一套，数值一直没动。
 */
private val DarkColors = NexusColors(
    primary = Color(0xFF6DB0FF),
    onPrimary = Color(0xFF002F6C),
    primaryContainer = Color(0xFF1E2838),
    onPrimaryContainer = Color(0xFFD4E3FF),
    surface = Color(0xFF0F1115),
    cardSurface = Color(0xFF1B1E26),
    onSurface = Color(0xFFF0F3F9),
    onSurfaceVariant = Color(0xFF9FA5B5),
    cardStroke = Color(0xFF2B2F3D),
    statusConnected = Color(0xFF34D399),
    statusConnectedBg = Color(0xFF133E2B),
    statusDisconnected = Color(0xFFF87171),
    statusDisconnectedBg = Color(0xFF4A1515),
    toggleOnContainer = Color(0xFF17263C),
    toggleOnBorder = Color(0xFF3D6FA8),
    toggleOffContainer = Color(0xFF15171E),
    subItemGuide = Color(0xFF39404F),
    isDark = true
)

/**
 * 浅色配色。
 *
 * 不是把深色反着来：开关行「开」的底色在深色下是偏亮的蓝（比卡片亮），浅色下就得
 * 反过来用偏深的蓝底（比卡片暗），否则开和关的对比就没了。页面底色比卡片略深也是
 * 同一个道理，卡片得浮在背景上面。
 */
private val LightColors = NexusColors(
    primary = Color(0xFF1A62C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FB),
    onPrimaryContainer = Color(0xFF0B2C5C),
    surface = Color(0xFFEFF1F5),
    cardSurface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181F),
    onSurfaceVariant = Color(0xFF5C6472),
    cardStroke = Color(0xFFD5DAE3),
    statusConnected = Color(0xFF0F7B4F),
    statusConnectedBg = Color(0xFFD3F0E0),
    statusDisconnected = Color(0xFFB3261E),
    statusDisconnectedBg = Color(0xFFFADAD7),
    toggleOnContainer = Color(0xFFDCE8FB),
    toggleOnBorder = Color(0xFF7FAEE8),
    toggleOffContainer = Color(0xFFF4F6FA),
    subItemGuide = Color(0xFFC4CBD6),
    isDark = false
)

/**
 * 当前生效的配色。用 static 不用普通的 CompositionLocal：整棵树是一起换色的，
 * 不需要按子树区分，static 版本读起来更省。
 */
private val LocalNexusColors = staticCompositionLocalOf { DarkColors }

// 下面这些属性名跟 v1.6.5 的顶层 val 完全一样，所以调用点都不用改。
// getter 标了 @ReadOnlyComposable：只读 CompositionLocal、不产生副作用，
// 编译器能省掉一层 composable 包装。

val MdThemePrimary: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.primary
val MdThemeOnPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.onPrimary
val MdThemePrimaryContainer: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.primaryContainer
val MdThemeOnPrimaryContainer: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.onPrimaryContainer

val MdThemeSurface: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.surface
val CardSurface: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.cardSurface
val MdThemeOnSurface: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.onSurface
val MdThemeOnSurfaceVariant: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.onSurfaceVariant
val CardStroke: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.cardStroke

val StatusConnected: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.statusConnected
val StatusConnectedBg: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.statusConnectedBg
val StatusDisconnected: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.statusDisconnected
val StatusDisconnectedBg: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.statusDisconnectedBg

/**
 * 一体化开关行的开 / 关配色。
 * 开和关在三个地方体现差异：底色、描边、右侧状态徽标，
 * 不靠单一视觉线索也能分辨。
 */
val ToggleOnContainer: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.toggleOnContainer
val ToggleOnBorder: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.toggleOnBorder
val ToggleOffContainer: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.toggleOffContainer

/** 子项（比如 CPU 频率）左边那条层级引导线 */
val SubItemGuide: Color
    @Composable @ReadOnlyComposable get() = LocalNexusColors.current.subItemGuide

private fun scheme(c: NexusColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        background = c.surface,
        onBackground = c.onSurface,
        surface = c.cardSurface,
        onSurface = c.onSurface,
        surfaceVariant = c.cardSurface,
        onSurfaceVariant = c.onSurfaceVariant,
        outline = c.cardStroke,
        outlineVariant = c.cardStroke
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        background = c.surface,
        onBackground = c.onSurface,
        surface = c.cardSurface,
        onSurface = c.onSurface,
        surfaceVariant = c.cardSurface,
        onSurfaceVariant = c.onSurfaceVariant,
        outline = c.cardStroke,
        outlineVariant = c.cardStroke
    )
}

/**
 * darkTheme 决定用深色还是浅色。调用方按「浅色 / 深色 / 跟随系统」的设置算好传进来，
 * 跟随系统的话就传 `isSystemInDarkTheme()`。
 */
@Composable
fun NexusFloatTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalNexusColors provides colors) {
        // Material 的组件（Switch、Button、进度圈）是走 MaterialTheme 取色的，
        // 所以两套颜色都得喂给它，不然浅色下 Switch 还是深色那套
        MaterialTheme(
            colorScheme = scheme(colors),
            content = content
        )
    }
}
