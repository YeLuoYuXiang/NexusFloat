package com.jj.nexusfloat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jj.nexusfloat.App
import com.jj.nexusfloat.bridge.SettingsChannel
import com.jj.nexusfloat.collector.GpuCollectorWorker
import com.jj.nexusfloat.collector.GpuRootReader
import com.jj.nexusfloat.collector.SysfsReader
import com.jj.nexusfloat.constant.Constants
import com.jj.nexusfloat.service.GpuCollectorLauncher
import com.jj.nexusfloat.ui.theme.*
import com.jj.nexusfloat.utils.ExecUtils
import com.jj.nexusfloat.utils.LogUtils
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileOutputStream

/**
 * 模块主界面：用 Jetpack Compose (Material 3) 照着原来的 XML MaterialCardView 风格做的，
 * 顺手加了点东西。
 */
class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var showPortrait by mutableStateOf(true)
    private var showLandscape by mutableStateOf(true)
    /** 息屏时还显不显示监视条（v1.8.10），默认不显示 */
    private var showScreenOff by mutableStateOf(false)
    /**
     * 后台唤醒间隔（秒，v1.8.11）：0 表示关闭。
     * ColorOS 划卡清掉模块进程后，靠这个定时把进程拉回来，GPU 数据才不会断。
     */
    private var wakeIntervalSec by mutableStateOf(Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC)
    /** 各指标模块开关，下标对应 Constants.Modules.KEYS */
    private val moduleFlags = mutableStateListOf(
        *Array(Constants.Modules.COUNT) { true }
    )
    /** 各指标在监视条上显示的名称，下标对应 Constants.Modules.KEYS */
    private val moduleNames = mutableStateListOf(
        *Constants.Modules.DEFAULT_NAMES
    )
    /** 「背景」开关，默认关 */
    private var backgroundEnabled by mutableStateOf(false)
    /** 「自动反色」开关，默认关；开了之后字色跟着背景明暗走 */
    private var autoContrastEnabled by mutableStateOf(false)
    /** 「字体颜色」在 Constants.Ui.TEXT_COLORS 里的下标，0 是白色 */
    private var textColorIndex by mutableStateOf(0)
    /** CPU / GPU 柱状图开关，默认开（跟旧版本观感一致） */
    private var cpuBarEnabled by mutableStateOf(true)
    private var gpuBarEnabled by mutableStateOf(true)
    /** 「双电芯」开关，默认关；开了之后功率乘 2 */
    private var dualCellEnabled by mutableStateOf(false)
    /** 悬浮窗字号（sp） */
    private var fontSizeSp by mutableStateOf(Constants.Ui.TEXT_SIZE_SP)
    /** 悬浮窗字体加不加粗（v1.8.9） */
    private var fontBold by mutableStateOf(true)
    /** 监视项目之间的间隔（dp） */
    private var spacingDp by mutableStateOf(Constants.Ui.DIVIDER_WIDTH_DP)
    /** 各 FPS 来源开关，下标对应 Constants.Modules.FpsSource.KEYS */
    private val fpsSourceFlags = mutableStateListOf(
        *Array(Constants.Modules.FpsSource.COUNT) { true }
    )
    /** 「SurfaceFlinger 走 root」开关，默认关（走 Binder，不起进程） */
    private var sfPreferRoot by mutableStateOf(false)
    /** 「FPS 诊断」开关，开了之后帧率后面跟一个来源字母 */
    private var fpsDebug by mutableStateOf(false)
    /** App 界面主题：0 跟随系统 / 1 浅色 / 2 深色 */
    private var appTheme by mutableStateOf(Constants.Modules.APP_THEME_SYSTEM)
    /** 软件界面壁纸文件名（v1.8.5）：存在 filesDir 里的图片，空串表示不用 */
    private var uiWallpaperName by mutableStateOf("")
    /** 壁纸解码后的位图；Compose 内部用 remember 拿着，这个是读取时用的 */
    private var uiWallpaperBitmap: Bitmap? = null
    /**
     * 顶层模块的显示顺序（v1.8.0 加的），元素是模块下标。
     * 存数组不拼串：上下移按钮要频繁交换元素，拆串再拼又慢又容易引 bug。
     * 持久化在 moveModule 里做。
     */
    private var moduleOrder by mutableStateOf(Constants.Modules.DEFAULT_ORDER.clone())
    /**
     * 空格自定义（v1.8.7）：下标是顶层模块 IDX，值是该模块前面的空格数（0–9）。
     * 默认全是 0（监视条全程紧贴，没空格）。
     */
    private var spaceBefore by mutableStateOf(
        Constants.Modules.parseSpaceBefore("")
    )
    /** 空格自定义输入框的文本（跟 spaceBefore 双向同步） */
    private var spaceBeforeText by mutableStateOf("")
    /** 监视条位置微调（px），正值向右 / 向下 */
    private var offsetX by mutableStateOf(0)
    private var offsetY by mutableStateOf(0)
    /**
     * 「刷新时间」输入框里的文本，不是数值。
     *
     * 存文本是为了让用户能正常键入：输入「1.5」会经过「1」「1.」两个中间态，
     * 要是每次按键都解析成数字再格式化回去，用户永远打不出小数点后面那一位。
     * 校验和截断推到 commitInterval() 里做。
     */
    private var intervalText by mutableStateOf("")
    /** 「仅在选定应用显示」开关，默认关 */
    private var appFilterEnabled by mutableStateOf(false)
    /** 白名单包名 */
    private var whitelist by mutableStateOf<Set<String>>(emptySet())
    /** 为 true 时显示应用选择页，顶掉主界面 */
    private var showAppPicker by mutableStateOf(false)
    private var xposedConnected by mutableStateOf(false)
    private var gpuFreqText by mutableStateOf("-- MHz")
    private var gpuUsageText by mutableStateOf("-- %")
    private var lastUpdateText by mutableStateOf("上次同步：从未")

    /** 版本号从 PackageManager 取，免得依赖 BuildConfig（AGP 默认不生成它了） */
    private val versionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            LogUtils.w("read versionName failed", e)
            ""
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, Constants.Config.UPDATE_INTERVAL_MS.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        showPortrait = readSwitch(Constants.Remote.KEY_SHOW_PORTRAIT)
        showLandscape = readSwitch(Constants.Remote.KEY_SHOW_LANDSCAPE)
        showScreenOff = readSwitch(Constants.Remote.KEY_SHOW_SCREEN_OFF, default = false)
        wakeIntervalSec = readInt(
            Constants.Modules.KEY_WAKE_INTERVAL,
            Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC
        )
        for (i in 0 until Constants.Modules.COUNT) {
            // 默认值取 DEFAULT_ENABLED（v1.8.11 起只默认开 CPU/GPU/功率/FPS），
            // 与 NexusBridge.readModuleFlags 保持一致，否则界面显示的开关状态
            // 会和监视条实际显示的项目对不上
            moduleFlags[i] = readSwitch(
                Constants.Modules.KEYS[i],
                default = Constants.Modules.DEFAULT_ENABLED[i]
            )
            moduleNames[i] = readString(
                Constants.Modules.nameKey(i),
                Constants.Modules.DEFAULT_NAMES[i]
            )
        }
        backgroundEnabled = readSwitch(Constants.Modules.KEY_BACKGROUND, default = false)
        autoContrastEnabled = readSwitch(Constants.Modules.KEY_AUTO_CONTRAST, default = false)
        textColorIndex = readInt(Constants.Modules.KEY_TEXT_COLOR, 0)
            .coerceIn(0, Constants.Ui.TEXT_COLORS.size - 1)
        cpuBarEnabled = readSwitch(Constants.Modules.KEY_CPU_BAR, default = true)
        gpuBarEnabled = readSwitch(Constants.Modules.KEY_GPU_BAR, default = true)
        dualCellEnabled = readSwitch(Constants.Modules.KEY_DUAL_CELL, default = false)
        appFilterEnabled = readSwitch(Constants.Modules.KEY_APP_FILTER, default = false)
        whitelist = readWhitelist()
        fontSizeSp = readFloat(Constants.Modules.KEY_FONT_SIZE, Constants.Ui.TEXT_SIZE_SP)
        fontBold = readSwitch(Constants.Modules.KEY_FONT_BOLD, default = true)
        spacingDp = readFloat(Constants.Modules.KEY_SPACING, Constants.Ui.DIVIDER_WIDTH_DP)
        offsetX = readInt(Constants.Modules.KEY_OFFSET_X, 0)
            .coerceIn(Constants.Modules.OFFSET_MIN_PX, Constants.Modules.OFFSET_MAX_PX)
        offsetY = readInt(Constants.Modules.KEY_OFFSET_Y, 0)
            .coerceIn(Constants.Modules.OFFSET_MIN_PX, Constants.Modules.OFFSET_MAX_PX)
        intervalText = formatInterval(
            readInt(Constants.Modules.KEY_UPDATE_INTERVAL, Constants.Config.UPDATE_INTERVAL_MS)
                .coerceIn(
                    Constants.Config.UPDATE_INTERVAL_MIN_MS,
                    Constants.Config.UPDATE_INTERVAL_MAX_MS
                )
        )
        loadFpsSourceFlags()
        sfPreferRoot = readSwitch(Constants.Modules.KEY_SF_PREFER_ROOT, default = false)
        fpsDebug = readSwitch(Constants.Modules.KEY_FPS_DEBUG, default = false)
        appTheme = readInt(Constants.Modules.KEY_APP_THEME, Constants.Modules.APP_THEME_SYSTEM)
        loadModuleOrder()
        uiWallpaperName = readString(Constants.Modules.KEY_UI_WALLPAPER, "")
        spaceBefore = Constants.Modules.parseSpaceBefore(
            readString(Constants.Modules.KEY_SPACE_BEFORE, "")
        )
        spaceBeforeText = readString(Constants.Modules.KEY_SPACE_BEFORE, "")

        setContent {
            // 主题只影响 App 界面：跟随系统就用 isSystemInDarkTheme()，
            // 否则用用户选定的固定值
            val dark = when (appTheme) {
                Constants.Modules.APP_THEME_LIGHT -> false
                Constants.Modules.APP_THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            NexusFloatTheme(darkTheme = dark) {
                // 壁纸选择器：从相册挑一张图当软件界面壁纸（v1.8.5）
                val wallpaperPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri -> pickWallpaper(uri) }
                // 壁纸背景（v1.8.7）：载入原图，不做模糊之类处理，保持原比例铺满屏幕；
                // 没壁纸就退回纯色
                val wallpaper = remember(uiWallpaperName) {
                    if (uiWallpaperName.isEmpty()) null else loadWallpaperBitmap()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (wallpaper != null) {
                        Image(
                            bitmap = wallpaper.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(MdThemeSurface))
                    }
                    if (showAppPicker) {
                        AppPickerScreen(
                            modifier = Modifier.fillMaxSize(),
                            selected = whitelist,
                            onToggle = { pkg, checked -> toggleWhitelist(pkg, checked) },
                            onClearAll = { saveWhitelist(emptySet()) },
                            onBack = { showAppPicker = false }
                        )
                        // 系统返回键应该退出选择页，而不是退出应用
                        BackHandler { showAppPicker = false }
                    } else {
                        MainScreen(
                            modifier = Modifier.fillMaxSize(),
                            showPortrait = showPortrait,
                            showLandscape = showLandscape,
                            onShowPortraitChange = { checked ->
                                showPortrait = checked
                                saveSwitch(Constants.Remote.KEY_SHOW_PORTRAIT, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "竖屏已开启监视器" else "竖屏已关闭监视器",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onShowLandscapeChange = { checked ->
                                showLandscape = checked
                                saveSwitch(Constants.Remote.KEY_SHOW_LANDSCAPE, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "横屏已开启监视器" else "横屏已关闭监视器",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            showScreenOff = showScreenOff,
                            onShowScreenOffChange = { checked ->
                                showScreenOff = checked
                                saveSwitch(Constants.Remote.KEY_SHOW_SCREEN_OFF, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "息屏时已开启监视器" else "息屏时已关闭监视器",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            wakeIntervalSec = wakeIntervalSec,
                            onWakeIntervalChange = { delta -> changeWakeInterval(delta) },
                            moduleFlags = moduleFlags,
                            onModuleChange = { index, checked ->
                                moduleFlags[index] = checked
                                saveSwitch(Constants.Modules.KEYS[index], checked)
                            },
                            moduleNames = moduleNames,
                            onModuleNameChange = { index, value ->
                                moduleNames[index] = value
                                saveString(Constants.Modules.nameKey(index), value)
                            },
                            moduleOrder = moduleOrder,
                            onMoveModule = { idx, up -> moveModule(idx, up) },
                            cpuBarEnabled = cpuBarEnabled,
                            onCpuBarChange = { checked ->
                                cpuBarEnabled = checked
                                saveSwitch(Constants.Modules.KEY_CPU_BAR, checked)
                            },
                            gpuBarEnabled = gpuBarEnabled,
                            onGpuBarChange = { checked ->
                                gpuBarEnabled = checked
                                saveSwitch(Constants.Modules.KEY_GPU_BAR, checked)
                            },
                            backgroundEnabled = backgroundEnabled,
                            onBackgroundChange = { checked ->
                                backgroundEnabled = checked
                                saveSwitch(Constants.Modules.KEY_BACKGROUND, checked)
                            },
                            autoContrastEnabled = autoContrastEnabled,
                            onAutoContrastChange = { checked ->
                                autoContrastEnabled = checked
                                saveSwitch(Constants.Modules.KEY_AUTO_CONTRAST, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "字色将跟随背景明暗" else "字色恢复为选定颜色",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            textColorIndex = textColorIndex,
                            onTextColorChange = { index ->
                                textColorIndex = index
                                saveInt(Constants.Modules.KEY_TEXT_COLOR, index)
                            },
                            dualCellEnabled = dualCellEnabled,
                            onDualCellChange = { checked ->
                                dualCellEnabled = checked
                                saveSwitch(Constants.Modules.KEY_DUAL_CELL, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "功率已按双电芯翻倍" else "功率已恢复单电芯",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            appFilterEnabled = appFilterEnabled,
                            onAppFilterChange = { checked ->
                                appFilterEnabled = checked
                                saveSwitch(Constants.Modules.KEY_APP_FILTER, checked)
                                if (checked && whitelist.isEmpty()) {
                                    // 开了过滤却一个应用没选，等于全程隐藏，直接引导去选
                                    Toast.makeText(
                                        this@MainActivity,
                                        "请先选择要显示监视条的应用",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showAppPicker = true
                                }
                            },
                            selectedAppCount = whitelist.size,
                            onPickApps = { showAppPicker = true },
                            fontSizeSp = fontSizeSp,
                            onFontSizeChange = { delta -> changeFontSize(delta) },
                            fontBold = fontBold,
                            onFontBoldChange = { bold -> changeFontBold(bold) },
                            spacingDp = spacingDp,
                            onSpacingChange = { delta -> changeSpacing(delta) },
                            offsetX = offsetX,
                            onOffsetXChange = { delta -> changeOffset(true, delta) },
                            offsetY = offsetY,
                            onOffsetYChange = { delta -> changeOffset(false, delta) },
                            onOffsetReset = { resetOffset() },
                            intervalText = intervalText,
                            onIntervalTextChange = { intervalText = it },
                            onIntervalCommit = { commitInterval() },
                            fpsSourceFlags = fpsSourceFlags,
                            onFpsSourceFlagChange = { index, checked ->
                                fpsSourceFlags[index] = checked
                                saveSwitch(Constants.Modules.FpsSource.KEYS[index], checked)
                            },
                            onApplyPreset = { preset -> applyFpsPreset(preset) },
                            sfPreferRoot = sfPreferRoot,
                            onSfPreferRootChange = { checked ->
                                sfPreferRoot = checked
                                saveSwitch(Constants.Modules.KEY_SF_PREFER_ROOT, checked)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (checked) "SurfaceFlinger 改走 root dumpsys"
                                    else "SurfaceFlinger 改走 Binder（不起进程）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            fpsDebug = fpsDebug,
                            onFpsDebugChange = { checked ->
                                fpsDebug = checked
                                saveSwitch(Constants.Modules.KEY_FPS_DEBUG, checked)
                            },
                            onRestartSystemUi = { restartSystemUi() },
                            appTheme = appTheme,
                            onAppThemeChange = { value ->
                                appTheme = value
                                saveInt(Constants.Modules.KEY_APP_THEME, value)
                            },
                            hasWallpaper = uiWallpaperName.isNotEmpty(),
                            onPickWallpaper = { wallpaperPicker.launch("image/*") },
                            onClearWallpaper = { clearUiWallpaper() },
                            spaceBeforeText = spaceBeforeText,
                            onSpaceBeforeCommit = { commitSpaceBefore(it) },
                            xposedConnected = xposedConnected,
                            lastUpdateText = lastUpdateText,
                            versionName = versionName
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 设置界面要显示实时 GPU 读数，所以在前台期间明确要一份采集；
        // 退到后台就把这个需求撤掉，免得 App 打开过一次就让采集永久跑着
        GpuCollectorWorker.setUiDemand(true)
        GpuCollectorLauncher.startInProcess()
        mainHandler.post(refreshRunnable)
        // 每次进界面都补推一次整表：覆盖「上次改设置时 root 还没授权」
        // 以及「用户从旧版本升上来、Settings.Global 里还没有快照」这两种情况
        publishSettings()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(refreshRunnable)
        GpuCollectorWorker.setUiDemand(false)
        super.onStop()
    }

    private fun restartSystemUi() {
        Toast.makeText(this, "正在请求 Root 重启 SystemUI...", Toast.LENGTH_SHORT).show()
        Thread {
            val out = ExecUtils.exec("killall com.android.systemui || pkill -f com.android.systemui")
            LogUtils.i("Restart SystemUI result: $out")
        }.start()
    }

    /**
     * 载入显示顺序（v1.8.0 加的）。
     *
     * 读取逻辑跟 NexusBridge.readModuleOrder 一样，但走的是 App 侧的读取链
     * （Remote 优先、本地兜底）——两侧的解析规则必须一致，
     * 不然 App 显示的顺序和监视条实际顺序会对不上。
     */
    private fun loadModuleOrder() {
        val raw = readString(Constants.Modules.KEY_MODULE_ORDER, "")
        if (raw.isEmpty()) {
            moduleOrder = Constants.Modules.DEFAULT_ORDER.clone()
            return
        }
        val parsed = raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toIntArray()
        moduleOrder = if (Constants.Modules.isValidOrder(parsed)) {
            parsed
        } else {
            Constants.Modules.DEFAULT_ORDER.clone()
        }
    }

    /**
     * 从系统相册 / 文件选择器挑一张图当软件界面壁纸（v1.8.5）。
     *
     * 选中的 Uri 是 ContentProvider 的临时授权，重启之后就失效了，所以马上复制到
     * 应用私有目录，只存文件名。解码后放在 uiWallpaperBitmap 里给 Compose 层当背景；
     * 文件名持久化到 Constants.Modules#KEY_UI_WALLPAPER。
     */
    private fun pickWallpaper(uri: Uri?) {
        if (uri == null) {
            return
        }
        try {
            val dir = File(filesDir, "wallpaper").apply { mkdirs() }
            val name = "ui_${System.currentTimeMillis()}.jpg"
            val target = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            // 解码时顺手压缩一下，免得超大图把内存撑爆
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val full = BitmapFactory.decodeFile(target.absolutePath, opts)
            uiWallpaperBitmap = full
            uiWallpaperName = name
            saveString(Constants.Modules.KEY_UI_WALLPAPER, name)
            // 顺手清掉旧壁纸文件
            dir.listFiles()?.forEach { if (it.name != name) it.delete() }
            Toast.makeText(this, "已应用壁纸", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            LogUtils.w("pickWallpaper failed", t)
            Toast.makeText(this, "壁纸读取失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 清掉软件界面壁纸，恢复纯色背景（v1.8.5） */
    private fun clearUiWallpaper() {
        uiWallpaperBitmap = null
        uiWallpaperName = ""
        saveString(Constants.Modules.KEY_UI_WALLPAPER, "")
        File(filesDir, "wallpaper").listFiles()?.forEach { it.delete() }
        Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show()
    }

    /** 读已保存的壁纸位图（进程重启后从文件恢复），没有壁纸就返回空 */
    private fun loadWallpaperBitmap(): Bitmap? {
        if (uiWallpaperName.isEmpty()) {
            return null
        }
        val file = File(File(filesDir, "wallpaper"), uiWallpaperName)
        if (!file.exists()) {
            return null
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * 空格自定义提交（v1.8.6）：把输入框文本解析出来并持久化。
     * 文本格式是逗号分隔的顶层模块 IDX，比如 "2,4" 表示 CPU、GPU 前各加空格。
     */
    private fun commitSpaceBefore(text: String) {
        spaceBeforeText = text
        spaceBefore = Constants.Modules.parseSpaceBefore(text)
        saveString(Constants.Modules.KEY_SPACE_BEFORE, text)
    }

    /**
     * 把模块在显示顺序里上移 / 下移一格（v1.8.0 加的）。
     *
     * 顺序存的是顶层模块下标数组；移动就是交换相邻元素，越界就不动。
     * 持久化成逗号分隔串——Remote/SharedPreferences 只适合存标量，
     * 数组拆串存是这种简单整型列表的常规做法。
     *
     * moduleIdx 是要移动的顶层模块下标；up 为 true 是上移（往前），false 是下移（往后）。
     */
    private fun moveModule(moduleIdx: Int, up: Boolean) {
        val order = moduleOrder.toMutableList()
        val pos = order.indexOf(moduleIdx)
        if (pos < 0) {
            return
        }
        val target = if (up) pos - 1 else pos + 1
        if (target < 0 || target >= order.size) {
            return
        }
        val tmp = order[pos]
        order[pos] = order[target]
        order[target] = tmp
        moduleOrder = order.toIntArray()
        saveString(
            Constants.Modules.KEY_MODULE_ORDER,
            order.joinToString(",")
        )
    }

    /**
     * 读开关：优先 RemotePreferences（SystemUI 侧读的就是它），
     * 没绑上 Xposed 服务就退回本地 SharedPreferences。
     */
    private fun readSwitch(key: String, default: Boolean = true): Boolean {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    return remotePrefs.getBoolean(key, default)
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read $key from RemotePreferences", e)
            }
        }
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        return localPrefs.getBoolean(key, default)
    }

    /**
     * 写开关：本地和 RemotePreferences 双写。
     * SystemUI 侧每秒轮询，所以改完大约 1 秒内生效，不用重启作用域。
     */
    private fun saveSwitch(key: String, enabled: Boolean) {
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putBoolean(key, enabled).apply()

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remotePrefs.edit().putBoolean(key, enabled).apply()
            } catch (e: Exception) {
                LogUtils.e("Failed to save $key to RemotePreferences", e)
            }
        }
        publishSettings()
    }

    /**
     * 把设置整表推到 Settings.Global，给 SystemUI 侧读。
     *
     * 这是 v1.6.11 加的第三条通道：RemotePreferences 要求模块进程被注入
     * （澎湃 OS 4 不满足），ContentProvider 要求跨应用 query 能通（可能被
     * SELinux 挡住），而这条只要 root 在就通。
     *
     * 放后台线程：写入要 fork 一次 settings 命令，几十毫秒，不该卡住点开关的手感。
     * 每次改动都推整表，不做增量——整表才几百字节，比维护增量状态简单可靠。
     */
    private fun publishSettings() {
        Thread { SettingsChannel.publish(applicationContext) }.start()
    }

    /**
     * 读白名单：包名用换行分隔存成一个字符串。
     * 跟开关一样优先 Remote，绑不上就退回本地。
     */
    private fun readWhitelist(): Set<String> {
        val key = Constants.Modules.KEY_APP_WHITELIST
        var raw: String? = null

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    raw = remotePrefs.getString(key, "")
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read whitelist from RemotePreferences", e)
            }
        }
        if (raw == null) {
            val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            raw = localPrefs.getString(key, "")
        }
        return raw?.split(Constants.Modules.WHITELIST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    /** 写白名单并同步到 Remote，顺便更新界面状态 */
    private fun saveWhitelist(packages: Set<String>) {
        whitelist = packages
        val key = Constants.Modules.KEY_APP_WHITELIST
        val raw = packages.joinToString(Constants.Modules.WHITELIST_SEPARATOR)

        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putString(key, raw).apply()

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remotePrefs.edit().putString(key, raw).apply()
            } catch (e: Exception) {
                LogUtils.e("Failed to save whitelist to RemotePreferences", e)
            }
        }
        publishSettings()
    }

    private fun toggleWhitelist(packageName: String, checked: Boolean) {
        val next = whitelist.toMutableSet()
        if (checked) {
            next.add(packageName)
        } else {
            next.remove(packageName)
        }
        saveWhitelist(next)
    }

    /** 读浮点设定项；跟开关一样优先 Remote，绑不上就退回本地 */
    private fun readFloat(key: String, default: Float): Float {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    return remotePrefs.getFloat(key, default)
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read $key from RemotePreferences", e)
            }
        }
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        return localPrefs.getFloat(key, default)
    }

    /**
     * 读字符串设定项；跟开关一样优先 Remote，绑不上就退回本地。
     *
     * 用 contains 判断，而不是「取到空串就当没设过」：空串是自定义名称的合法取值
     * （表示不显示名称），要是空串回退默认值，用户就永远清不掉名称了。
     */
    private fun readString(key: String, default: String): String {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    return remotePrefs.getString(key, default) ?: default
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read $key from RemotePreferences", e)
            }
        }
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        return localPrefs.getString(key, default) ?: default
    }

    /** 写字符串设定项：本地和 Remote 双写，悬浮窗侧每秒轮询就能生效 */
    private fun saveString(key: String, value: String) {
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putString(key, value).apply()

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remotePrefs.edit().putString(key, value).apply()
            } catch (e: Exception) {
                LogUtils.e("Failed to save $key to RemotePreferences", e)
            }
        }
        publishSettings()
    }

    /** 写浮点设定项：本地和 Remote 双写，悬浮窗侧每秒轮询就能生效 */
    private fun saveFloat(key: String, value: Float) {
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putFloat(key, value).apply()

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remotePrefs.edit().putFloat(key, value).apply()
            } catch (e: Exception) {
                LogUtils.e("Failed to save $key to RemotePreferences", e)
            }
        }
        publishSettings()
    }

    /** 读整型设定项；跟开关一样优先 Remote，绑不上就退回本地 */
    private fun readInt(key: String, default: Int): Int {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    return remotePrefs.getInt(key, default)
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read $key from RemotePreferences", e)
            }
        }
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        return localPrefs.getInt(key, default)
    }

    /** 写整型设定项：本地和 Remote 双写 */
    private fun saveInt(key: String, value: Int) {
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putInt(key, value).apply()

        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remotePrefs.edit().putInt(key, value).apply()
            } catch (e: Exception) {
                LogUtils.e("Failed to save $key to RemotePreferences", e)
            }
        }
        publishSettings()
    }

    /** 读长整型；只有 GPU 数据的时间戳在用 */
    private fun readLong(key: String, default: Long): Long {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                val remotePrefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (remotePrefs.contains(key)) {
                    return remotePrefs.getLong(key, default)
                }
            } catch (e: Exception) {
                LogUtils.w("Failed to read $key from RemotePreferences", e)
            }
        }
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        return localPrefs.getLong(key, default)
    }

    /**
     * 按步长调字号并双写。撞到上下限就不再变，弹个提示。
     * 悬浮窗侧每秒轮询，字号和背景会一起等比缩放。
     */
    private fun changeFontSize(delta: Float) {
        val target = (fontSizeSp + delta)
            .coerceIn(Constants.Ui.TEXT_SIZE_MIN_SP, Constants.Ui.TEXT_SIZE_MAX_SP)
        if (target == fontSizeSp) {
            Toast.makeText(
                this@MainActivity,
                if (delta > 0) "已是最大字号" else "已是最小字号",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        fontSizeSp = target
        saveFloat(Constants.Modules.KEY_FONT_SIZE, target)
    }

    /** 切换字体粗细（v1.8.9）：true 加粗 / false 常规，双写保存 */
    private fun changeFontBold(bold: Boolean) {
        if (fontBold == bold) {
            return
        }
        fontBold = bold
        saveSwitch(Constants.Modules.KEY_FONT_BOLD, bold)
    }

    /** 按步长调监视项目之间的间隔并双写 */
    private fun changeSpacing(delta: Float) {
        val target = (spacingDp + delta)
            .coerceIn(Constants.Ui.DIVIDER_MIN_DP, Constants.Ui.DIVIDER_MAX_DP)
        if (target == spacingDp) {
            Toast.makeText(
                this@MainActivity,
                if (delta > 0) "已是最大间隔" else "已是最小间隔",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        spacingDp = target
        saveFloat(Constants.Modules.KEY_SPACING, target)
    }

    /**
     * 按步长调监视条位置并双写。
     *
     * horizontal 传 true 调水平（正值向右），false 调垂直（正值向下）。
     */
    private fun changeOffset(horizontal: Boolean, delta: Int) {
        val current = if (horizontal) offsetX else offsetY
        val target = (current + delta)
            .coerceIn(Constants.Modules.OFFSET_MIN_PX, Constants.Modules.OFFSET_MAX_PX)
        if (target == current) {
            Toast.makeText(this, "已到可调范围边界", Toast.LENGTH_SHORT).show()
            return
        }
        if (horizontal) {
            offsetX = target
            saveInt(Constants.Modules.KEY_OFFSET_X, target)
        } else {
            offsetY = target
            saveInt(Constants.Modules.KEY_OFFSET_Y, target)
        }
    }

    /** 位置复位到屏幕顶端居中 */
    private fun resetOffset() {
        offsetX = 0
        offsetY = 0
        saveInt(Constants.Modules.KEY_OFFSET_X, 0)
        saveInt(Constants.Modules.KEY_OFFSET_Y, 0)
        Toast.makeText(this, "已复位到顶端居中", Toast.LENGTH_SHORT).show()
    }

    /**
     * 提交「刷新时间」输入框。
     *
     * 等到失焦或者按下完成时才校验：输入过程中改写内容会让用户打不完整个数字。
     * 解析失败或者超出范围都截断到最近的合法值，再把规范格式回写进输入框，
     * 这样用户立刻能看到系统实际采用的值，不会以为自己输入的 5 秒生效了。
     */
    private fun commitInterval() {
        val minMs = Constants.Config.UPDATE_INTERVAL_MIN_MS
        val maxMs = Constants.Config.UPDATE_INTERVAL_MAX_MS
        val parsed = intervalText.toFloatOrNull()
        val ms = if (parsed == null) {
            // 解析不出来（空串、只有个小数点）：原值保持不动
            readInt(Constants.Modules.KEY_UPDATE_INTERVAL, Constants.Config.UPDATE_INTERVAL_MS)
        } else {
            (parsed * 1000f).toInt().coerceIn(minMs, maxMs)
        }
        intervalText = formatInterval(ms)
        saveInt(Constants.Modules.KEY_UPDATE_INTERVAL, ms)
    }

    /** 毫秒转成「x.x」；界面上按秒显示，存储还是毫秒整数 */
    private fun formatInterval(ms: Int): String =
        String.format(java.util.Locale.US, "%.1f", ms / 1000f)

    /**
     * 调后台唤醒间隔（v1.8.11），单位秒，0 表示关闭。
     *
     * 到边界就不再变，并给个提示——这个值范围比较宽（0–300），
     * 用户长按加号时不容易察觉已经到顶了。
     */
    private fun changeWakeInterval(delta: Int) {
        val target = (wakeIntervalSec + delta).coerceIn(
            Constants.Modules.WAKE_INTERVAL_MIN_SEC,
            Constants.Modules.WAKE_INTERVAL_MAX_SEC
        )
        if (target == wakeIntervalSec) {
            Toast.makeText(
                this@MainActivity,
                if (delta > 0) "已是最大间隔" else "已是最小间隔",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        wakeIntervalSec = target
        saveInt(Constants.Modules.KEY_WAKE_INTERVAL, target)
    }

    /**
     * 载入各 FPS 来源开关。
     *
     * 首次运行（还没有迁移标记）时，按旧版那个 fps_source 整数推导出等价的开关组合
     * 并落盘，否则升级之后用户原本选好的方案会被重置成「全部」。
     * 迁移放在 App 侧做而不是 SystemUI 侧：只有 App 有 RemotePreferences 的写权限。
     */
    private fun loadFpsSourceFlags() {
        val migrated = readSwitch(Constants.Modules.FpsSource.KEY_MIGRATED, default = false)
        if (!migrated) {
            val legacy = readInt(
                Constants.Modules.KEY_FPS_SOURCE,
                Constants.Modules.FPS_SOURCE_AUTO
            )
            val preset = Constants.Modules.FpsSource.PRESETS.getOrElse(legacy) {
                Constants.Modules.FpsSource.PRESETS[0]
            }
            preset.forEachIndexed { index, on ->
                fpsSourceFlags[index] = on
                saveSwitch(Constants.Modules.FpsSource.KEYS[index], on)
            }
            saveSwitch(Constants.Modules.FpsSource.KEY_MIGRATED, true)
            return
        }
        for (i in 0 until Constants.Modules.FpsSource.COUNT) {
            fpsSourceFlags[i] = readSwitch(
                Constants.Modules.FpsSource.KEYS[i],
                default = true
            )
        }
    }

    /** 把预设写进各来源开关；写完照样能逐个微调 */
    private fun applyFpsPreset(preset: Int) {
        val values = Constants.Modules.FpsSource.PRESETS.getOrNull(preset) ?: return
        values.forEachIndexed { index, on ->
            fpsSourceFlags[index] = on
            saveSwitch(Constants.Modules.FpsSource.KEYS[index], on)
        }
        Toast.makeText(
            this@MainActivity,
            "已套用预设：" + Constants.Modules.FpsSource.PRESET_LABELS[preset],
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshStatus() {
        // 先试 root 通道（高通 KGSL 必需），读不到再试直读（有些联发科节点对普通应用开放）
        var localFreq = GpuRootReader.readFreqMhz()
        if (localFreq <= 0) {
            localFreq = SysfsReader.direct().readGpuFreqMhz()
        }
        var localUsage = GpuRootReader.readUsagePercent()
        if (localUsage < 0) {
            localUsage = SysfsReader.direct().readGpuUsagePercent()
        }

        var remoteFreq = 0
        var remoteUsage = -1
        var updated = 0L

        val svc: XposedService? = App.getXposedService()
        // 「已连接」的判据不再只看 XposedService：有些框架（实测澎湃 OS 4 上的
        // LSPosed）只注入 SystemUI、不注入模块自身进程，服务永远绑不上，可监视条
        // 照样工作——这时候显示「未连接」会让人以为模块坏了。改成「服务可用，或者
        // 监视条确实在读设置」，后者由本地 prefs 里的时间戳体现
        val providerAlive = readLong(Constants.Remote.KEY_UPDATED_AT, 0L) > 0
        xposedConnected = svc != null || providerAlive

        if (svc != null) {
            try {
                val prefs = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remoteFreq = prefs.getInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
                remoteUsage = prefs.getInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
                updated = prefs.getLong(Constants.Remote.KEY_UPDATED_AT, 0L)
            } catch (_: Exception) {
            }
        }
        // Remote 拿不到就读本地：GPU 采集从 v1.6.10 起总会写本地一份
        if (remoteFreq <= 0) {
            remoteFreq = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
        }
        if (remoteUsage < 0) {
            remoteUsage = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
        }
        if (updated <= 0L) {
            updated = readLong(Constants.Remote.KEY_UPDATED_AT, 0L)
        }

        val finalFreq = if (remoteFreq > 0) remoteFreq else localFreq
        val finalUsage = if (remoteUsage >= 0) remoteUsage else localUsage

        gpuFreqText = if (finalFreq > 0) "$finalFreq MHz" else "N/A"
        gpuUsageText = if (finalUsage >= 0) "$finalUsage %" else "N/A"

        lastUpdateText = when {
            updated > 0 -> {
                val diffSec = maxOf(0L, (System.currentTimeMillis() - updated) / 1000)
                "上次同步：$diffSec 秒前 (Remote)"
            }
            localFreq > 0 || localUsage >= 0 -> "数据源：本机直读 sysfs"
            else -> "上次同步：从未 (请检查 root 授权)"
        }
    }
}

/**
 * 底部分页。
 *
 * 原先所有卡片都堆在一个长滚动页里，从「显示时机」翻到「外观与校正」要划过
 * 十个项目开关。按用途拆成三页：一页看状态和何时显示，一页调监视项目和外观，
 * 一页放帧率来源这类调完基本不再动的设置。
 */
private enum class MainTab(val title: String, val icon: String) {
    /** 运行状态 + 显示时机 + 维护 + 使用须知：打开 App 最先要确认的几件事 */
    STATUS("状态", "◉"),
    /** 监视项目 + 外观与校正：「显示什么」和「长什么样」搁一起才顺手 */
    MODULES("项目", "☰"),
    /** FPS 来源：六个开关加预设，独占一页 */
    TUNING("高级", "✦")
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    showPortrait: Boolean,
    showLandscape: Boolean,
    onShowPortraitChange: (Boolean) -> Unit,
    onShowLandscapeChange: (Boolean) -> Unit,
    showScreenOff: Boolean,
    onShowScreenOffChange: (Boolean) -> Unit,
    wakeIntervalSec: Int,
    onWakeIntervalChange: (Int) -> Unit,
    moduleFlags: List<Boolean>,
    onModuleChange: (Int, Boolean) -> Unit,
    moduleNames: List<String>,
    onModuleNameChange: (Int, String) -> Unit,
    cpuBarEnabled: Boolean,
    onCpuBarChange: (Boolean) -> Unit,
    gpuBarEnabled: Boolean,
    onGpuBarChange: (Boolean) -> Unit,
    backgroundEnabled: Boolean,
    onBackgroundChange: (Boolean) -> Unit,
    autoContrastEnabled: Boolean,
    onAutoContrastChange: (Boolean) -> Unit,
    textColorIndex: Int,
    onTextColorChange: (Int) -> Unit,
    dualCellEnabled: Boolean,
    onDualCellChange: (Boolean) -> Unit,
    appFilterEnabled: Boolean,
    onAppFilterChange: (Boolean) -> Unit,
    selectedAppCount: Int,
    onPickApps: () -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    fontBold: Boolean,
    onFontBoldChange: (Boolean) -> Unit,
    spacingDp: Float,
    onSpacingChange: (Float) -> Unit,
    offsetX: Int,
    onOffsetXChange: (Int) -> Unit,
    offsetY: Int,
    onOffsetYChange: (Int) -> Unit,
    onOffsetReset: () -> Unit,
    intervalText: String,
    onIntervalTextChange: (String) -> Unit,
    onIntervalCommit: () -> Unit,
    fpsSourceFlags: List<Boolean>,
    onFpsSourceFlagChange: (Int, Boolean) -> Unit,
    onApplyPreset: (Int) -> Unit,
    sfPreferRoot: Boolean,
    onSfPreferRootChange: (Boolean) -> Unit,
    fpsDebug: Boolean,
    onFpsDebugChange: (Boolean) -> Unit,
    moduleOrder: IntArray,
    onMoveModule: (Int, Boolean) -> Unit,
    onRestartSystemUi: () -> Unit,
    appTheme: Int,
    onAppThemeChange: (Int) -> Unit,
    hasWallpaper: Boolean,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    spaceBeforeText: String,
    onSpaceBeforeCommit: (String) -> Unit,
    xposedConnected: Boolean,
    lastUpdateText: String,
    versionName: String
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.STATUS) }

    Column(modifier = modifier) {
        // 每页各自滚动：key(tab) 让每页拿到独立的 ScrollState，切页后回到该页顶部。
        // 共用一个 state 的话，从长页面（项目）切到短页面（外观）会带着上一页的
        // 偏移量过去，看着像内容缺了一块
        val scrollState = key(tab) { rememberScrollState() }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 标题卡只在首页出现：另外两页寸土寸金，版本号在首页看过就够了
            if (tab == MainTab.STATUS) {
                HeaderCard(
                    versionName = versionName,
                    appTheme = appTheme,
                    onAppThemeChange = onAppThemeChange,
                    hasWallpaper = hasWallpaper,
                    onPickWallpaper = onPickWallpaper,
                    onClearWallpaper = onClearWallpaper
                )
            }

            when (tab) {
                MainTab.STATUS -> {
                    StatusCard(
                        xposedConnected = xposedConnected,
                        lastUpdateText = lastUpdateText
                    )
                    DisplayCard(
                        showPortrait = showPortrait,
                        showLandscape = showLandscape,
                        onShowPortraitChange = onShowPortraitChange,
                        onShowLandscapeChange = onShowLandscapeChange,
                        showScreenOff = showScreenOff,
                        onShowScreenOffChange = onShowScreenOffChange,
                        wakeIntervalSec = wakeIntervalSec,
                        onWakeIntervalChange = onWakeIntervalChange,
                        appFilterEnabled = appFilterEnabled,
                        onAppFilterChange = onAppFilterChange,
                        selectedAppCount = selectedAppCount,
                        onPickApps = onPickApps,
                        intervalText = intervalText,
                        onIntervalTextChange = onIntervalTextChange,
                        onIntervalCommit = onIntervalCommit
                    )
                    // 维护排在使用须知前面：它是「让改动生效」的收尾动作，
                    // 属于要动手的部分，该排在只读说明前头
                    MaintenanceCard(onRestartSystemUi = onRestartSystemUi)
                    GuidanceCard()
                    ChangelogCard()
                }

                MainTab.MODULES -> {
                    ModulesCard(
                        moduleFlags = moduleFlags,
                        onModuleChange = onModuleChange,
                        moduleNames = moduleNames,
                        onModuleNameChange = onModuleNameChange,
                        moduleOrder = moduleOrder,
                        onMoveModule = onMoveModule,
                        spaceBeforeText = spaceBeforeText,
                        onSpaceBeforeCommit = onSpaceBeforeCommit,
                        cpuBarEnabled = cpuBarEnabled,
                        onCpuBarChange = onCpuBarChange,
                        gpuBarEnabled = gpuBarEnabled,
                        onGpuBarChange = onGpuBarChange
                    )
                    // 外观紧跟监视项目：「显示什么」和「长什么样」是同一件事的两面，
                    // 改完项目往下滑就能调字号间距，不用翻页
                    AppearanceCard(
                        backgroundEnabled = backgroundEnabled,
                        onBackgroundChange = onBackgroundChange,
                        autoContrastEnabled = autoContrastEnabled,
                        onAutoContrastChange = onAutoContrastChange,
                        textColorIndex = textColorIndex,
                        onTextColorChange = onTextColorChange,
                        dualCellEnabled = dualCellEnabled,
                        onDualCellChange = onDualCellChange,
                        fontSizeSp = fontSizeSp,
                        onFontSizeChange = onFontSizeChange,
                        fontBold = fontBold,
                        onFontBoldChange = onFontBoldChange,
                        spacingDp = spacingDp,
                        onSpacingChange = onSpacingChange,
                        offsetX = offsetX,
                        onOffsetXChange = onOffsetXChange,
                        offsetY = offsetY,
                        onOffsetYChange = onOffsetYChange,
                        onOffsetReset = onOffsetReset
                    )
                }

                MainTab.TUNING -> FpsSourceCard(
                    fpsSourceFlags = fpsSourceFlags,
                    onFpsSourceFlagChange = onFpsSourceFlagChange,
                    onApplyPreset = onApplyPreset,
                    sfPreferRoot = sfPreferRoot,
                    onSfPreferRootChange = onSfPreferRootChange,
                    fpsDebug = fpsDebug,
                    onFpsDebugChange = onFpsDebugChange
                )
            }
        }

        BottomTabBar(selected = tab, onSelect = { tab = it })
    }
}

/**
 * 底部分页栏（v1.8.8 悬浮 dock 样式）。
 *
 * 没用 Material3 的 NavigationBar：本界面的卡片、开关行配色都是手写的
 * （见 Theme.kt 里的常量），混进来一个走 MaterialTheme 取色的组件会跟周围对不上。
 * v1.8.8 起改成半透明悬浮胶囊：整体缩小、左右留边距、圆角大弧度、
 * 背景 50% 透明，像手机 dock 栏那样浮在内容上面。
 */
@Composable
private fun BottomTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit
) {
    // 悬浮 dock：留出左右和底部的边距，去掉顶边分隔线
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(CardSurface.copy(alpha = 0.5f))
            .border(1.dp, CardStroke.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        MainTab.entries.forEach { item ->
            val active = item == selected
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (active) MdThemePrimaryContainer else Color.Transparent)
                    .toggleable(
                        value = active,
                        role = Role.Tab,
                        onValueChange = { if (it) onSelect(item) }
                    )
                    .semantics { contentDescription = item.title }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = item.icon,
                    fontSize = 15.sp,
                    color = if (active) MdThemeOnPrimaryContainer else MdThemeOnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = item.title,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MdThemeOnPrimaryContainer else MdThemeOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HeaderCard(
    versionName: String,
    appTheme: Int,
    onAppThemeChange: (Int) -> Unit,
    hasWallpaper: Boolean,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MdThemePrimaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NexusFloat",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MdThemeOnPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "酷安@叶落雨巷",
                        fontSize = 13.sp,
                        color = MdThemeOnSurfaceVariant
                    )
                    Text(
                        text = "Github@YeLuoYuXiang",
                        fontSize = 13.sp,
                        color = MdThemeOnSurfaceVariant
                    )
                }
                if (versionName.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MdThemeSurface)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "v$versionName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MdThemePrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 主题切换放在版本号这一行下面：它只影响 App 界面本身，
            // 跟任何监视项都无关，搁标题卡里最合适
            SegmentedSelector(
                options = Constants.Modules.APP_THEME_LABELS.toList(),
                selectedIndex = appTheme,
                onSelect = onAppThemeChange
            )

            // 软件界面壁纸（v1.8.5）：配合液态玻璃材质，选图当页面背景。
            // 有壁纸时显示清除按钮，没有就只显示选择按钮
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPickWallpaper,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Text(
                        text = if (hasWallpaper) "更换界面壁纸" else "选择界面壁纸",
                        fontSize = 13.sp
                    )
                }
                if (hasWallpaper) {
                    OutlinedButton(
                        onClick = onClearWallpaper,
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text(text = "清除", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 卡片外壳：统一圆角、描边和内边距，再带个小标题 */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // v1.8.7：去掉液态玻璃效果，大项目卡片背景统一 50% 透明。
    // 半透明让壁纸/底色透出来（选了壁纸的话透过卡片能看到原图）
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardSurface.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, CardStroke),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )
            content()
        }
    }
}

/**
 * 一体化开关行：整行是一个圆角容器，名称和开关都在里头。
 *
 * 开和关的区别体现在四处：容器底色、描边颜色、右侧「开/关」徽标，以及 Switch 自己的
 * 位置和配色。这样强光下或者色觉异常时也能分辨，不用依赖单一视觉线索。
 *
 * 点击区域分两种形态：
 * - 不传 name 时整行可点，Switch 只当指示（onCheckedChange = null），免得两个点击目标重叠。
 * - 传了 name 时行内多一个输入框，整行可点就会让「点进输入框」顺带把这一项关掉。
 *   所以改成左侧名称区可点 + Switch 自身可点，输入框独立成一个目标，三者互不重叠。
 *   这是从结构上排除冲突，不是靠 Compose 的指针事件消费顺序。
 *
 * sub 表示是不是子项（比如 CPU频率），是的话缩进并显示层级引导线；
 * enabled 为 false 时置灰（父项关了），免得让人以为单开子项就能显示；
 * name 非 null 时在名称和开关之间插一个监视条名称输入框，onNameChange 与它成对提供。
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    sub: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
    name: String? = null,
    onNameChange: ((String) -> Unit)? = null
) {
    val on = checked && enabled
    // v1.8.7：去掉液态玻璃，开关行恢复原配色
    val container = if (on) ToggleOnContainer else ToggleOffContainer
    val borderColor = if (on) ToggleOnBorder else CardStroke
    val labelColor = when {
        !enabled -> MdThemeOnSurface.copy(alpha = 0.38f)
        on -> MdThemeOnSurface
        else -> MdThemeOnSurfaceVariant
    }
    val shape = RoundedCornerShape(14.dp)
    val hasNameField = name != null && onNameChange != null

    // 整行可切换的开关语义；有输入框时就挪到左侧名称区上
    val toggleModifier = Modifier
        // 用 toggleable 不用 clickable，读屏软件才会把它报成开关并播报开/关状态
        .toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
        .semantics {
            contentDescription = if (description == null) label else "$label，$description"
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (sub) 16.dp else 0.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, borderColor, shape)
            .then(if (hasNameField) Modifier else toggleModifier)
            .padding(horizontal = 14.dp, vertical = if (description == null) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sub) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (on) MdThemePrimary else SubItemGuide)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (hasNameField) {
                        // 名称区独立成点击目标，跟输入框、Switch 三者互不重叠
                        toggleModifier.padding(vertical = 4.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = label,
                fontSize = if (sub) 14.sp else 15.sp,
                fontWeight = if (sub) FontWeight.Normal else FontWeight.Bold,
                color = labelColor
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MdThemeOnSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
                )
            }
        }

        // 名称输入框夹在项目名和开关之间
        if (name != null && onNameChange != null) {
            NameField(
                value = name,
                enabled = enabled,
                onValueChange = onNameChange
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Text(
            text = if (checked) "开" else "关",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (on) MdThemePrimary else MdThemeOnSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            // 没输入框时点击由整行接管，这里传 null 让 Switch 变成纯指示；
            // 有输入框时整行不再可点，Switch 就得自己能点
            onCheckedChange = if (hasNameField) onCheckedChange else null,
            enabled = enabled
        )
    }
}

/**
 * 监视条名称输入框。
 *
 * 所在的整行是个 toggleable 开关，点哪儿都会切换开关状态。这里不用额外拦手势：
 * Compose 的指针事件由内向外传，BasicTextField 自己的点击检测会在 Main pass 把
 * down 事件消费掉，外层 toggleable 收到时已经被消费了，所以点进输入框不会顺带
 * 把这一项关掉。
 *
 * 留空表示不显示名称、只显示数值，所以不做「空则回退默认值」的处理——
 * 那样用户就永远清不掉名称了。
 */
@Composable
private fun NameField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            // 换行会把单行监视条顶成两行，制表符在等宽字体下宽度不可控，一并过滤掉
            val cleaned = raw.filter { it != '\n' && it != '\r' && it != '\t' }
            onValueChange(cleaned.take(Constants.Modules.NAME_MAX_LEN))
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) MdThemeOnSurface else MdThemeOnSurface.copy(alpha = 0.38f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MdThemePrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MdThemeSurface)
            .border(1.dp, CardStroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    // 空值是合法状态（只显示数值），所以用占位提示，而不是塞回默认名
                    Text(
                        text = "无",
                        fontSize = 13.sp,
                        color = MdThemeOnSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                inner()
            }
        }
    )
}

