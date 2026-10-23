package com.jj.nexusfloat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jj.nexusfloat.R
import com.jj.nexusfloat.frame.FrameBubble
import com.jj.nexusfloat.frame.FrameRecordService
import com.jj.nexusfloat.frame.FrameRecordStore
import com.jj.nexusfloat.frame.FrameSampler
import com.jj.nexusfloat.ui.theme.CardStroke
import com.jj.nexusfloat.ui.theme.CardSurface
import com.jj.nexusfloat.ui.theme.MdThemeOnPrimaryContainer
import com.jj.nexusfloat.ui.theme.MdThemeOnSurface
import com.jj.nexusfloat.ui.theme.MdThemeOnSurfaceVariant
import com.jj.nexusfloat.ui.theme.MdThemePrimary
import com.jj.nexusfloat.ui.theme.MdThemePrimaryContainer
import com.jj.nexusfloat.ui.theme.ToggleOffContainer
import com.jj.nexusfloat.ui.theme.ToggleOnBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================
// 帧记录页
//
// 一级页：设备信息 + 记录列表 + 底部操作条（按应用筛选 / 多选删除 / 拉起悬浮球）。
// 二级页：单条记录的备注、汇总与曲线。
//
// 结构跟统计页一样是「同页替换」的二级视图，不引导航库：这一页本来就是只读
// 看板，为一次查看引入导航栈不划算；BackHandler 补上返回键的行为。
//
// 跟统计页唯一的结构差别：这一页自己滚动、底部操作条固定——筛选/删除/加号
// 得一直够得着，塞进滚动内容里的话记录一长就得划到最底才能找到加号。
// ============================================================

/** 记录列表一次最多读多少条 */
private const val FRAME_LIST_LIMIT = 200

/** 曲线最多画多少个点（抽稀交给存储层，见 FrameRecordStore#querySamplesDownsampled） */
private const val FRAME_CURVE_MAX_POINTS = 3600

/**
 * 帧记录曲线的颜色。
 *
 * 功率橙 / 电量绿 / 温度红沿用统计页的语义色——同一个物理量在两个页面里
 * 必须是同一个颜色，用户在统计页认下的颜色不该到这儿就变了。
 * CPU/GPU 是帧记录新引入的量，配自己的色。
 */
private val FrameFpsColor = ChargeColor
private val FramePowerColor = PowerColor
private val FrameCpuColor = Color(0xFF6366F1)
private val FrameGpuColor = Color(0xFF10B981)
private val FrameRamColor = Color(0xFF8B5CF6)
private val FrameBatteryColor = BatteryColor
private val FrameTempCpuColor = TempColor
private val FrameTempGpuColor = PowerColor
/** 电池温度曲线：用绿色跟 CPU 的橙色区分开 */
private val FrameTempBatteryColor = Color(0xFF16A34A)

/** 一屏要展示的数据，一次 IO 装齐 */
private class FrameListData(
    val records: List<FrameRecordStore.Record>,
    val apps: List<FrameRecordStore.AppEntry>,
    val sampleCount: Int
)

/** 详情页一屏的数据 */
private class FrameDetailData(
    val record: FrameRecordStore.Record,
    val samples: List<FrameRecordStore.Sample>
)

@Composable
fun FrameRecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }

    // 悬浮球开关状态：点按后本地立即翻转，3 秒刷一次时再跟服务真实状态对一遍
    // （长按收球、通知里关掉这类页面外的关闭也能同步回来，不会一直亮着「关闭」）
    var bubbleOn by remember { mutableStateOf(FrameRecordService.isRunning()) }

    // 二级视图：被点开的那条记录 id，0 表示没打开
    var detailId by remember { mutableStateOf(0L) }

    // 列表状态。filterPkg 空串表示「全部」——rememberSaveable 对 null 的自动存取不可靠
    var filterPkg by rememberSaveable { mutableStateOf("") }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val listScroll = rememberScrollState()

    fun back() {
        if (detailId > 0L) {
            detailId = 0L
            refreshTick++
        } else if (selectMode) {
            selectMode = false
            selected.clear()
        }
    }
    BackHandler(enabled = detailId > 0L || selectMode) { back() }

    // 定时刷新：列表在场就 3 秒装一次，记录中的那条和列表才跟手。
    // 进了二级页就停：详情页自己拉自己的数据，两边同时跑是两轮 SQLite 查询
    LaunchedEffect(detailId) {
        while (detailId <= 0L) {
            delay(3000)
            refreshTick++
        }
    }

    // 每次数据刷新都把开关跟服务的真实运行状态对齐一次（含首帧）
    LaunchedEffect(refreshTick) {
        bubbleOn = FrameRecordService.isRunning()
    }

    val data by produceState<FrameListData?>(initialValue = null, refreshTick, filterPkg) {
        value = withContext(Dispatchers.IO) {
            loadFrameList(context, filterPkg.ifEmpty { null })
        }
    }

    // 图标只在包名集合变化时才重新解码（同统计页的做法，解码不便宜）
    val recordPkgs = remember(data) {
        data?.records?.mapNotNull { it.pkg }?.distinct() ?: emptyList()
    }
    val icons = remember(recordPkgs) { appIcons(context, recordPkgs) }

    // 筛选列表里的应用可能不在当前过滤后的记录列表里，所以要单独按 appEntries 的
    // 包名再取一份图标（appIcons 内部有进程级缓存，重复包名不会再解码一遍）
    val appPkgs = remember(data) {
        data?.apps?.mapNotNull { it.pkg }?.distinct() ?: emptyList()
    }
    val appEntryIcons = remember(appPkgs) { appIcons(context, appPkgs) }

    Column(modifier = modifier.fillMaxSize()) {
        if (detailId > 0L) {
            FrameRecordDetail(
                recordId = detailId,
                onBack = { back() },
                modifier = Modifier
                    .weight(1f)
                    .statusBarsPadding()
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(listScroll)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DeviceCard()

                val d = data
                when {
                    d == null -> SectionCard(title = "帧率记录") {
                        Text(
                            text = "正在读取本机记录…",
                            fontSize = 12.sp,
                            color = MdThemeOnSurfaceVariant,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    d.records.isEmpty() -> SectionCard(title = "帧率记录") {
                        EmptyHint(
                            "还没有帧率记录。点右下角的「+」拉起悬浮球，切到游戏里" +
                                    "再点一下球就开始记录；再点一下结束并保存。"
                        )
                    }
                    else -> RecordsCard(
                        records = d.records,
                        totalSamples = d.sampleCount,
                        icons = icons,
                        selectMode = selectMode,
                        selected = selected,
                        filterLabel = d.apps.firstOrNull { it.pkg == filterPkg }?.label,
                        onOpen = { r ->
                            detailId = r.id
                            scope.launch { listScroll.scrollTo(0) }
                        },
                        onToggleSelect = { r ->
                            if (selected.contains(r.id)) selected.remove(r.id)
                            else selected.add(r.id)
                        },
                        onDelete = { r ->
                            // 删到正在记的那条：先丢弃采样，别让它再往里写孤儿点
                            if (r.id == FrameSampler.get(context).currentRecordId()) {
                                FrameSampler.get(context).discardRecording()
                            }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    FrameRecordStore.get(context).deleteRecord(r.id)
                                }
                                if (ok) {
                                    Toast.makeText(
                                        context, "已删除这条记录", Toast.LENGTH_SHORT
                                    ).show()
                                    refreshTick++
                                }
                            }
                        }
                    )
                }
            }

            FrameBottomBar(
                selectMode = selectMode,
                selectedCount = selected.size,
                selectedIds = selected.toList(),
                filterPkg = filterPkg,
                filterLabel = data?.apps?.firstOrNull { it.pkg == filterPkg }?.label,
                appEntries = data?.apps ?: emptyList(),
                appEntryIcons = appEntryIcons,
                onSelectFilter = { pkg ->
                    filterPkg = pkg ?: ""
                    // 筛完把多选清掉：选中的行可能不在筛后的列表里，留着会误删
                    selected.clear()
                },
                onToggleSelectMode = {
                    selectMode = !selectMode
                    selected.clear()
                },
                onSelectAll = {
                    data?.records?.forEach {
                        if (!selected.contains(it.id)) selected.add(it.id)
                    }
                },
                onDeleteSelected = { ids ->
                    // 批量删除同样可能圈进正在记的那条
                    if (FrameSampler.get(context).isRecording()
                        && ids.contains(FrameSampler.get(context).currentRecordId())
                    ) {
                        FrameSampler.get(context).discardRecording()
                    }
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            FrameRecordStore.get(context).deleteRecords(ids)
                        }
                        Toast.makeText(context, "已删除 $n 条记录", Toast.LENGTH_SHORT).show()
                        selected.clear()
                        selectMode = false
                        refreshTick++
                    }
                },
                onDeleteAll = {
                    // 全部删除必含进行中的那条
                    if (FrameSampler.get(context).isRecording()) {
                        FrameSampler.get(context).discardRecording()
                    }
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            FrameRecordStore.get(context).deleteAll()
                        }
                        Toast.makeText(context, "已删除全部 $n 条记录", Toast.LENGTH_SHORT).show()
                        selected.clear()
                        selectMode = false
                        refreshTick++
                    }
                },
                bubbleOn = bubbleOn,
                onToggleBubble = {
                    if (bubbleOn) {
                        // 本地立即翻转，别等下一轮 3 秒刷新
                        FrameRecordService.stop(context)
                        bubbleOn = false
                        Toast.makeText(
                            context,
                            "悬浮球已关闭（记录中的已保存）",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        if (launchBubble(context)) {
                            bubbleOn = true
                            Toast.makeText(
                                context,
                                "悬浮球已显示：切到游戏里点一下开始记录，再点一下结束并保存",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }
    }
}

// ======================= 一级页的卡片 =======================

/**
 * 设备信息卡：CPU 型号 / 手机型号 / 安卓版本。
 *
 * 跟记录无关，但看帧率数据时第一眼要知道「这是哪台机器测的」——换机之后旧记录
 * 还在列表里，没有这行就会把新旧机型的数据混着看。
 */
@Composable
private fun DeviceCard() {
    SectionCard(title = "设备信息") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "CPU 型号",
                value = socModel(),
                modifier = Modifier.weight(1f),
                compact = true,
                iconRes = R.drawable.ic_dev_cpu_ink,
                iconAccentRes = R.drawable.ic_dev_cpu_accent
            )
            MetricTile(
                title = "手机型号",
                value = Build.MODEL ?: "未知",
                modifier = Modifier.weight(1f),
                compact = true,
                iconRes = R.drawable.ic_dev_phone_ink
            )
            MetricTile(
                title = "安卓版本",
                value = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                modifier = Modifier.weight(1f),
                compact = true,
                iconRes = R.drawable.ic_dev_android_ink,
                iconAccentRes = R.drawable.ic_dev_android_accent
            )
        }
    }
}