@Composable
fun DisplayCard(
    showPortrait: Boolean,
    showLandscape: Boolean,
    onShowPortraitChange: (Boolean) -> Unit,
    onShowLandscapeChange: (Boolean) -> Unit,
    showScreenOff: Boolean,
    onShowScreenOffChange: (Boolean) -> Unit,
    wakeIntervalSec: Int,
    onWakeIntervalChange: (Int) -> Unit,
    appFilterEnabled: Boolean,
    onAppFilterChange: (Boolean) -> Unit,
    selectedAppCount: Int,
    onPickApps: () -> Unit,
    intervalText: String,
    onIntervalTextChange: (String) -> Unit,
    onIntervalCommit: () -> Unit
) {
    SectionCard(title = "显示时机") {
        // 控制这个方向下显不显示监视条
        ToggleRow(
            label = "竖屏",
            checked = showPortrait,
            onCheckedChange = onShowPortraitChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "横屏",
            checked = showLandscape,
            onCheckedChange = onShowLandscapeChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 息屏显示（v1.8.10）：默认关——息屏后屏幕也看不到内容，
        // 藏起来还能一并把采集停了省电
        ToggleRow(
            label = "息屏显示",
            checked = showScreenOff,
            onCheckedChange = onShowScreenOffChange,
            description = "关闭时锁屏/灭屏后自动隐藏监视条并停止采集"
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 关闭时在所有界面显示；开启后只有白名单应用在前台时才显示，其余时间停采集
        ToggleRow(
            label = Constants.Modules.LABEL_APP_FILTER,
            checked = appFilterEnabled,
            onCheckedChange = onAppFilterChange,
            description = "关闭时在所有界面显示；隐藏期间停止采集，不耗电"
        )
        if (appFilterEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            ActionRow(
                label = "选择应用",
                description = if (selectedAppCount > 0) {
                    "已选 $selectedAppCount 个应用"
                } else {
                    "尚未选择，监视条将一直隐藏"
                },
                buttonText = "选择",
                onClick = onPickApps
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 刷新时间放本卡最后：它决定「多久看一次」，跟「什么时候显示」同属时机范畴，
        // 但影响的是采集频率不是可见性，所以排最末
        NumberFieldRow(
            label = Constants.Modules.LABEL_UPDATE_INTERVAL,
            value = intervalText,
            onValueChange = onIntervalTextChange,
            onCommit = onIntervalCommit,
            unit = "秒",
            description = "范围 %.1f–%.1f 秒。调大更省电，调小数值更跟手；" 
                .format(
                    Constants.Config.UPDATE_INTERVAL_MIN_MS / 1000f,
                    Constants.Config.UPDATE_INTERVAL_MAX_MS / 1000f
                ) + "低于 0.7 秒时帧率仍按 0.7 秒统计，不会更快"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 后台唤醒（v1.8.11）：ColorOS 全部清除后台会把模块进程置为 stopped，
        // 而 GPU 数据必须由那个进程用 root 读，进程起不来数据就停更。
        // 这里让 SystemUI 按设定间隔发广播把它唤醒
        StepperRow(
            label = Constants.Modules.LABEL_WAKE_INTERVAL,
            value = if (wakeIntervalSec <= 0) "关闭" else "$wakeIntervalSec 秒",
            onDecrease = { onWakeIntervalChange(-Constants.Modules.WAKE_INTERVAL_STEP_SEC) },
            onIncrease = { onWakeIntervalChange(Constants.Modules.WAKE_INTERVAL_STEP_SEC) },
            description = "全部清除后台后 GPU 数据不刷新时用它恢复；默认 15 秒，" +
                    "范围 0–${Constants.Modules.WAKE_INTERVAL_MAX_SEC} 秒，0 为关闭。" +
                    "间隔越小越不容易断，也越费电"
        )

    }
}

@Composable
fun ModulesCard(
    moduleFlags: List<Boolean>,
    onModuleChange: (Int, Boolean) -> Unit,
    moduleNames: List<String>,
    onModuleNameChange: (Int, String) -> Unit,
    moduleOrder: IntArray,
    onMoveModule: (Int, Boolean) -> Unit,
    spaceBeforeText: String,
    onSpaceBeforeCommit: (String) -> Unit,
    cpuBarEnabled: Boolean,
    onCpuBarChange: (Boolean) -> Unit,
    gpuBarEnabled: Boolean,
    onGpuBarChange: (Boolean) -> Unit
) {
    SectionCard(title = "监视项目") {
        Text(
            text = "中间的输入框是该项显示在监视条上的名称，可改成任意文字（如把 C 改成 CPU），" +
                    "留空则只显示数值。右侧 ↑↓ 调整该项在监视条上的位置。",
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 空格自定义（v1.8.7）：监视条默认全程紧贴没空格；
        // 输入「项目名+空格数」比如 ZRAM2 表示 ZRAM 前加 2 个空格，多个用逗号分隔
        Text(
            text = "空格自定义：默认监视条各项紧贴无空格。输入「项目名+空格数」" +
                    "即可在对应项目前加空格，如 ZRAM2 表示 ZRAM 前加 2 格，" +
                    "多个用逗号分隔：CPU1,ZRAM2。项目名用列表中的显示名。",
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicTextField(
            value = spaceBeforeText,
            onValueChange = { onSpaceBeforeCommit(it) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MdThemeSurface)
                .border(1.dp, CardStroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(fontSize = 13.sp, color = MdThemeOnSurface),
            singleLine = true,
            cursorBrush = SolidColor(MdThemePrimary),
            decorationBox = { inner ->
                if (spaceBeforeText.isEmpty()) {
                    Text(
                        text = "例：CPU1,ZRAM2",
                        fontSize = 13.sp,
                        color = MdThemeOnSurfaceVariant.copy(alpha = 0.5f)
                    )
                } else {
                    inner()
                }
            }
        )
        Spacer(modifier = Modifier.height(10.dp))
        // v1.8.0：按 moduleOrder 渲染顶层行（用户可以上下移），
        // 子项（CPU频率 / GPU频率 / 24小时制）跟着各自的父项显示
        moduleOrder.forEachIndexed { position, moduleIdx ->
            if (position > 0) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 顶层行带名称输入框（时间等无名称项除外）与排序按钮
            val hasName = Constants.Modules.hasName(moduleIdx)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    ToggleRow(
                        label = Constants.Modules.LABELS[moduleIdx],
                        checked = moduleFlags.getOrElse(moduleIdx) { true },
                        onCheckedChange = { checked -> onModuleChange(moduleIdx, checked) },
                        name = if (hasName) moduleNames.getOrElse(moduleIdx) { "" } else null,
                        onNameChange = if (hasName) {
                            { value -> onModuleNameChange(moduleIdx, value) }
                        } else {
                            null
                        }
                    )
                }
                // 排序按钮：首行禁用上移、末行禁用下移（点了也没反应，置灰提示）
                OrderButtons(
                    canUp = position > 0,
                    canDown = position < moduleOrder.size - 1,
                    onUp = { onMoveModule(moduleIdx, true) },
                    onDown = { onMoveModule(moduleIdx, false) }
                )
            }
            // 该父项的子项：缩进显示、没有排序按钮（跟着父项走）
            for (child in Constants.Modules.DEFAULT_NAMES.indices) {
                if (Constants.Modules.PARENT[child] != moduleIdx) {
                    continue
                }
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = Constants.Modules.LABELS[child],
                    checked = moduleFlags.getOrElse(child) { true },
                    onCheckedChange = { checked -> onModuleChange(child, checked) },
                    sub = true,
                    enabled = moduleFlags.getOrElse(moduleIdx) { true },
                    name = if (Constants.Modules.hasName(child)) {
                        moduleNames.getOrElse(child) { "" }
                    } else {
                        null
                    },
                    onNameChange = if (Constants.Modules.hasName(child)) {
                        { value -> onModuleNameChange(child, value) }
                    } else {
                        null
                    }
                )
            }
            // 柱状图开关紧跟在各自的 CPU / GPU 后面，作为这一项的子开关。
            // 放这儿而不是外观页：它属于「这一项显示什么」，跟项目开关是一回事
            if (moduleIdx == Constants.Modules.IDX_CPU) {
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = Constants.Modules.LABEL_CPU_BAR,
                    checked = cpuBarEnabled,
                    onCheckedChange = onCpuBarChange,
                    sub = true,
                    enabled = moduleFlags.getOrElse(moduleIdx) { true },
                    description = "各核占用竖条，关掉可缩短监视条"
                )
            }
            if (moduleIdx == Constants.Modules.IDX_GPU) {
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = Constants.Modules.LABEL_GPU_BAR,
                    checked = gpuBarEnabled,
                    onCheckedChange = onGpuBarChange,
                    sub = true,
                    enabled = moduleFlags.getOrElse(moduleIdx) { true },
                    description = "GPU 占用竖条"
                )
            }
        }
    }
}

/**
 * 排序按钮组（v1.8.0）：上移 / 下移两个小按钮。
 *
 * 尺寸跟 StepperRow 的 +/- 保持一致（34dp），看着统一；
 * 到边界时置灰而不是藏起来——藏起来会让整列按钮左右跳。
 */
@Composable
private fun OrderButtons(
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Row {
        FilledTonalButton(
            onClick = onUp,
            enabled = canUp,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(34.dp)
        ) {
            Text(text = "↑", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(4.dp))
        FilledTonalButton(
            onClick = onDown,
            enabled = canDown,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(34.dp)
        ) {
            Text(text = "↓", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * FPS 来源卡片：六个来源各一个开关，按固定顺序依次试，关掉的跳过。
 *
 * 取代了 v1.6.1 的「自动 / 高通 / 联发科」三选一。那种预设背后是一条写死的尝试链，
 * 某个来源在某机型上出数慢或不准时，用户只能整条换掉——而实际碰到的恰恰是这种情况
 * （TimeStats 要两次采样才出数，有约 1 秒延迟；面板节点是瞬时值但只有高通有）。
 * 现在可以精确到「关掉 TimeStats、只留面板」。
 *
 * 每个开关旁边标着它在监视条上的诊断字母，所以看到 `60t` 就知道该关哪一个。
 * 顺序就是优先级，按响应速度和准确度排的，不随勾选变化。
 */
@Composable
fun FpsSourceCard(
    fpsSourceFlags: List<Boolean>,
    onFpsSourceFlagChange: (Int, Boolean) -> Unit,
    onApplyPreset: (Int) -> Unit,
    sfPreferRoot: Boolean,
    onSfPreferRootChange: (Boolean) -> Unit,
    fpsDebug: Boolean,
    onFpsDebugChange: (Boolean) -> Unit
) {
    SectionCard(title = Constants.Modules.LABEL_FPS_SOURCE_LIST) {
        Text(
            text = "按下面的顺序依次尝试，取到就停，关掉的跳过。数值不准或有延迟时，" +
                    "开「FPS 诊断」看帧率后面的字母，把对应的那一项关掉即可。",
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 预设只是往开关里批量写值，写完照样能逐个微调
        Text(
            text = "快速预设",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        PresetRow(
            options = Constants.Modules.FpsSource.PRESET_LABELS.toList(),
            onSelect = onApplyPreset
        )

        Spacer(modifier = Modifier.height(12.dp))

        Constants.Modules.FpsSource.LABELS.forEachIndexed { index, label ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            ToggleRow(
                label = label,
                checked = fpsSourceFlags.getOrElse(index) { true },
                onCheckedChange = { checked -> onFpsSourceFlagChange(index, checked) },
                description = Constants.Modules.FpsSource.DESCRIPTIONS[index] +
                        "　标记 " + Constants.Modules.FpsSource.TAGS[index]
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 这一项影响的是 SurfaceFlinger 两条路的取数通道，不是来源开关，所以分开放
        ToggleRow(
            label = Constants.Modules.LABEL_SF_PREFER_ROOT,
            checked = sfPreferRoot,
            onCheckedChange = onSfPreferRootChange,
            description = "默认关：走 Binder，不额外起进程。开启后每秒多一次 dumpsys，" +
                    "仅在 SurfaceFlinger 两项都读不到时才需要"
        )

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            label = Constants.Modules.LABEL_FPS_DEBUG,
            checked = fpsDebug,
            onCheckedChange = onFpsDebugChange,
            description = "帧率后显示来源字母，与上面各项对应；x 表示全部失败"
        )

        // v1.8.0：探测面板节点 / 诊断面板方案两个按钮已经删了——
        // 面板方案的路径和判定问题在 v1.7.2~v1.7.9 就定位并修好了，
        // 这两个纯诊断入口没什么日常价值，留着只会让「高级」页越来越长
    }
}

/**
 * 预设按钮行：点一下把各来源开关填成常用组合。
 *
 * 跟 SegmentedSelector 的区别是这里没有「当前选中」——预设写完值就完事了，
 * 之后用户可以逐个改，这时候任何一个预设都不再准确描述现状，
 * 硬要高亮某一项反而是误导。
 */
@Composable
private fun PresetRow(
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            FilledTonalButton(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text(text = label, fontSize = 13.sp)
            }
        }
    }
}

/**
 * 分段选择器：一行几个等宽按钮，选中的那个高亮。
 *
 * 用它不用下拉菜单，是为了让几个选项同时可见、一次点击就能切换。
 * v1.6.6 起用于主题切换（浅色 / 深色 / 跟随系统）。
 */
@Composable
private fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, CardStroke, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val itemShape = RoundedCornerShape(11.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(if (selected) MdThemePrimaryContainer else CardSurface)
                    .border(
                        1.dp,
                        if (selected) ToggleOnBorder else CardStroke,
                        itemShape
                    )
                    // 用 selectable 语义，读屏软件会播报「已选中」
                    .toggleable(
                        value = selected,
                        role = Role.RadioButton,
                        onValueChange = { if (it) onSelect(index) }
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MdThemeOnPrimaryContainer else MdThemeOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AppearanceCard(
    backgroundEnabled: Boolean,
    onBackgroundChange: (Boolean) -> Unit,
    autoContrastEnabled: Boolean,
    onAutoContrastChange: (Boolean) -> Unit,
    textColorIndex: Int,
    onTextColorChange: (Int) -> Unit,
    dualCellEnabled: Boolean,
    onDualCellChange: (Boolean) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    fontBold: Boolean,
    onFontBoldChange: (Boolean) -> Unit,
    spacingDp: Float,
    onSpacingChange: (Float) -> Unit,
    offsetX: Int,
    onOffsetXChange: (Int) -> Unit,
    offsetY: Int,
    onOffsetYChange: (Int) -> Unit,
    onOffsetReset: () -> Unit
) {
    SectionCard(title = "外观与校正") {
        // 给监视条加个圆角灰底，宽度跟着监视项目的增减自动伸缩
        ToggleRow(
            label = Constants.Modules.LABEL_BACKGROUND,
            checked = backgroundEnabled,
            onCheckedChange = onBackgroundChange,
            description = "浅色壁纸下加深灰底，保证文字可读"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 自动反色：跟着系统状态栏图标的明暗定字色。
        // 它是「背景」的子项，但开了背景之后就不起作用了——背景本身就提供了确定的
        // 深色底，这时候固定字色最清楚，所以置灰提示
        ToggleRow(
            label = Constants.Modules.LABEL_AUTO_CONTRAST,
            checked = autoContrastEnabled,
            onCheckedChange = onAutoContrastChange,
            sub = true,
            enabled = !backgroundEnabled,
            description = if (backgroundEnabled) {
                "已开背景，字色由下面的「字体颜色」决定"
            } else {
                "浅色壁纸下自动改用深色字，会覆盖下面选的颜色"
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 字体颜色：自动反色生效时会被盖掉，所以这时候置灰
        ColorPickerRow(
            label = Constants.Modules.LABEL_TEXT_COLOR,
            selectedIndex = textColorIndex,
            enabled = !(autoContrastEnabled && !backgroundEnabled),
            onSelect = onTextColorChange
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 双电芯：节点只报单颗电芯的电压，所以功率得乘 2
        ToggleRow(
            label = Constants.Modules.LABEL_DUAL_CELL,
            checked = dualCellEnabled,
            onCheckedChange = onDualCellChange,
            description = "双电芯机型功率偏小一半时开启"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 字体大小：加减按钮，背景和柱条跟着字号等比缩放
        StepperRow(
            label = Constants.Modules.LABEL_FONT_SIZE,
            value = "${fontSizeSp.toInt()} sp",
            onDecrease = { onFontSizeChange(-Constants.Ui.TEXT_SIZE_STEP_SP) },
            onIncrease = { onFontSizeChange(Constants.Ui.TEXT_SIZE_STEP_SP) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 字体粗细（v1.8.9）：加粗 / 常规，跟字体大小用同一套加减按钮样式
        StepperRow(
            label = Constants.Modules.LABEL_FONT_BOLD,
            value = if (fontBold) "加粗" else "常规",
            onDecrease = { onFontBoldChange(false) },
            onIncrease = { onFontBoldChange(true) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 项目间隔：统一控制各监视项目之间的空隙，同样按字号等比缩放
        StepperRow(
            label = Constants.Modules.LABEL_SPACING,
            value = "${spacingDp.toInt()} dp",
            onDecrease = { onSpacingChange(-Constants.Ui.DIVIDER_STEP_DP) },
            onIncrease = { onSpacingChange(Constants.Ui.DIVIDER_STEP_DP) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 位置微调：改的是悬浮窗的 x/y 偏移，不动内容布局。
        // 默认贴屏幕顶端居中，挖孔、圆角、异形屏或者第三方状态栏布局下
        // 可能压住别的元素，得挪开一点
        Text(
            text = "位置微调（相对屏幕顶端居中）",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        StepperRow(
            label = Constants.Modules.LABEL_OFFSET_X,
            value = "$offsetX px",
            onDecrease = { onOffsetXChange(-Constants.Modules.OFFSET_STEP_PX) },
            onIncrease = { onOffsetXChange(Constants.Modules.OFFSET_STEP_PX) },
            description = "负值向左、正值向右"
        )

        Spacer(modifier = Modifier.height(8.dp))

        StepperRow(
            label = Constants.Modules.LABEL_OFFSET_Y,
            value = "$offsetY px",
            onDecrease = { onOffsetYChange(-Constants.Modules.OFFSET_STEP_PX) },
            onIncrease = { onOffsetYChange(Constants.Modules.OFFSET_STEP_PX) },
            description = "负值向上、正值向下"
        )

        if (offsetX != 0 || offsetY != 0) {
            Spacer(modifier = Modifier.height(8.dp))
            ActionRow(
                label = "复位位置",
                description = "回到屏幕顶端居中",
                buttonText = "复位",
                onClick = onOffsetReset
            )
        }
    }
}

/**
 * 一行「名称 + 色块」：从预设色板里挑监视条字色。
 *
 * 不做取色器：状态栏上的字就几个像素高，饱和度或明度稍低一点就看不清，
 * 让用户自由取色多半只会选出一个读不了的颜色。色板里都是深浅背景上都还能
 * 分辨的高对比色。
 *
 * enabled 为 false 是「自动反色」生效的时候——那时字色由背景明暗决定，
 * 这里选什么都会被盖掉，所以置灰。
 */
@Composable
private fun ColorPickerRow(
    label: String,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, CardStroke, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MdThemeOnSurface else MdThemeOnSurface.copy(alpha = 0.38f),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Constants.Ui.TEXT_COLOR_NAMES.getOrElse(selectedIndex) { "" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MdThemePrimary else MdThemeOnSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        if (!enabled) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "已开自动反色，字色由背景明暗决定",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Constants.Ui.TEXT_COLORS.forEachIndexed { index, argb ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(argb).copy(alpha = if (enabled) 1f else 0.3f))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            // 选中用主题色描边，不加对勾：色块太小，
                            // 对勾在浅色块上要用深色、深色块上要用浅色，
                            // 描边只要一种颜色就能在所有色块上看清
                            color = if (selected) MdThemePrimary else CardStroke,
                            shape = RoundedCornerShape(9.dp)
                        )
                        .toggleable(
                            value = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onValueChange = { if (it) onSelect(index) }
                        )
                        .semantics {
                            contentDescription =
                                Constants.Ui.TEXT_COLOR_NAMES.getOrElse(index) { "" }
                        }
                )
            }
        }
    }
}

/**
 * 一行「名称 + 数值 + 加减按钮」，容器样式跟 ToggleRow 一致，排版才统一。
 *
 * description 是可选的补充说明，为空就不占位。
 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    description: String = ""
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, CardStroke, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurface
            )
            if (description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
        }
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemePrimary
        )
        Spacer(modifier = Modifier.width(10.dp))
        FilledTonalButton(
            onClick = onDecrease,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(34.dp)
        ) {
            Text(text = "−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onIncrease,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(34.dp)
        ) {
            Text(text = "+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 一行「名称 + 说明 + 数字输入框 + 单位」。
 *
 * 用输入框不用加减按钮：刷新时间的可调范围跨 4 倍（0.5–2.0 秒），
 * 按步长点过去得十几下，直接键入快得多。
 *
 * 输入过程中不校验、不改写用户敲的内容——边打边纠正会让人打不完整个数字
 * （输入「1.5」时刚敲完「1.」就被改成「1.0」）。校验推到 onCommit：
 * 失焦或者按下完成时才截断到范围内并回写规范格式。
 */
@Composable
private fun NumberFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    unit: String,
    description: String = ""
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, CardStroke, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurface
            )
            if (description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                // 只挡明显非法的字符，不碰数值本身：小数点允许一个，
                // 长度限制防止粘贴进来一长串
                val cleaned = raw.filter { it.isDigit() || it == '.' }
                if (cleaned.count { it == '.' } <= 1 && cleaned.length <= 4) {
                    onValueChange(cleaned)
                }
            },
            singleLine = true,
            textStyle = TextStyle(
                color = MdThemePrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(MdThemePrimary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MdThemeSurface)
                .border(1.dp, CardStroke, RoundedCornerShape(8.dp))
                .padding(vertical = 8.dp)
                // 失焦时也提交：用户可能直接点别处，不按「完成」
                .onFocusChanged { state -> if (!state.isFocused) onCommit() }
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = unit,
            fontSize = 13.sp,
            color = MdThemeOnSurfaceVariant
        )
    }
}

/** 一行「名称 + 说明 + 动作按钮」 */
@Composable
private fun ActionRow(
    label: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, CardStroke, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        FilledTonalButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(text = buttonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StatusCard(
    xposedConnected: Boolean,
    lastUpdateText: String
) {
    SectionCard(title = "运行状态") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LSPosed 服务",
                fontSize = 14.sp,
                color = MdThemeOnSurface,
                modifier = Modifier.weight(1f)
            )

            val badgeBg = if (xposedConnected) StatusConnectedBg else StatusDisconnectedBg
            val badgeTextColor = if (xposedConnected) StatusConnected else StatusDisconnected

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (xposedConnected) "已连接" else "未连接",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeTextColor
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // v1.8.6：运行状态栏不再显示 GPU 频率/占用——
        // 这两项监视条上的 G 项目已经承担了，重复展示只会占版面

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = lastUpdateText,
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** 一块读数：小标题 + 大字数值 */
@Composable
private fun MetricTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ToggleOffContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurface
        )
    }
}

@Composable
fun GuidanceCard() {
    SectionCard(title = "使用须知") {
        GuidanceItem(1, "授予 Root 权限，用于后台采集 GPU 指标。")
        Spacer(modifier = Modifier.height(6.dp))
        GuidanceItem(2, "LSPosed 作用域必须勾选「系统界面 (SystemUI)」。")
        Spacer(modifier = Modifier.height(6.dp))
        GuidanceItem(3, "以上开关约 1 秒内生效，无需重启作用域。")
        Spacer(modifier = Modifier.height(6.dp))
        GuidanceItem(4, "监视条隐藏期间采集线程一并停止，不额外耗电。")
    }
}

@Composable
private fun GuidanceItem(index: Int, text: String) {
    Row(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = "$index.",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemePrimary,
            modifier = Modifier.width(18.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MdThemeOnSurfaceVariant
        )
    }
}

/**
 * 维护操作卡片，在「状态」页。
 *
 * 单独成卡而不是塞进 GuidanceCard：这里是真会动系统的操作（杀掉 SystemUI 进程），
 * 跟只读的说明文字混排容易误触。
 */
@Composable
fun MaintenanceCard(onRestartSystemUi: () -> Unit) {
    SectionCard(title = "维护") {
        ActionRow(
            label = "重启系统界面",
            description = "修改作用域后点此重启 SystemUI 使 Hook 生效",
            buttonText = "重启",
            onClick = onRestartSystemUi
        )
    }
}

/**
 * 一条更新记录。
 *
 * 只保留 1.6.x 这一轮：更早的版本改的是已经被后续重写掉的实现（比如 FPS 的三选一
 * 方案），列出来对用户没什么参考价值，反而占满整屏。
 */
private data class ChangelogEntry(val version: String, val summary: String)

/**
 * 更新日志，新版在前。
 *
 * 每条一句话，只讲用户能感知到的变化——内部重构（比如把 root 取数从 cat 换成
 * shell 内建 read）只在能被观察到时才写进来，那一条的观察点是耗电。
 */
private val CHANGELOG = listOf(
    ChangelogEntry(
        "1.8.11",
        "修复 ColorOS「全部清除后台」后 GPU 数据停更：定时发一条广播把模块进程" +
                "唤醒。被全部清除的应用会被系统置为 stopped 状态，普通唤醒方式都" +
                "送不到，只有带 FLAG_INCLUDE_STOPPED_PACKAGES 的广播能穿透；" +
                "「显示时机」新增「后台唤醒」，间隔可调（0–300 秒，默认 15 秒）；" +
                "默认开启的项目精简为 CPU、GPU、功率、FPS 四项。"
    ),
    ChangelogEntry(
        "1.8.10",
        "修复 GPU 占用 100% 时百分号消失（列宽改回 4 字符，8%→10% 仍不跳动）；" +
                "显示时机新增「息屏显示」开关，关闭后锁屏/灭屏自动隐藏监视条并停采集；" +
                "修复充电功率偶发跳变（正常 +0.5W 突然显示 +60W）——加入突变守卫，" +
                "尖峰需连续 3 拍确认才采纳，并把电流上限从 20A 收紧到 12A。"
    ),
    ChangelogEntry(
        "1.8.9",
        "外观与校正新增「字体粗细」加减按钮（加粗 / 常规，与字体大小同款 UI）；" +
                "监视条 FPS 项目离前一个项目的间隔默认再收一个空格宽；" +
                "CPU/GPU 占用率数值改为固定两位数宽度——8% 跳到 10% 时" +
                "监视条不再整体左右移动。"
    ),
    ChangelogEntry(
        "1.8.8",
        "FPS 数值不再紧贴冒号：前面留一个空格缓冲位，十位数/百位数切换时" +
                "不再顶到冒号、视觉不跳；底部三个底栏改为半透明悬浮 dock 样式" +
                "（缩小、圆角、悬浮在内容之上）；修复锁屏后亮屏 FPS 长时间不更新" +
                "的问题——面板重扫退避从 60 拍缩短到 5 拍，亮屏后最快约 5 秒恢复。"
    ),
    ChangelogEntry(
        "1.8.7",
        "空格自定义改为「项目名+空格数」：输入 ZRAM2 即在 ZRAM 前加 2 个空格，" +
                "多个用逗号分隔（如 CPU1,ZRAM2）；大项目卡片（运行状态、显示时机等）" +
                "背景统一调为 50% 透明；界面壁纸改为原图直出——保持原比例铺满屏幕、" +
                "不再模糊或压暗；删去 v1.8.5 的液态玻璃材质效果。"
    ),
    ChangelogEntry(
        "1.8.6",
        "GPU 数据双通道：SystemUI 侧 root 直读优先（重启后 App 进程未起来也能" +
                "更新），读不到再等模块 App 进程传；运行状态页移除 GPU 频率/占用" +
                "两项；监视条删光所有项目内外的空格（数值全部左对齐紧贴名称冒号），" +
                "并新增「空格自定义」——在监视项目页输入项目编号即可在对应项前加空格；" +
                "修复中文项目名称与数值不对齐（偏下）的问题。"
    ),
    ChangelogEntry(
        "1.8.5",
        "界面升级 iOS 液态玻璃风格：卡片与开关行半透明玻璃质感、上缘白色高光" +
                "描边、背景高斯模糊；新增自选手机图片作为软件界面壁纸；" +
                "监视条修两处小细节——CPU/GPU 占用率与括号之间不再有空格（括号" +
                "用英文半角），FPS 数值左对齐紧贴冒号、个位数帧率自动贴齐。"
    ),
    ChangelogEntry(
        "1.8.4",
        "面板 FPS 方案整体恢复到 v1.7.6-debug 实测可用的实现：readMeasuredFps" +
                "选 frame_count 最大且活动的节点（主屏出帧最高自然胜出）+ " +
                "vsync_event 差分兜底 + 15 条深路径候选 + direct/root 双通道。" +
                "v1.7.9 起的缓存保留、删直读通道与多轮改写均回归，故整体回滚。"
    ),
    ChangelogEntry(
        "1.8.3",
        "重写高通面板帧率方案：依据内核源码（sde_crtc.c）确认 measured_fps 是" +
                "读取时实时计算的帧率，frame_count 是随读时机的窗口帧数而非累积器，" +
                "彻底丢弃 frame_count 判据（v1.7.2 起的灾难源头）；路径探测改为" +
                "sde-crtc-0 固定优先、通配全展开逐个读、find 兜底，适配澎湃 / " +
                "ColorOS 的 Android 15/16。"
    ),
    ChangelogEntry(
        "1.8.1",
        "回退高通面板 measured_fps 方案到 v1.7.1 的取数逻辑：去掉 v1.7.2 起的" +
                "frame_count 判据、vsync_event 差分与缓存保留——这几处在实测中" +
                "会让面板方案在 frame_count 瞬时清零时整拍判死、或差分建基线时" +
                "返回 0，表现为「p 方案用不了 / p 不稳定切 t」。现在恢复为" +
                "「取第一个非零 fps」的简单策略，主屏读数自然命中。"
    ),
    ChangelogEntry(
        "1.8.0",
        "新增 CPU 温度项目（thermal_zone 自动识别 CPU 源）与各项目显示顺序的" +
                "上下移动排序；CPU/GPU 柱状图与占用率之间的空隙移除；" +
                "移除「探测面板节点 / 诊断面板方案」两个调试入口；" +
                "新增开机自启，重启后无需手动打开 App 即可恢复 GPU 数据采集。"
    ),
    ChangelogEntry(
        "1.7.3",
        "补全 LOG2 实际节点路径：measured_fps 在骁龙 8 Gen 3 / 8 Elite 上是「外部 crtc」「设备" +
                "crtc」五层嵌套（如 /sys/class/drm/card0-sde-crtc-2/device/card0-sde-crtc-0/" +
                "measured_fps），候选表原本只覆盖到一层，shell 通配展开拿不到；v1.7.2 在这台机器" +
                "上走不通。把 LOG2 的 7 个真实节点（3 个 measured_fps + 4 个 vsync_event）逐条加进" +
                "候选路径表，离线校验逐条确认能被某条通配覆盖。"
    ),
    ChangelogEntry(
        "1.7.2",
        "修复高通面板 p 方案两个 bug：锁屏/息屏态主屏的 measured_fps 会出" +
                "「fps: 0.7 frame_count: 1」这种一秒一帧的伪活动（旧版只看 fps 字段就当" +
                "真活动，显示成 0.7p 误导用户），改判 frame_count ≥ 2 才是真面板；" +
                "LOG2 这台机器所有 measured_fps 都是 0.0/0（crtc-2/5/7 闲置节点、crtc-0 是" +
                "锁屏态），加 vsync_event 差分作为 fallback，从主屏那个非零时间戳" +
                "（实测 9692230326040ns）算帧率。"
    ),
    ChangelogEntry(
        "1.7.1",
        "去掉功率、电流数值中正负号前多余的空白（改为左对齐），并修复 CPU 满载时「100%」" +
                "的百分号被截掉；「显示时机」新增「刷新时间」输入框（0.5–2 秒）；" +
                "「外观与校正」新增监视条位置上下左右微调与复位；" +
                "修复高通新机面板帧率恒为 0（每个 crtc 都挂 measured_fps，主屏不在 crtc-0 时" +
                "会读到闲置节点），「探测面板节点」一并标注活动节点与通配覆盖情况。"
    ),
    ChangelogEntry(
        "1.7.0",
        "修复 TimeStats 帧率掉到个位数（原先会把状态栏、监视条自身这类每秒 1 帧的层" +
                "当成前台画面，现改为排除噪声层并优先匹配前台包名）；" +
                "面板帧率候选路径改用通配覆盖，并新增「探测面板节点」列出本机实际节点；" +
                "修复澎湃 OS 4 / 安卓 17 上监视器不显示与开关不生效。"
    ),
    ChangelogEntry(
        "1.6.7",
        "修复调大字号或自定义名称填中文时文字显示不全（窗口高度改为实测）、" +
                "各项目间隔不统一（去掉数值右侧多余的补白）；新增监视条字体颜色选择；" +
                "扩充安卓 17 的注入候选与窗口类型，兜底注入改为长期重试。"
    ),
    ChangelogEntry(
        "1.6.6",
        "新增监视条时间显示（含 12/24 小时制子开关）、App 界面浅色/深色/跟随系统主题切换，" +
                "并重排设置分页（维护移到使用须知上方、外观与校正并入项目页、底部「外观」改名「高级」）。"
    ),
    ChangelogEntry(
        "1.6.5",
        "修复高通机型面板帧率取不到（只走了直读、未试 root）与 measured_fps 键值串被解析成错值；" +
                "修复切换后台报 x8 与取数空窗期帧率闪回 0。"
    ),
    ChangelogEntry(
        "1.6.4",
        "修复帧率恒为 0：layer 名尾部的 #序号 会随窗口重建变化，导致帧数差分永远对不上；" +
                "同时修复 FPS 来源开关全部关掉仍不生效。"
    ),
    ChangelogEntry(
        "1.6.3",
        "修复进入应用后帧率显示 0：TimeStats 按累计帧数排名，前几名被静止的壁纸与桌面占满；" +
                "改为启用时清零统计、取 32 层，且某一来源报 0 不再截断后续来源。"
    ),
    ChangelogEntry(
        "1.6.2",
        "FPS 由「自动/高通/联发科」三选一改为六个来源各自开关（面板、SurfaceFlinger latency、" +
                "TimeStats、FPSGO、GED KPI、Choreographer），可按诊断字母逐个排除；" +
                "SurfaceFlinger 默认走 Binder 不再起进程。"
    ),
    ChangelogEntry(
        "1.6.1",
        "「重启系统界面」从外观页移到状态页。"
    ),
    ChangelogEntry(
        "1.6.0",
        "root 取数改用 shell 内建 read 并整轮合并为一次管道往返，每秒进程创建从十几次降到 0；" +
                "新增 CPU/GPU 柱状图开关、字体自动反色，界面拆成底部三页签。"
    )
)

/**
 * 更新日志卡片，在「状态」页最后。
 *
 * 默认只展开最新一条，其余折叠——这张卡越往后越长，全展开会把上面的运行状态挤出屏幕，
 * 而用户日常最关心的是「这次装的是什么」。
 */
@Composable
fun ChangelogCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) CHANGELOG else CHANGELOG.take(1)

    SectionCard(title = "更新日志") {
        shown.forEachIndexed { index, entry ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(10.dp))
            }
            ChangelogRow(entry)
        }

        if (CHANGELOG.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = expanded,
                        onValueChange = { expanded = it }
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (expanded) {
                        "收起"
                    } else {
                        "查看历史版本（共 ${CHANGELOG.size} 条）"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemePrimary
                )
            }
        }
    }
}

/** 一行更新记录：版本号徽标 + 概括文字 */
@Composable
private fun ChangelogRow(entry: ChangelogEntry) {
    Row(modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ToggleOnContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "v${entry.version}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.summary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