/**
 * CPU 型号。API 31 起系统直接给 Build.SOC_MODEL；更早的版本退回
 * /proc/cpuinfo 里的 Hardware 行，再不行就是「未知」——编一个型号出来更糟。
 */
private fun socModel(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val soc = Build.SOC_MODEL
        if (!soc.isNullOrBlank() && soc != "unknown") {
            return soc
        }
    }
    try {
        java.io.File("/proc/cpuinfo").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("Hardware") }?.let {
                val v = it.substringAfter(':').trim()
                if (v.isNotEmpty()) return v
            }
        }
    } catch (_: Throwable) {
    }
    return "未知"
}

@Composable
private fun RecordsCard(
    records: List<FrameRecordStore.Record>,
    totalSamples: Int,
    icons: Map<String, ImageBitmap>,
    selectMode: Boolean,
    selected: MutableList<Long>,
    filterLabel: String?,
    onOpen: (FrameRecordStore.Record) -> Unit,
    onToggleSelect: (FrameRecordStore.Record) -> Unit,
    onDelete: (FrameRecordStore.Record) -> Unit
) {
    SectionCard(
        title = if (filterLabel != null) "帧率记录 · $filterLabel" else "帧率记录"
    ) {
        records.forEachIndexed { index, r ->
            RecordRow(
                record = r,
                icon = r.pkg?.let { icons[it] },
                selectMode = selectMode,
                selected = selected.contains(r.id),
                onOpen = { onOpen(r) },
                onToggleSelect = { onToggleSelect(r) },
                onDelete = { onDelete(r) }
            )
            if (index != records.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "共 ${records.size} 条记录、$totalSamples 个采样点。" +
                    "点一条可以进去看帧率曲线和当时的 CPU/GPU/温度读数；" +
                    "点「删除」再点「确认」删掉这条记录和它的采样点。" +
                    "平均帧率只按有画面的采样算，暂停/读条时的 0 不掺进去。",
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun RecordRow(
    record: FrameRecordStore.Record,
    icon: ImageBitmap?,
    selectMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit
) {
    // 两段式删除：先点「删除」再点「确认」。跟统计页的历史行同一个交互，
    // 不用系统弹窗——弹窗会打断整页滚动位置
    var confirm by remember(record.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ToggleOffContainer)
            .border(1.dp, if (selected) ToggleOnBorder else CardStroke, shape)
            .clickable {
                if (selectMode) onToggleSelect() else onOpen()
            }
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            // 手画的复选框：项目里没有图标库，一个带勾的方框足够表达
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MdThemePrimaryContainer else CardSurface.copy(alpha = 0.4f)
                    )
                    .border(
                        1.dp,
                        if (selected) ToggleOnBorder else CardStroke,
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Text(
                        text = "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MdThemeOnPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.appLabel ?: record.pkg ?: "未知应用",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MdThemeOnSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (record.ongoing) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "记录中",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TempColor
                    )
                }
            }
            if (!record.remark.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = record.remark,
                    fontSize = 10.sp,
                    color = MdThemeOnSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = fmtDateTime(record.startTs) + " · " +
                        fmtDuration(record.durationMs(System.currentTimeMillis())),
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "平均 " + fpsText(record.avgFps) + " 帧 · " + powerText(record.avgPowerW),
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (record.avgFps > 0f)
                    String.format(Locale.US, "%.0f", record.avgFps) else "--",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = FrameFpsColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (!selectMode) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (confirm) TempColor.copy(alpha = 0.18f)
                            else CardStroke.copy(alpha = 0.35f)
                        )
                        .clickable {
                            if (confirm) onDelete() else confirm = true
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (confirm) "确认" else "删除",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (confirm) TempColor else MdThemeOnSurfaceVariant
                    )
                }
            }
        }
    }
}

// ======================= 底部操作条 =======================

/**
 * 固定在页底的操作条（悬浮 dock 样式，跟底部页签栏同一种观感）。
 *
 * 左边筛选、右边删除和加号。多选模式下整条换成批量操作：
 * 退出 / 全选 / 删除所选 / 全部删除，后两个都要二次确认。
 */
@Composable
private fun FrameBottomBar(
    selectMode: Boolean,
    selectedCount: Int,
    selectedIds: List<Long>,
    filterPkg: String,
    filterLabel: String?,
    appEntries: List<FrameRecordStore.AppEntry>,
    appEntryIcons: Map<String, ImageBitmap>,
    onSelectFilter: (String?) -> Unit,
    onToggleSelectMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: (List<Long>) -> Unit,
    onDeleteAll: () -> Unit,
    bubbleOn: Boolean,
    onToggleBubble: () -> Unit
) {
    var filterOpen by remember { mutableStateOf(false) }
    // 0=无待确认 1=删所选 2=全部删。两段式确认，跟单条删除一个交互
    var armed by remember { mutableStateOf(0) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(CardSurface.copy(alpha = 0.5f))
            .border(1.dp, CardStroke.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!selectMode) {
            // ---- 左：按应用筛选。点一次弹应用列表挑一个；已有筛选时再点列表里的
            // 「全部应用」取消筛选。按钮上始终写清当前状态，不靠颜色猜 ----
            Box {
                PillButton(
                    text = if (filterPkg.isEmpty()) "筛选全部"
                    else "筛选：${filterLabel ?: "按应用"}",
                    active = filterPkg.isNotEmpty(),
                    onClick = { filterOpen = true }
                )
                DropdownMenu(
                    expanded = filterOpen,
                    onDismissRequest = { filterOpen = false }
                ) {
                    if (appEntries.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有可筛选的应用", fontSize = 12.sp) },
                            onClick = { filterOpen = false }
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "全部应用",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                filterOpen = false
                                onSelectFilter(null)
                            }
                        )
                        appEntries.forEach { app ->
                            val appIcon = appEntryIcons[app.pkg]
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${app.label}（${app.count} 条）",
                                        fontSize = 12.sp,
                                        fontWeight = if (app.pkg == filterPkg) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                },
                                leadingIcon = {
                                    if (appIcon != null) {
                                        Image(
                                            bitmap = appIcon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                        )
                                    } else {
                                        // 拿不到图标就显示应用名首字母占位
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(ToggleOffContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = app.label.take(1).uppercase(Locale.US),
                                                fontSize = 10.sp,
                                                color = MdThemeOnSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    filterOpen = false
                                    onSelectFilter(app.pkg)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ---- 右：展开多选删除 ----
            PillButton(text = "删除", active = false, onClick = onToggleSelectMode)

            // ---- 右：悬浮球开关。未开启显示「+」，开启显示「关闭」，点一下立即翻转 ----
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(
                        if (bubbleOn) TempColor.copy(alpha = 0.18f)
                        else MdThemePrimaryContainer
                    )
                    .border(
                        1.dp,
                        if (bubbleOn) TempColor else ToggleOnBorder,
                        RoundedCornerShape(23.dp)
                    )
                    .clickable { onToggleBubble() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (bubbleOn) "关闭" else "+",
                    fontSize = if (bubbleOn) 11.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bubbleOn) TempColor else MdThemeOnPrimaryContainer
                )
            }
        } else {
            PillButton(text = "退出", active = false, onClick = {
                armed = 0
                onToggleSelectMode()
            })
            PillButton(text = "全选", active = false, onClick = onSelectAll)
            Spacer(modifier = Modifier.weight(1f))
            PillButton(
                text = if (armed == 2) "确认全部删" else "全部删除",
                active = armed == 2,
                danger = true,
                onClick = {
                    if (armed == 2) {
                        armed = 0
                        onDeleteAll()
                    } else {
                        armed = 2
                    }
                }
            )
            PillButton(
                text = if (armed == 1) "确认删 $selectedCount 条" else "删除所选($selectedCount)",
                active = armed == 1,
                danger = true,
                onClick = {
                    if (armed == 1) {
                        armed = 0
                        onDeleteSelected(selectedIds)
                    } else if (selectedCount > 0) {
                        armed = 1
                    }
                }
            )
        }
    }
}

/** 操作条上的胶囊按钮 */
@Composable
private fun PillButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    danger && active -> TempColor.copy(alpha = 0.18f)
                    active -> MdThemePrimaryContainer
                    else -> ToggleOffContainer
                }
            )
            .border(
                1.dp,
                when {
                    danger && active -> TempColor
                    active -> ToggleOnBorder
                    else -> CardStroke
                },
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                danger && active -> TempColor
                active -> MdThemeOnPrimaryContainer
                else -> MdThemeOnSurfaceVariant
            },
            maxLines = 1
        )
    }
}

/** 拉起悬浮球：权限尽力而为，然后起服务。返回是否真的把服务拉起来了 */
private fun launchBubble(context: Context): Boolean {
    if (!FrameBubble.canShow(context) && !FrameBubble.ensurePermission(context)) {
        Toast.makeText(
            context,
            "需要「显示在其他应用上层」权限，请在接下来的页面里允许",
            Toast.LENGTH_LONG
        ).show()
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
            )
        } catch (t: Throwable) {
            // 个别 ROM 没有这个 action；退回应用详情页也能找到权限开关
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName)
                )
            )
        }
        return false
    }
    FrameRecordService.start(context)
    return true
}

// ======================= 二级页 =======================

@Composable
private fun FrameRecordDetail(
    recordId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 记录中的那条要跟着长：每 3 秒重装一次；已完成的静态记录装完就停
    val data by produceState<FrameDetailData?>(initialValue = null, recordId) {
        while (true) {
            value = withContext(Dispatchers.IO) { loadFrameDetail(context, recordId) }
            if (value?.record?.ongoing == false) {
                break
            }
            delay(3000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val d = data
        if (d == null) {
            SectionCard(title = "帧率记录") {
                Text(
                    text = "正在读取记录…",
                    fontSize = 12.sp,
                    color = MdThemeOnSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
            return@Column
        }

        val icons = remember(d.record.pkg) {
            appIcons(context, listOfNotNull(d.record.pkg))
        }
        val icon = d.record.pkg?.let { icons[it] }

        // ---- 头部：返回 + 应用 + 时间 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(ToggleOffContainer)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = d.record.appLabel ?: d.record.pkg ?: "未知应用",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MdThemeOnSurface,
                        maxLines = 1
                    )
                    if (d.record.ongoing) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "记录中",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TempColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = fmtDateTime(d.record.startTs) + " · " +
                            fmtDuration(d.record.durationMs(System.currentTimeMillis())),
                    fontSize = 10.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
        }

        RemarkCard(recordId = d.record.id, initial = d.record.remark ?: "")

        SummaryCard(d.record)

        if (d.samples.size >= 2) {
            DetailCharts(d.samples)
        } else {
            SectionCard(title = "曲线") {
                EmptyHint("采样点还不够画曲线（至少需要两个点）。")
            }
        }
    }
}

/** 备注卡：一进页面带出已存的备注，改完点保存。保存走 IO，不卡主线程 */
@Composable
private fun RemarkCard(recordId: Long, initial: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(recordId) { mutableStateOf(initial) }
    var dirty by remember(recordId) { mutableStateOf(initial.isNotEmpty()) }
    var saved by remember(recordId) { mutableStateOf(true) }

    SectionCard(title = "备注") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ToggleOffContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    dirty = true
                    saved = it == initial
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MdThemeOnSurface),
                cursorBrush = SolidColor(MdThemePrimary),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "给这条记录写点备注，比如「全局极致 + 插电」",
                                fontSize = 12.sp,
                                color = MdThemeOnSurfaceVariant
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "备注只存本机，会显示在记录列表里",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (dirty && !saved) MdThemePrimaryContainer
                    else CardStroke.copy(alpha = 0.35f))
                    .clickable(enabled = dirty && !saved) {
                        val value = text
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FrameRecordStore.get(context).setRemark(recordId, value)
                            }
                            saved = true
                            Toast.makeText(context, "备注已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (saved && dirty) "已保存" else "保存",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dirty && !saved) MdThemeOnPrimaryContainer
                    else MdThemeOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(r: FrameRecordStore.Record) {
    SectionCard(title = "汇总") {
        // 两排、每排四个。选的是玩家真正会看的八个数：
        // 第一排看帧率表现，第二排看热和功耗
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "最大帧率", value = fpsText(r.maxFps),
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "最小帧率", value = fpsText(r.minFps),
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "平均帧率", value = fpsText(r.avgFps),
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "5% LOW", value = fpsText(r.lowFps5),
                modifier = Modifier.weight(1f), compact = true
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "平均 CPU 温",
                value = if (r.avgCpuTemp > 0f)
                    String.format(Locale.US, "%.1f°C", r.avgCpuTemp) else "--",
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "最大 CPU 温",
                value = if (r.peakCpuTemp > 0f)
                    String.format(Locale.US, "%.1f°C", r.peakCpuTemp) else "--",
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "最大电池温",
                value = if (r.peakBatteryTemp > 0f)
                    String.format(Locale.US, "%.1f°C", r.peakBatteryTemp) else "--",
                modifier = Modifier.weight(1f), compact = true
            )
            MetricTile(
                title = "平均功率", value = powerText(r.avgPowerW),
                modifier = Modifier.weight(1f), compact = true
            )
        }
        // 元信息单独一行小字：它们不是性能指标，不该跟上面八个抢眼
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = buildString {
                append("时长 ").append(fmtDuration(r.durationMs(System.currentTimeMillis())))
                if (r.batteryStart >= 0 && r.batteryEnd >= 0) {
                    append("　电量 ").append(r.batteryStart).append("% → ")
                        .append(r.batteryEnd).append("%")
                }
                append("　").append(r.sampleCount).append(" 个采样点")
            },
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant
        )
    }
}

private fun fpsText(v: Float): String =
    if (v > 0f) String.format(Locale.US, "%.1f", v) else "--"

private fun pctText(v: Float): String =
    if (v > 0f) String.format(Locale.US, "%.0f%%", v) else "--"

private fun powerText(v: Float): String =
    if (Math.abs(v) > 0.01f) String.format(Locale.US, "%.1fW", Math.abs(v)) else "功率 --"

// ======================= 曲线 =======================

/**
 * 详情页的全部曲线。每张一条物理量（GPU 那张双轴除外），
 * 刻度用步长制——跟统计页同一个约定，绝不退回均分 4 格的零碎刻度。
 */
@Composable
private fun DetailCharts(samples: List<FrameRecordStore.Sample>) {
    val times = samples.map { it.ts }
    val ticks = axisTicks(times, frameTickStep(times.last() - times.first()))

    SectionCard(title = "帧率 (FPS)") {
        val fps = samples.map { if (it.fps < 0f) null else it.fps }
        val known = fps.filterNotNull()
        if (known.isEmpty()) {
            EmptyHint("没有读到过帧率（本机的帧率来源都不可用），画不出曲线。")
        } else {
            val peak = known.maxOrNull() ?: 0f
            val step = if (peak <= 75f) 15f else 30f
            LineChart(
                lines = listOf(ChartLine("帧率", FrameFpsColor, fps)),
                leftMin = 0f,
                leftMax = (Math.ceil(peak * 1.15 / step) * step).toFloat(),
                leftStep = step,
                leftFormat = { it.toInt().toString() },
                xTicks = ticks,
                xValues = times
            )
        }
    }

    SectionCard(title = "功耗 (W)") {
        val power = samples.map { if (it.powerW == 0f) null else Math.abs(it.powerW) }
        val known = power.filterNotNull()
        if (known.isEmpty()) {
            EmptyHint("没有读到过功率。")
        } else {
            val peak = known.maxOrNull() ?: 0f
            // 功耗刻度固定 0.5W 一条。峰值再高也保持 0.5W 步长，
            // 宁可刻度多几条也别把低功耗阶段的起伏抹平；上限取峰值 1.1 倍向上取整到 0.5
            val step = 0.5f
            LineChart(
                lines = listOf(ChartLine("功率", FramePowerColor, power)),
                leftMin = 0f,
                leftMax = (Math.ceil(peak * 1.1 / step) * step).toFloat(),
                leftStep = step,
                leftFormat = { if (it == it.toInt().toFloat()) it.toInt().toString()
                    else String.format(Locale.US, "%.1f", it) },
                xTicks = ticks,
                xValues = times
            )
        }
    }

    SectionCard(title = "CPU 占用 (%)") {
        LineChart(
            lines = listOf(
                ChartLine("CPU 占用", FrameCpuColor, samples.map { it.cpuTotal })
            ),
            leftMin = 0f,
            leftMax = 100f,
            leftStep = 25f,
            leftFormat = { "${it.toInt()}%" },
            xTicks = ticks,
            xValues = times
        )
    }

    // ---- CPU 频率：只画一条，取这一拍里最高的那个簇 ----
    //
    // 早先按簇画多条（簇 0/1/2…），问题是用户得先分清哪条是大核，而真正
    // 和掉帧相关的是「当前跑得最高的那个簇」。统一成一条：取各簇频率的最大值，
    // 正好是顶部悬浮栏 CPU 频率那个括号里靠右的数，两边可以对着看。
    SectionCard(title = "CPU 频率 (MHz · 各簇最高)") {
        val freqs = samples.map { s ->
            val arr = s.cpuFreqs
            if (arr == null) null else arr.filter { it > 0 }.maxOrNull()?.toFloat()
        }
        val known = freqs.filterNotNull()
        if (known.isEmpty()) {
            EmptyHint("读不到 CPU 频率节点。")
        } else {
            val peak = known.maxOrNull() ?: 0f
            val min = known.minOrNull() ?: 0f
            val (axisMin, axisMax, step) = freqAxis(min, peak)
            LineChart(
                lines = listOf(ChartLine("CPU 频率", FrameCpuColor, freqs)),
                leftMin = axisMin,
                leftMax = axisMax,
                leftStep = step,
                leftFormat = { it.toInt().toString() },
                xTicks = ticks,
                xValues = times
            )
        }
    }

    // ---- GPU：频率左轴 + 占用右轴。两个量纲差着三个数量级，必须分轴 ----
    SectionCard(title = "GPU 频率与占用") {
        val freqs = samples.map { s -> s.gpuFreqMhz.takeIf { it > 0 }?.toFloat() }
        val usage = samples.map { s -> s.gpuUsage.takeIf { it >= 0 }?.toFloat() }
        val knownFreq = freqs.filterNotNull()
        val knownUsage = usage.filterNotNull()
        if (knownFreq.isEmpty() && knownUsage.isEmpty()) {
            EmptyHint("读不到 GPU 频率/占用节点。")
        } else {
            val lines = mutableListOf<ChartLine>()
            if (knownFreq.isNotEmpty()) {
                val peak = knownFreq.maxOrNull() ?: 0f
                val minFreq = knownFreq.minOrNull() ?: 0f
                // 频率轴动态区间 + 动态步长：见 freqAxis。
                // 开销只是对已加载的 samples 数组做一次 min/max 遍历，纯内存运算，
                // 没有任何额外 IO 或 sysfs 读取，不存在额外功耗。
                val (axisMin, axisMax, step) = freqAxis(minFreq, peak)
                lines += ChartLine("GPU 频率", FrameGpuColor, freqs)
                lines += ChartLine(
                    "GPU 占用", FrameCpuColor, usage, useRightAxis = true
                )
                LineChart(
                    lines = lines,
                    leftMin = axisMin,
                    leftMax = axisMax,
                    leftStep = step,
                    leftFormat = { it.toInt().toString() },
                    showRightAxis = knownUsage.isNotEmpty(),
                    rightMin = 0f,
                    rightMax = 100f,
                    rightStep = 25f,
                    rightFormat = { "${it.toInt()}%" },
                    xTicks = ticks,
                    xValues = times
                )
            } else {
                // 只有占用：单轴画百分比
                LineChart(
                    lines = listOf(ChartLine("GPU 占用", FrameCpuColor, usage)),
                    leftMin = 0f,
                    leftMax = 100f,
                    leftStep = 25f,
                    leftFormat = { "${it.toInt()}%" },
                    xTicks = ticks,
                    xValues = times
                )
            }
        }
    }

    // ---- 温度：CPU 和电池两条线。没读到的画断点（null），不编数 ----
    //
    // GPU 温度在多数机型上只有一个粗粒度的 zone，读数长时间不动，画成曲线没多少
    // 信息；电池温度反而是玩家最关心的（手机烫不烫手主要看它），所以换成 CPU + 电池。
    SectionCard(title = "温度 (°C)") {
        val cpu = samples.map { s -> s.cpuTemp.takeIf { !it.isNaN() } }
        val battery = samples.map { s -> s.batteryTemp.takeIf { !it.isNaN() } }
        val known = cpu.filterNotNull() + battery.filterNotNull()
        if (known.isEmpty()) {
            EmptyHint("读不到温度传感器。")
        } else {
            val min = (known.minOrNull() ?: 30f) - 2f
            val max = (known.maxOrNull() ?: 50f) + 2f
            val step = if (max - min > 25f) 10f else 5f
            val lines = mutableListOf<ChartLine>()
            if (cpu.any { it != null }) lines += ChartLine("CPU", FrameTempCpuColor, cpu)
            if (battery.any { it != null }) {
                lines += ChartLine("电池", FrameTempBatteryColor, battery)
            }
            LineChart(
                lines = lines,
                leftMin = (Math.floor((min / step).toDouble()) * step).toFloat(),
                leftMax = (Math.ceil((max / step).toDouble()) * step).toFloat(),
                leftStep = step,
                leftFormat = { "${it.toInt()}°" },
                xTicks = ticks,
                xValues = times
            )
        }
    }
}

/** 记录时长的横轴刻度间隔：几分钟的记录用 1 分钟一格，几小时的用 1 小时一格 */
private fun frameTickStep(spanMs: Long): Long = when {
    spanMs <= 10L * 60_000L -> 60_000L
    spanMs <= 30L * 60_000L -> 5L * 60_000L
    spanMs <= 2L * 3600_000L -> 15L * 60_000L
    spanMs <= 6L * 3600_000L -> 3600_000L
    else -> 6L * 3600_000L
}

/** 频率轴最少几个刻度值（含上下两端）：低于这个值中间会有大段空白读不出数 */
private const val FREQ_MIN_TICKS = 5
/** 频率轴最多几个刻度值。跟 [LineChart] 的 MAX_TICKS 对齐：再密就糊成一片了 */
private const val FREQ_MAX_TICKS = 12
/** 频率轴的步长候选，从细到粗；全是整数 MHz，刻度值不会是 12.5 这种零碎数字 */
private val FREQ_STEPS = intArrayOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000)

/**
 * 频率轴的 (下限, 上限, 步长)。
 *
 * 早先是固定 50MHz 一步，GPU 只在很小一段频率上浮动时（整段记录都挤在
 * 210–230MHz 这种），整根轴上就只剩两个刻度值，中间一大段空着没法读数。现在改成
 * 按本次记录的频率跨度挑步长：跨度大就用粗步长、跨度小就细到 5/10MHz，**保证轴上
 * 不少于 [FREQ_MIN_TICKS] 个刻度**（含上下两端），同时不超过 [FREQ_MAX_TICKS] 条。
 *
 * 两个不变的原则：
 * - 区间紧贴本次记录的 min/max，**不从 0 起**——低频段的起伏正是这张图要看的东西，
 *   从 0 画上来会把那段起伏压成一条直线；
 * - 步长必须是整数 MHz：刻度标签直接取整显示，不能出现 12.5 这种数。
 *
 * @param minFreq 本次记录的最低频率 @param peakFreq 本次记录的最高频率
 */
private fun freqAxis(minFreq: Float, peakFreq: Float): Triple<Float, Float, Float> {
    // 上下各留 2MHz 余量，免得最高/最低那个点正好贴在轴的边框上
    val lo = Math.floor((minFreq - 2.0)).toInt().coerceAtLeast(0)
    val hi = Math.ceil((peakFreq * 1.05 + 2.0)).toInt().coerceAtLeast(lo + 1)
    // 从细往粗试，取第一个「刻度条数落在 5..12 之间」的步长。挑**最细**的可用步长——
    // 同样看得清的前提下，刻度越密越好读，而且步长越细，「下限对齐到步长整数倍」
    // 让掉的头部留白越小，曲线越贴住真实区间。
    for (step in FREQ_STEPS) {
        val axisMin = lo / step * step
        val axisMax = (hi + step - 1) / step * step
        val count = axisMax / step - axisMin / step + 1
        if (count in FREQ_MIN_TICKS..FREQ_MAX_TICKS) {
            return Triple(axisMin.toFloat(), axisMax.toFloat(), step.toFloat())
        }
    }
    // 跨度不在预期里：保底给整数量程，至少能画出来
    return Triple(lo.toFloat(), hi.toFloat(), 1f)
}

// ======================= 数据装载 =======================

private fun loadFrameList(context: Context, filterPkg: String?): FrameListData {
    val store = FrameRecordStore.get(context)
    val sampler = FrameSampler.get(context)
    // 孤儿记录（进程被杀没来得及收尾的）在这里顺手收掉：用户点开页面就该看到干净列表。
    // 正在记的那条必须跳过——不跳的话页面一刷新就把进行中的记录收了口
    store.finalizeOrphans(sampler.currentRecordId())
    if (sampler.isRecording()) {
        val id = sampler.currentRecordId()
        // 进行中的记录汇总行平时不更新，这里刷一遍列表上的平均帧率才有实时数字
        if (id > 0) store.refreshAggregates(id)
    }
    return FrameListData(
        records = store.queryRecords(FRAME_LIST_LIMIT, filterPkg),
        apps = store.queryAppEntries(),
        sampleCount = store.countAllSamples()
    )
}

private fun loadFrameDetail(context: Context, recordId: Long): FrameDetailData {
    val store = FrameRecordStore.get(context)
    val record = store.queryRecordById(recordId)
    if (record == null) {
        return FrameDetailData(
            FrameRecordStore.Record().apply {
                id = recordId
                appLabel = "记录不存在（可能已删除）"
            },
            emptyList()
        )
    }
    if (record.ongoing) {
        // 进行中：汇总行跟着刷，头部数字才是实时的
        store.refreshAggregates(recordId)
        record.sampleCount = store.countSamples(recordId)
    }
    // 进行中的记录点数少，直接全量读；完成的抽稀，几小时的记录才不会一次读上万行
    val samples = if (record.ongoing) {
        store.querySamples(recordId)
    } else {
        store.querySamplesDownsampled(recordId, FRAME_CURVE_MAX_POINTS)
    }
    return FrameDetailData(record, samples)
}
