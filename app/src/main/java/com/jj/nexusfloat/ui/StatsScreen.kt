package com.jj.nexusfloat.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jj.nexusfloat.stats.StatsRepository
import com.jj.nexusfloat.stats.StatsStore
import com.jj.nexusfloat.ui.theme.CardStroke
import com.jj.nexusfloat.ui.theme.MdThemeOnSurface
import com.jj.nexusfloat.ui.theme.MdThemeOnSurfaceVariant
import com.jj.nexusfloat.ui.theme.MdThemePrimary
import com.jj.nexusfloat.ui.theme.ToggleOffContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 配色：统计页自己的几条曲线颜色。
// 没放进 Theme.kt：那套是「界面结构色」（卡片、描边、开关），跟主题一一对应；
// 曲线颜色是数据语义色（电量 / 功率 / 温度），浅色深色下都该是同一个意思，
// 跟着主题换反而会让用户重新认一遍图例。
// 对详情页（StatsDetail.kt）可见：两边画的都是同一批物理量，颜色必须一一对应，
// 用户在总览页认下的「橙色=功率」，点进详情页不该变成别的意思。
// ============================================================

internal val ChargeColor = Color(0xFF3B82F6)
internal val BatteryColor = Color(0xFF10B981)
internal val PowerColor = Color(0xFFF59E0B)
internal val TempColor = Color(0xFFEF4444)
internal val ScreenOnColor = Color(0xFF6366F1)
internal val StandbyColor = Color(0xFF94A3B8)

/** 统计页数据多久自刷一次。页面上有正在进行的充电/放电，太慢会显得不动 */
internal const val REFRESH_MS = 5000L

/**
 * 「统计」页。
 *
 * 数据全部来自本机 SQLite（见 stats 包），这一层只负责装配和展示：
 * 一次 {@link StatsRepository.load} 拿一屏数据，然后再按秒级刷新重装。
 * 没做成「每张卡各自查一段」是因为那样每次重组就是好几轮 SQLite + 一轮
 * UsageStats 查询，滚动时会明显卡。
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    statsEnabled: Boolean,
    onStatsEnabledChange: (Boolean) -> Unit,
    intervalSec: Int,
    onIntervalChange: (Int) -> Unit,
    notificationEnabled: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    screenOnOnly: Boolean,
    onScreenOnOnlyChange: (Boolean) -> Unit,
    dualCellEnabled: Boolean,
    onDualCellChange: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onClearData: () -> Unit,
    /**
     * 请求把整页滚回顶部。
     *
     * 详情页是在本页内「就地替换内容」的二级视图（没引导航库，见文件末尾的说明），
     * 而滚动状态由外面的 MainScreen 持有，所以进入/退出详情得让外面帮忙滚一下——
     * 否则从历史列表第 15 条点进去，会落在详情页中间，像内容错位。
     */
    onRequestScrollTop: () -> Unit = {}
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 二级视图：0 表示没打开；否则是被点开的那条记录的 id。
    // 存 id 而不是整条记录，是为了让详情页每次都回库重取一遍（理由见
    // StatsStore#querySessionById 的注释：列表里那份已经被双电芯倍率缩放过）。
    var detailSessionId by remember { mutableStateOf(0L) }
    var detailPeriodId by remember { mutableStateOf(0L) }
    val detailOpen = detailSessionId > 0L || detailPeriodId > 0L

    /**
     * 当前看的是哪个模块：0=充电统计，1=使用统计。
     *
     * 用 rememberSaveable 存，退出统计页再回来（或者旋屏/进程重建）还停在原来那一栏，
     * 不会每次都弹回充电统计。切换时调 onRequestScrollTop() 把整页滚回顶部——
     * 两个模块都是长列表，切过去还停在上一栏的滚动位置会像内容错位。
     */
    var section by rememberSaveable { mutableStateOf(0) }

    val closeDetail = {
        detailSessionId = 0L
        detailPeriodId = 0L
        // 详情页期间总览是停更的，退出来得补一次，否则列表还停在进去之前那一刻
        refreshTick++
        onRequestScrollTop()
    }
    BackHandler(enabled = detailOpen) { closeDetail() }

    // 定时重装数据。页面切走时 LaunchedEffect 会取消，不会在后台一直刷。
    // 详情页打开时也停掉：详情页自己会拉数据，两边同时跑就是两轮 SQLite +
    // 两轮 UsageStats 查询，纯浪费电。detailOpen 一变这个 effect 就重启，
    // 所以循环里读的是当次捕获的常量，不需要额外同步
    LaunchedEffect(detailOpen) {
        while (!detailOpen) {
            delay(REFRESH_MS)
            refreshTick++
        }
    }

    val dashboard by produceState<StatsRepository.Dashboard?>(
        initialValue = null,
        refreshTick
    ) {
        value = withContext(Dispatchers.IO) { StatsRepository.load(context) }
    }

    /**
     * 现在是不是插着电（「仅亮屏时记录」的临时状态要用）。
     *
     * 直接读电池的粘性广播，不复用采样数据：采样点默认 15 秒一个，统计整个关掉时
     * 更是一个都没有，而设置卡上那个开关要在插上电的瞬间就显示成「已自动关闭」。
     * 粘性广播是系统缓存的一份，读它不耗电、也不用注册接收器。
     *
     * 跟着 refreshTick 重读：统计页本来就在 5 秒刷一次，插拔电源最迟 5 秒内反映到
     * 开关上；页面切走时 LaunchedEffect 停了，这里也不会再读。
     */
    val chargingNow = remember(refreshTick) { readChargingNow(context) }

    // 删单条记录：删除是几百行的 DELETE，不能放在主线程；删完把 refreshTick 往前
    // 推一格，produceState 会重新装一屏数据，列表里那条立刻消失。
    // 进行中的记录删掉后采样线程下一拍会重建，所以文案用「重新开始」而不是「已删除」
    val deleteSession: (StatsStore.Session) -> Unit = { s ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { StatsRepository.deleteSession(context, s) }
            Toast.makeText(
                context,
                if (!ok) "删除失败，请重试"
                else if (s.ongoing) "已重置，这一轮从此刻重新记录"
                else "已删除这条充电记录",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                refreshTick++
            }
        }
    }
    val deletePeriod: (StatsStore.Period) -> Unit = { p ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { StatsRepository.deletePeriod(context, p) }
            Toast.makeText(
                context,
                if (!ok) "删除失败，请重试"
                else if (p.ongoing) "已重置，这一轮从此刻重新记录"
                else "已删除这轮使用周期",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                refreshTick++
            }
        }
    }

    val data = dashboard
    if (data == null) {
        SectionCard(title = "充电与使用统计") {
            Text(
                text = "正在读取本机统计…",
                fontSize = 12.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(4.dp)
            )
        }
        return
    }

    // ---- 二级视图：单条记录的详情 ----
    // 用「同页替换内容」而不是新开 Activity：这一页本来就是个只读看板，
    // 为一次查看引入导航栈、返回栈状态、深链处理都不划算；BackHandler 补上
    // 系统返回键的行为，用起来跟真的跳页一样。
    if (detailSessionId > 0L) {
        // 从已经装好的列表里取这一条，只是想拿它是不是「进行中」——决定详情页
        // 要不要跟着秒级刷新。数字一律由详情页自己回库重取
        val row = data.sessionHistory.firstOrNull { it.id == detailSessionId }
        SessionDetailScreen(
            sessionId = detailSessionId,
            ongoing = row?.ongoing ?: false,
            onBack = { closeDetail() },
            onDelete = {
                detailSessionId = 0L
                onRequestScrollTop()
                if (row != null) {
                    deleteSession(row)
                }
            }
        )
        return
    }
    if (detailPeriodId > 0L) {
        val row = data.periodHistory.firstOrNull { it.id == detailPeriodId }
        PeriodDetailScreen(
            periodId = detailPeriodId,
            ongoing = row?.ongoing ?: false,
            usagePermission = data.usagePermission,
            onOpenUsageAccess = onOpenUsageAccess,
            onBack = { closeDetail() },
            onDelete = {
                detailPeriodId = 0L
                onRequestScrollTop()
                if (row != null) {
                    deletePeriod(row)
                }
            }
        )
        return
    }

    val now = System.currentTimeMillis()
    val capacity = data.capacityMah

    // 「当前状态」两个模块都要看（电池是充电和放电共用的那一份实时读数），
    // 所以留在最上面，不进分段。
    CurrentStatusCard(data)

    // 分段控件：充电统计 / 使用统计。
    //
    // 拆成两个模块的理由：这一页原来把「充电」和「使用」十几张卡顺序铺下来，
    // 想找充电历史得先划过整个使用统计；两件事本来也互不相干（一个只在插电时发生、
    // 一个只在拔电后发生）。分段之后一次只呈现一件事，符合页面的语义划分。
    // 设置项也跟着分：开统计/采样间隔/通知/双电芯/清空是**采样器**这套公共设施
    // （采样器在充电场景下才持锁常跑），放「充电统计」；「仅亮屏时记录」按定义只
    // 影响不充电时的采样、「使用情况访问」只服务应用榜，放「使用统计」。
    Spacer(modifier = Modifier.height(12.dp))
    SegmentedSelector(
        options = listOf("充电统计", "使用统计"),
        selectedIndex = section,
        onSelect = { picked ->
            if (picked != section) {
                section = picked
                onRequestScrollTop()
            }
        }
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (section == 0) {
        // ================= 充电统计 =================
        // 本次充电卡（电量—功率曲线 + 电池温度曲线）
        val shownSession = data.currentSession ?: data.sessionHistory.firstOrNull()
        ChargeCard(
            session = shownSession,
            curve = data.chargeCurve,
            capacityMah = capacity,
            now = now
        )

        // 充电记录历史（可点进详情、可删/重置）
        if (data.sessionHistory.isNotEmpty()) {
            HistoryCard(
                sessions = data.sessionHistory,
                now = now,
                onOpen = { s ->
                    detailSessionId = s.id
                    onRequestScrollTop()
                },
                onDelete = deleteSession
            )
        }

        // 累计充电（原「累计统计」里属于充电的那一半）
        ChargeTotalsCard(data.totals)

        // 充电统计设置（开统计 / 采样间隔 / 通知 / 双电芯 / 电流诊断 / 清空）
        ChargeSettingsCard(
            statsEnabled = statsEnabled,
            onStatsEnabledChange = onStatsEnabledChange,
            intervalSec = intervalSec,
            onIntervalChange = onIntervalChange,
            notificationEnabled = notificationEnabled,
            onNotificationChange = onNotificationChange,
            dualCellEnabled = dualCellEnabled,
            onDualCellChange = onDualCellChange,
            sampleCount = data.sampleCount,
            onClearData = onClearData,
            currentSource = data.currentSource
        )
    } else {
        // ================= 使用统计 =================
        // 本次使用卡（使用过程曲线 + 应用图标塔）
        val shownPeriod = data.currentPeriod ?: data.periodHistory.firstOrNull()
        UsageCard(
            period = shownPeriod,
            curve = data.dischargeCurve,
            capacityMah = capacity,
            usagePermission = data.usagePermission,
            onOpenUsageAccess = onOpenUsageAccess,
            now = now,
            fgBuckets = data.fgBuckets,
            towerBucketCount = data.towerBuckets
        )

        // 应用使用情况榜
        AppUsageCard(
            apps = data.apps,
            drainBaseMah = data.appDrainBaseMah,
            usagePermission = data.usagePermission,
            hasPeriod = shownPeriod != null,
            onOpenUsageAccess = onOpenUsageAccess
        )

        // 使用周期历史
        if (data.periodHistory.isNotEmpty()) {
            PeriodHistoryCard(
                periods = data.periodHistory,
                now = now,
                onOpen = { p ->
                    detailPeriodId = p.id
                    onRequestScrollTop()
                },
                onDelete = deletePeriod
            )
        }

        // 累计使用（原「累计统计」里属于使用的那一半）
        UsageTotalsCard(data.totals, capacity)

        // 使用统计设置（仅亮屏时记录 / 使用情况访问）
        UsageSettingsCard(
            screenOnOnly = screenOnOnly,
            onScreenOnOnlyChange = onScreenOnOnlyChange,
            // 充电中这个开关会自动显示成「关」并置灰，拔电后恢复用户原本的选择。
            // 恢复不需要额外做什么——用户存下来的值从头到尾没被改写
            charging = chargingNow,
            usagePermission = data.usagePermission,
            onOpenUsageAccess = onOpenUsageAccess
        )
    }

    // 说明文案留在页尾（两个模块都能看到）。
    // 它讲的是「数据怎么存的、保留多久、能不能删」——是整页级别的约定，不属于
    // 充电或使用任何一边，所以不跟着分段走，避免在两处重复一遍同样的字。
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "统计数据永久存在本机，不联网、不上传。采样明细保留 45 天后自动清理，" +
                "充电记录与使用周期永久保留；两者都可以在上面的列表里手动删除。",
        fontSize = 10.sp,
        color = MdThemeOnSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

// ======================= 当前状态 =======================

@Composable
private fun CurrentStatusCard(data: StatsRepository.Dashboard) {
    val latest = data.latest
    SectionCard(title = "当前状态") {
        if (latest == null) {
            EmptyHint("还没有采到数据。统计功能刚打开时，等 15 秒左右会出现第一个采样点。")
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${latest.level}",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = if (latest.charging()) ChargeColor else MdThemeOnSurface
            )
            Text(
                text = "%",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            // 「使用中 / 充电中」这行原来下面还挂着一行小字（功率 · 电压 · 温度）。
            // 三个读数现在都在下面的卡片里各有格子，这里再写一遍只会把主数字挤小，
            // 所以只留状态本身
            Text(
                text = if (latest.charging()) "充电中" else "使用中",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (latest.charging()) ChargeColor else MdThemeOnSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        // 一行四个格子，每格只剩七十来 dp，所以用 compact 把内边距和字号收一档
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricTile(
                title = "电流",
                value = if (latest.currentAbsMa() > 0) {
                    "${latest.currentAbsMa()} mA"
                } else {
                    "-- mA"
                },
                compact = true,
                modifier = Modifier.weight(1f)
            )
            // 电压原来缩在「充电中/使用中」下面那行小字里，它跟电流、功率是同一组
            // 电学量，单独给一格比挤在状态行里清楚
            MetricTile(
                title = "电压",
                value = if (latest.voltageMv > 0) {
                    String.format(Locale.US, "%.2f V", latest.voltageMv / 1000f)
                } else {
                    "-- V"
                },
                compact = true,
                modifier = Modifier.weight(1f)
            )
            // 原来是「屏幕 亮/灭」。用户打开这个页面本身就意味着屏幕是亮的，
            // 这一格的信息量恒为「亮」，纯属占地方，换成功率。
            MetricTile(
                title = "功率",
                value = if (Math.abs(latest.powerW) > 0.01f) {
                    String.format(Locale.US, "%.1f W", Math.abs(latest.powerW))
                } else {
                    "-- W"
                },
                compact = true,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "电池温度",
                value = String.format(Locale.US, "%.1f°C", latest.tempC),
                compact = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = lastSampleHint(latest.ts),
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private fun lastSampleHint(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚采样"
        diff < 3_600_000 -> "最近采样 ${diff / 60_000} 分钟前"
        else -> "最近采样 ${fmtDateTime(ts)}"
    }
}

// ======================= 充电 =======================

@Composable
private fun ChargeCard(
    session: StatsStore.Session?,
    curve: List<StatsStore.Sample>,
    capacityMah: Float,
    now: Long
) {
    SectionCard(title = if (session?.ongoing == true) "本次充电（进行中）" else "最近一次充电") {
        if (session == null) {
            EmptyHint("还没有充电记录。插上充电器之后这里会开始记录曲线。")
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (session.ongoing) "充电中" else "已完成",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (session.ongoing) ChargeColor else MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${fmtDateTime(session.startTs)} 起 · ${fmtDuration(session.durationMs(now))}",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = if (session.ongoing) "已充入" else "充入电量",
                value = if (session.chargedMah > 0f) {
                    "${session.chargedMah.toInt()} mAh"
                } else {
                    "-- mAh"
                },
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "电量变化",
                value = "${session.startLevel}% → ${session.endLevel}%",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "平均功率",
                value = if (session.avgPowerW > 0f) {
                    String.format(Locale.US, "%.1f W", session.avgPowerW)
                } else "--",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "峰值功率",
                value = if (session.peakPowerW > 0f) {
                    String.format(Locale.US, "%.1f W", session.peakPowerW)
                } else "--",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "最高温度",
                value = if (session.peakTempC > 0f) {
                    String.format(Locale.US, "%.1f°C", session.peakTempC)
                } else "--",
                modifier = Modifier.weight(1f)
            )
        }

        if (capacityMah > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "按本次充入电量反推，电池容量约 ${capacityMah.toInt()} mAh（粗略估算，仅供参考）",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "电量与充电功率曲线",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        ChargeCurves(curve)
    }
}

/** 充电曲线的横轴刻度间隔：10 分钟 */
internal const val X_TICK_CHARGE_MS = 10L * 60_000L
/** 使用曲线的横轴刻度间隔：6 小时 */
internal const val X_TICK_USAGE_MS = 6L * 3600_000L

/**
 * 充电曲线：电量走左轴（0–100%），功率走右轴。
 *
 * 两条线共用一个横轴但量纲完全不同，所以必须分轴。电量放左轴是因为它是「主信息」，
 * 用户看充电记录第一眼想知道的是「充到多少了」；功率是副信息，放右轴不抢注意力。
 */
@Composable
internal fun ChargeCurves(curve: List<StatsStore.Sample>) {
    if (curve.size < 2) {
        EmptyHint("充电刚开始，采样点还不够画曲线（至少需要两个点）。")
        return
    }
    val levels = curve.map { it.level.toFloat() }
    // 功率序列带「未知」语义：power_known=0 的点（读不到 / 被区间拒掉 / 沿用值）
    // 不画成 0，用相邻有效值桥接，否则曲线会呈现「多数点贴 0、偶尔窜尖峰」的方波
    val powers = chargePowerSeries(curve)

    // 功率轴上限取实际峰值的 1.15 倍并向上取整到 10W，免得曲线贴着顶边；
    // 右轴刻度每 10W 一条，上限不是 10 的整数倍时最上面那条刻度对不上峰值。
    // 峰值只取桥接后仍有值的点（= 有效点），未知点不参与
    val peak = powers.filterNotNull().maxOrNull() ?: 0f
    val powerMax = maxOf(10f, Math.ceil((peak * 1.15f / 10f).toDouble()).toFloat() * 10f)

    val times = curve.map { it.ts }
    LineChart(
        lines = listOf(
            ChartLine(label = "电量 (%)", color = BatteryColor, points = levels),
            ChartLine(
                label = "功率 (W)",
                color = PowerColor,
                points = powers,
                useRightAxis = true
            )
        ),
        leftMin = 0f,
        leftMax = 100f,
        rightMin = 0f,
        rightMax = powerMax,
        showRightAxis = true,
        leftFormat = { "${it.toInt()}%" },
        rightFormat = { if (it >= 10f) it.toInt().toString() else String.format(Locale.US, "%.1f", it) },
        leftStep = 20f,
        rightStep = 10f,
        xTicks = axisTicks(times, X_TICK_CHARGE_MS),
        xValues = times
    )

    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = "电池温度",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MdThemeOnSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    val temps = curve.map { it.tempC }
    val tempMin = (temps.minOrNull() ?: 20f).let { Math.floor((it - 2f).toDouble()).toFloat() }
    val tempMax = (temps.maxOrNull() ?: 40f).let { Math.ceil((it + 2f).toDouble()).toFloat() }
    LineChart(
        lines = listOf(ChartLine(label = "温度 (°C)", color = TempColor, points = temps)),
        leftMin = tempMin,
        leftMax = if (tempMax - tempMin < 2f) tempMin + 2f else tempMax,
        leftFormat = { "${it.toInt()}°" },
        xTicks = axisTicks(times, X_TICK_CHARGE_MS),
        xValues = times
    )
}

/**
 * 充电功率曲线的取值序列（带「未知」语义）。
 *
 * `power_known=0` 的采样点表示这一拍的电流/功率没能真读到（读不到、被合法区间
 * 拒掉、或者充电中沿用了上次的有效值）。这些点**不能**当成 0 画出来——那正是
 * 用户看到的「橙线呈方波、多数点贴在 0 上」。规则：
 *
 * - 每个未知点用**前一个有效点**的值桥接，折线保持连续，既不留空洞也不画假 0；
 * - 出现在第一个有效点之前的未知点没有可桥接的对象，返回 null，让折线从第一个
 *   有效点开始（宁可起点稍晚，也不在开头画一段假的 0）；
 * - 整段一个有效点都没有（这一段全读不到）时**维持原样**，直接把落库值取绝对值
 *   画出来，免得曲线整条消失、看着像页面坏了。
 *
 * 返回值是 `List<Float?>`，null 会被 LineChart 断开——这是图表本来就支持的能力。
 */
internal fun chargePowerSeries(curve: List<StatsStore.Sample>): List<Float?> {
    if (curve.none { it.powerKnown }) {
        return curve.map { Math.abs(it.powerW) }
    }
    val out = ArrayList<Float?>(curve.size)
    var last: Float? = null
    for (s in curve) {
        if (s.powerKnown) {
            val v = Math.abs(s.powerW)
            last = v
            out.add(v)
        } else {
            out.add(last)
        }
    }
    return out
}

// ======================= 使用周期 =======================

@Composable
private fun UsageCard(
    period: StatsStore.Period?,
    curve: List<StatsStore.Sample>,
    capacityMah: Float,
    usagePermission: Boolean,
    onOpenUsageAccess: () -> Unit,
    now: Long,
    fgBuckets: List<StatsStore.FgBucket> = emptyList(),
    towerBucketCount: Int = 0
) {
    val context = LocalContext.current
    // 图标只在包名集合变化时才重新解码：分桶数据每 5 秒就会因为时长微调变成一个新
    // 对象，直接拿它当 key 的话会每 5 秒重新解码一轮应用图标
    val towerPkgs = remember(fgBuckets) { fgBuckets.map { it.pkg }.distinct() }
    val towerIcons = remember(towerPkgs) { appIcons(context, towerPkgs) }
    val tower = remember(fgBuckets, towerBucketCount, towerIcons) {
        if (fgBuckets.isEmpty() || towerBucketCount <= 0) {
            null
        } else {
            AppTower(buckets = bucketize(fgBuckets, towerBucketCount), icons = towerIcons)
        }
    }
    SectionCard(title = if (period?.ongoing == true) "本次使用（进行中）" else "最近一次使用周期") {
        if (period == null) {
            EmptyHint("还没有使用周期记录。拔掉充电器之后这里会开始统计这一轮的使用情况。")
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (period.ongoing) "使用中" else "已结束",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (period.ongoing) ScreenOnColor else MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${fmtDateTime(period.startTs)} 起 · 共 ${fmtDuration(period.durationMs(now))}",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "亮屏时间",
                value = fmtDuration(period.screenOnMs),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "待机时间",
                value = fmtDuration(period.standbyMs(now)),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "电量消耗",
                value = "${period.levelDrop()}%",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "耗电估算",
                value = estimateDrainText(period, capacityMah),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "平均功率",
                value = if (period.avgPowerW > 0f) {
                    String.format(Locale.US, "%.2f W", period.avgPowerW)
                } else "--",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        SegmentedBar(
            segments = listOf(
                period.screenOnMs.toFloat() to ScreenOnColor,
                period.standbyMs(now).toFloat() to StandbyColor
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(ScreenOnColor, "亮屏 ${fmtDuration(period.screenOnMs)}")
            Spacer(modifier = Modifier.width(14.dp))
            LegendDot(StandbyColor, "待机 ${fmtDuration(period.standbyMs(now))}")
        }

        if (!usagePermission) {
            Spacer(modifier = Modifier.height(10.dp))
            PermissionHint(onOpenUsageAccess)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "使用过程",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        if (curve.size < 2) {
            EmptyHint("采样点还不够画曲线。")
        } else {
            val times = curve.map { it.ts }
            LineChart(
                lines = listOf(
                    ChartLine(
                        label = "电量 (%)",
                        color = ScreenOnColor,
                        points = curve.map { it.level.toFloat() }
                    )
                ),
                leftMin = 0f,
                leftMax = 100f,
                leftFormat = { "${it.toInt()}%" },
                leftStep = 20f,
                xTicks = axisTicks(times, X_TICK_USAGE_MS),
                xValues = times,
                // 图标塔并进同一张图：塔立在绘图区底线上，电量曲线压在塔上面
                appTower = tower
            )
            if (tower != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "底下的每一格图标是一个应用，柱子高低＝那一段的前台使用时长，" +
                            "图标自下而上按时长排。曲线是那一段剩余电量。",
                    fontSize = 10.sp,
                    color = MdThemeOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * 耗电估算。
 *
 * 两套算法各写各的：
 * - 电流积分是「这段时间电池实际放出去多少电」，物理上最直接，但息屏期间采样点稀疏，
 *   会偏小；
 * - 电量差 × 电池容量是「电池百分比掉了多少所对应的电量」，跟采样密度无关，但容量
 *   本身是上一次充电反推的，误差会传导过来。
 * 两个都给出来，让用户自己能看出量级对不对，比只给一个假装精确的数字诚实。
 */
internal fun estimateDrainText(period: StatsStore.Period, capacityMah: Float): String {
    val byLevel = if (capacityMah > 0f) period.levelDrop() / 100f * capacityMah else 0f
    return when {
        byLevel > 0f -> "${byLevel.toInt()} mAh"
        period.drainMah > 0f -> "${period.drainMah.toInt()} mAh"
        else -> "--"
    }
}

// ======================= 应用榜 =======================

private enum class AppSort(val label: String) {
    DRAIN("按耗电"),
    TIME("按时长")
}

@Composable
internal fun AppUsageCard(
    apps: List<StatsStore.AppUsage>,
    drainBaseMah: Float,
    usagePermission: Boolean,
    hasPeriod: Boolean,
    onOpenUsageAccess: () -> Unit
) {
    val context = LocalContext.current
    var sort by remember { mutableStateOf(AppSort.DRAIN) }
    val names = remember(apps) { appLabels(context, apps.map { it.pkg }) }
    val icons = remember(apps) { appIcons(context, apps.map { it.pkg }) }

    SectionCard(title = "应用使用情况") {
        if (!hasPeriod || apps.isEmpty()) {
            EmptyHint(
                if (!usagePermission) {
                    "还没有应用数据。可以点下面的按钮授予「使用情况访问」，统计会更准。"
                } else {
                    "这一轮还没有记录到前台应用。只有当屏幕亮着、并且确实有应用在前台时才会归因。"
                }
            )
            if (!usagePermission) {
                Spacer(modifier = Modifier.height(10.dp))
                PermissionHint(onOpenUsageAccess)
            }
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedSelector(
                options = AppSort.entries.map { it.label },
                selectedIndex = sort.ordinal,
                onSelect = { sort = AppSort.entries[it] },
                modifier = Modifier.weight(1f),
                compact = true
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${apps.size} 个应用",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }

        val sorted = remember(apps, sort) {
            if (sort == AppSort.DRAIN) {
                apps.sortedByDescending { it.drainMah }
            } else {
                apps.sortedByDescending { it.foregroundMs }
            }
        }
        val maxDrain = sorted.maxOfOrNull { it.drainMah } ?: 0f
        val maxTime = sorted.maxOfOrNull { it.foregroundMs } ?: 0L

        Spacer(modifier = Modifier.height(12.dp))
        sorted.forEachIndexed { index, app ->
            val fraction = if (sort == AppSort.DRAIN) {
                if (maxDrain > 0f) app.drainMah / maxDrain else 0.02f
            } else {
                if (maxTime > 0L) app.foregroundMs.toFloat() / maxTime else 0.02f
            }
            AppRow(
                name = names[app.pkg] ?: app.pkg,
                icon = icons[app.pkg],
                timeText = fmtDuration(app.foregroundMs),
                // 百分比是「占整机耗电」的份额，不是「占各应用之和」——分母是
                // 整机的话各应用加起来不足 100%，差额就是系统与后台，跟系统
                // 电池页一个口径
                percentText = if (drainBaseMah > 0f && app.drainMah > 0f) {
                    String.format(Locale.US, "%.1f%%", app.drainMah / drainBaseMah * 100f)
                } else {
                    "—"
                },
                drainText = if (app.drainMah > 0f) {
                    String.format(Locale.US, "%.0f mAh", app.drainMah)
                } else {
                    "—"
                },
                powerText = if (app.avgPowerW > 0.005f) {
                    String.format(Locale.US, "%.2f W", app.avgPowerW)
                } else {
                    "—"
                },
                fraction = fraction,
                color = if (sort == AppSort.DRAIN) PowerColor else ScreenOnColor
            )
            if (index != sorted.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "百分比＝该应用前台耗电 ÷ 本轮整机耗电；平均功率＝该应用在前台期间的平均放电功率。" +
                    "后台耗电（网络、定位、推送）归不到单个应用头上，所以各应用百分比加起来通常不到 100%。",
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun AppRow(
    name: String,
    icon: ImageBitmap?,
    timeText: String,
    percentText: String,
    drainText: String,
    powerText: String,
    fraction: Float,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ToggleOffContainer),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = name.take(1).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MdThemeOnSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = percentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText,
                    fontSize = 10.sp,
                    color = MdThemeOnSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = powerText,
                    fontSize = 10.sp,
                    color = MdThemeOnSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = drainText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MdThemeOnSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            BarOnly(fraction = fraction, color = color)
        }
    }
}

// ======================= 汇总 =======================

/**
 * 「累计充电」卡。原「累计统计」里属于充电的那一半，拆到充电模块下。
 */
@Composable
private fun ChargeTotalsCard(totals: StatsStore.Totals) {
    SectionCard(title = "累计充电") {
        if (totals.sessionCount == 0) {
            EmptyHint("还没有充电记录可以汇总。用上一两天再回来看。")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "充电次数",
                value = "${totals.sessionCount} 次",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "累计充入",
                value = if (totals.totalChargedMah > 0f) {
                    "${totals.totalChargedMah.toInt()} mAh"
                } else "--",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "充电总时长",
                value = fmtDuration(totals.totalChargeMs),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "历史最高功率",
                value = if (totals.peakPowerW > 0f) {
                    String.format(Locale.US, "%.1f W", totals.peakPowerW)
                } else "--",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 「累计使用」卡。原「累计统计」里属于使用的那一半。
 *
 * 容量只用于「平均每小时耗电」这一格的分母（把累计放电换算成百分比），
 * 反推不出容量时由 capacityOrFallback 给个常见值兜底。
 */
@Composable
private fun UsageTotalsCard(totals: StatsStore.Totals, capacityMah: Float) {
    SectionCard(title = "累计使用") {
        if (totals.periodCount == 0) {
            EmptyHint("还没有使用周期可以汇总。用上一两天再回来看。")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "使用周期",
                value = "${totals.periodCount} 次",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "累计亮屏",
                value = fmtDuration(totals.totalScreenOnMs),
                modifier = Modifier.weight(1f)
            )
        }
        if (totals.totalDrainMah > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    title = "累计放电",
                    value = "${totals.totalDrainMah.toInt()} mAh",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "平均每小时耗电",
                    value = if (totals.totalPeriodMs > 60_000L) {
                        String.format(
                            Locale.US, "%.1f %%",
                            totals.totalDrainMah / capacityOrFallback(capacityMah) * 100f
                                * 3_600_000f / totals.totalPeriodMs
                        )
                    } else {
                        "--"
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun capacityOrFallback(capacityMah: Float): Float {
    if (capacityMah > 0f) {
        return capacityMah
    }
    // 反推不出容量时给个常见值当分母，只为了算出一个量级对的百分比
    return 5000f
}

// ======================= 历史 =======================

/** 历史列表最多展示多少条。超过这个数上面还有汇总，翻太多也没人看 */
private const val HISTORY_SHOWN = 20

@Composable
private fun HistoryCard(
    sessions: List<StatsStore.Session>,
    now: Long,
    onOpen: (StatsStore.Session) -> Unit,
    onDelete: (StatsStore.Session) -> Unit
) {
    SectionCard(title = "充电历史") {
        sessions.take(HISTORY_SHOWN).forEachIndexed { index, s ->
            HistoryRow(s, now, onOpen = { onOpen(s) }, onDelete = { onDelete(s) })
            if (index != sessions.take(HISTORY_SHOWN).lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "充电记录永久保留，采样明细保留 45 天。共 ${sessions.size} 条，最多展示最近 $HISTORY_SHOWN 条。" +
                    "点一条可以进去看那一次的电量—功率曲线、温度曲线和当时的读数。" +
                    "点「删除」再点「确认」删掉这条记录及对应区间的采样点；进行中的那条显示为" +
                    "「重置」，删掉后会从此刻重新开始记录。",
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun HistoryRow(
    s: StatsStore.Session,
    now: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var confirm by remember(s.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ToggleOffContainer)
            // 整行可点进详情；右边那个删除小按钮自己也有 clickable，
            // 内层的会先吃掉点击，所以在删除按钮上不会误跳转
            .clickable { onOpen() }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fmtDateTime(s.startTs),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MdThemeOnSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${s.startLevel}% → ${s.endLevel}% · ${fmtDuration(s.durationMs(now))}" +
                        if (s.ongoing) " · 进行中" else "",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (s.chargedMah > 0f) "${s.chargedMah.toInt()} mAh" else "--",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (s.peakPowerW > 0f) {
                    String.format(Locale.US, "峰值 %.1fW", s.peakPowerW)
                } else {
                    ""
                },
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        // 进行中的记录也能删：删完采样线程下一拍会重新建一条，效果就是
        // 「从此刻重新开始记这一轮」，所以按钮文案写成「重置」而不是「删除」
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "›", fontSize = 16.sp, color = MdThemeOnSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        DeleteChip(armed = confirm, label = if (s.ongoing) "重置" else "删除") {
            if (confirm) {
                onDelete()
            } else {
                confirm = true
            }
        }
    }
}

@Composable
private fun PeriodHistoryCard(
    periods: List<StatsStore.Period>,
    now: Long,
    onOpen: (StatsStore.Period) -> Unit,
    onDelete: (StatsStore.Period) -> Unit
) {
    SectionCard(title = "使用周期历史") {
        periods.take(HISTORY_SHOWN).forEachIndexed { index, p ->
            PeriodHistoryRow(p, now, onOpen = { onOpen(p) }, onDelete = { onDelete(p) })
            if (index != periods.take(HISTORY_SHOWN).lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "一轮使用周期＝拔掉充电器到下次插上充电器。共 ${periods.size} 条，最多展示最近 " +
                    "$HISTORY_SHOWN 条。点一条可以进去看那一轮的使用过程（电量曲线叠应用图标塔）" +
                    "和应用使用情况；删除会连同这一轮的采样点一起清掉，进行中的那条显示为「重置」。",
            fontSize = 10.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun PeriodHistoryRow(
    p: StatsStore.Period,
    now: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var confirm by remember(p.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ToggleOffContainer)
            .clickable { onOpen() }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fmtDateTime(p.startTs),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MdThemeOnSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${p.startLevel}% → ${p.endLevel}% · ${fmtDuration(p.durationMs(now))}" +
                        " · 亮屏 ${fmtDuration(p.screenOnMs)}" +
                        if (p.ongoing) " · 进行中" else "",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (p.drainMah > 0f) "${p.drainMah.toInt()} mAh" else "--",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (p.avgPowerW > 0f) {
                    String.format(Locale.US, "均 %.2fW", p.avgPowerW)
                } else {
                    ""
                },
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "›", fontSize = 16.sp, color = MdThemeOnSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        DeleteChip(armed = confirm, label = if (p.ongoing) "重置" else "删除") {
            if (confirm) {
                onDelete()
            } else {
                confirm = true
            }
        }
    }
}

/**
 * 删除按钮。
 *
 * 两段式：第一下变成红色的「确认」，第二下才真的删。不用系统弹窗是因为
 * Compose 里弹窗会打断整页的滚动位置，而且这里删的是一条记录，误触代价不算大，
 * 但也不能一下就删掉——所以用这种「轻确认」的形态。
 *
 * 进行中的记录文案是「重置」：删掉之后采样线程下一拍会重新建一条，
 * 语义是「从现在重新开始记」，跟删一条历史记录不完全一样。
 */
@Composable
internal fun DeleteChip(armed: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (armed) TempColor.copy(alpha = 0.18f) else CardStroke)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (armed) "确认" else label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (armed) TempColor else MdThemeOnSurfaceVariant
        )
    }
}

// ======================= 设置 =======================

/**
 * 「充电统计设置」。
 *
 * 这些项都是**采样器**这套公共设施的开关：采样器在充电场景下才持锁常跑
 * （放电不持锁、只靠定时器），所以「开统计 / 采样间隔 / 常驻通知 / 双电芯 /
 * 电流来源诊断 / 清空数据」归在充电这一侧。（「双电芯」本质是显示倍率，
 * 但充电曲线是它最直接的作用面，跟着采样设置一起放。）
 */
@Composable
private fun ChargeSettingsCard(
    statsEnabled: Boolean,
    onStatsEnabledChange: (Boolean) -> Unit,
    intervalSec: Int,
    onIntervalChange: (Int) -> Unit,
    notificationEnabled: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    dualCellEnabled: Boolean,
    onDualCellChange: (Boolean) -> Unit,
    sampleCount: Int,
    onClearData: () -> Unit,
    currentSource: String?
) {
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard(title = "充电统计设置") {
        ToggleRow(
            label = "开启统计",
            checked = statsEnabled,
            onCheckedChange = onStatsEnabledChange,
            description = "关闭后停止采样，已有数据保留"
        )
        Spacer(modifier = Modifier.height(8.dp))
        StepperRow(
            label = "采样间隔",
            valueText = "$intervalSec 秒",
            // 传的是**步进量**（±1），不是新的秒数。MainActivity 那边会自己乘
            // INTERVAL_STEP_SEC 再夹到合法区间；这里如果传 intervalSec + it，
            // 就会被当成 16 这样的「步数」再乘一次 5，一下顶到上限 120 卡死。
            onStep = { onIntervalChange(it) },
            enabled = statsEnabled
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "常驻通知",
            checked = notificationEnabled,
            onCheckedChange = onNotificationChange,
            description = "关掉只是不显示；前台服务本身需要它保活"
        )
        // 双电芯跟监视条共用同一个开关（读写的是同一个 prefs 键），
        // 拨完这一页的数字立刻翻倍/减半，悬浮窗那边下一拍也会跟着变
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "双电芯（功率 ×2）",
            checked = dualCellEnabled,
            onCheckedChange = onDualCellChange,
            description = "功率/电流只有悬浮窗的一半时开启；与「监视项目」页的开关是同一个"
        )

        // 电流取数诊断。机型差异太大（联发科 HAL 常常不实现 CURRENT_NOW），
        // 把「当前走的哪条路」写出来，出问题时不用翻 logcat
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (currentSource != null) {
                "电流读取正常（来源：$currentSource）"
            } else {
                "读取不到电流：系统接口、广播、sysfs 直读都试过了。可尝试在 root 管理器里" +
                        "给 NexusFloat 授权后重启本应用；仍不行请把机型反馈给作者。"
            },
            fontSize = 10.sp,
            color = if (currentSource != null) MdThemeOnSurfaceVariant else TempColor,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本机已有 $sampleCount 个采样点",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (confirmClear) TempColor.copy(alpha = 0.18f) else ToggleOffContainer)
                    .clickable {
                        if (confirmClear) {
                            onClearData()
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (confirmClear) "再点一次确认清空" else "清空统计数据",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (confirmClear) TempColor else MdThemeOnSurfaceVariant
                )
            }
        }
    }
}

/**
 * 「使用统计设置」。
 *
 * 这两个开关按定义都只影响**不充电时**的统计：
 * - 「仅亮屏时记录」：只在没插电时决定要不要跳过着点；充电期间恒不跳过（见
 *   BatterySampler#skipByScreenOnly），所以界面上充电时会显示成「关」且置灰。
 * - 「使用情况访问」：只服务应用使用榜（前台时长的精确来源），充电场景用不到。
 * 所以放在使用统计这一侧，跟它们真正影响的卡片挨在一起。
 */
@Composable
private fun UsageSettingsCard(
    screenOnOnly: Boolean,
    onScreenOnOnlyChange: (Boolean) -> Unit,
    /** 当前是否插着电；插着电时「仅亮屏时记录」自动关闭并置灰 */
    charging: Boolean,
    usagePermission: Boolean,
    onOpenUsageAccess: () -> Unit
) {
    SectionCard(title = "使用统计设置") {
        // 「仅亮屏时记录」在充电期间自动关闭。
        //
        // 理由：插着电本来就谈不上省电，而这个开关开着会让整夜充电的曲线只剩零星
        // 几个点（采样侧同样把充电排除在外，见 BatterySampler#skipByScreenOnly）。
        // 这里是把那条规则**显示出来**：充电中这一行显示成「关」且不可点，副标题
        // 说明拔电后会自动恢复。
        //
        // 恢复不需要额外做什么——用户存下来的值从头到尾没被改写。真去「先存原值、
        // 改掉、充电完再写回来」的话，进程一旦在充电中途被杀，用户的选择就永久丢了。
        ToggleRow(
            label = "仅亮屏时记录",
            checked = screenOnOnly && !charging,
            onCheckedChange = onScreenOnOnlyChange,
            enabled = !charging,
            description = if (charging) {
                if (screenOnOnly) {
                    "充电中已自动关闭（插着电本来就全量记录），拔电后恢复为「开」"
                } else {
                    "充电中不适用（插着电本来就全量记录）"
                }
            } else {
                "更省电，但息屏期间的电量曲线会断开"
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "使用情况访问",
            checked = usagePermission,
            onCheckedChange = { onOpenUsageAccess() },
            description = if (usagePermission) {
                "已授权，应用时长取自系统记录"
            } else {
                "点此前往系统设置授权，可让应用时长统计更精确"
            }
        )
    }
}

// ======================= 小部件 =======================

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MdThemeOnSurfaceVariant,
        modifier = Modifier.padding(4.dp)
    )
}

@Composable
internal fun PermissionHint(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ChargeColor.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "未授权「使用情况访问」，应用时长只能按采样估算。点此前往授权",
            fontSize = 11.sp,
            color = ChargeColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = text, fontSize = 10.sp, color = MdThemeOnSurfaceVariant)
    }
}

/** 步进行：只用一个「+」按钮，长按/连点调整间隔；减号省掉，间隔本来就是个粗调 */
@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onStep: (Int) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ToggleOffContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (enabled) MdThemeOnSurface else MdThemeOnSurface.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "越小曲线越细，越费电",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        StepButton("−", enabled) { onStep(-1) }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = valueText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MdThemePrimary else MdThemeOnSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        StepButton("+", enabled) { onStep(1) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) MdThemePrimary.copy(alpha = 0.15f) else CardStroke)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MdThemePrimary else MdThemeOnSurfaceVariant
        )
    }
}

// ======================= 工具 =======================

/** 时长：按量级换单位，秒级的不写成 0 分 */
internal fun fmtDuration(ms: Long): String {
    if (ms <= 0L) {
        return "0 分"
    }
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        m > 0 && s > 0 && m < 10 -> "${m}分${s}秒"
        m > 0 -> "${m}分"
        else -> "${s}秒"
    }
}

private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private val CLOCK_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun fmtDateTime(ts: Long): String = TIME_FMT.format(Date(ts))
internal fun fmtClock(ts: Long): String = CLOCK_FMT.format(Date(ts))

/**
 * 现在是不是插着电。
 *
 * 读 `ACTION_BATTERY_CHANGED` 的粘性广播（receiver 传 null 就是「只取缓存那份，
 * 不注册」），所以不耗电、也不需要注销。读不到就按「没插电」处理：那只是让
 * 「仅亮屏时记录」保持用户自己设的状态，不会误改任何东西。
 */
internal fun readChargingNow(context: Context): Boolean {
    return try {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        intent != null && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    } catch (e: Throwable) {
        false
    }
}

/** 批量把包名解析成应用名；解析不到就留着包名，不显示成空白 */
internal fun appLabels(context: Context, packages: List<String>): Map<String, String> {
    val pm = context.packageManager
    val out = HashMap<String, String>(packages.size)
    for (pkg in packages) {
        out[pkg] = try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        } catch (e: Throwable) {
            pkg
        }
    }
    return out
}

/** 应用图标解码缓存（进程级）。图标解码不便宜，帧记录页 3 秒刷一次，没缓存等于反复解同一批 */
private val iconCache = LruCache<String, ImageBitmap>(64)

/**
 * 批量取应用图标。
 *
 * 手动把 Drawable 画进 Bitmap 而不是用 core-ktx 的 toBitmap：本项目的依赖里
 * 没有 core-ktx（只有 appcompat 带进来的 core），为了一个转换函数再拉一个依赖不划算。
 *
 * 结果按包名缓存到 {@link #iconCache}：命中就不再解码，直接复用上一轮的 Bitmap。
 * 签名不变，统计页、详情页、帧记录页等既有调用点自动受益。
 */
internal fun appIcons(context: Context, packages: List<String>): Map<String, ImageBitmap> {
    val pm = context.packageManager
    val out = HashMap<String, ImageBitmap>(packages.size)
    val sizePx = (22 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    for (pkg in packages) {
        val cached = iconCache.get(pkg)
        if (cached != null) {
            out[pkg] = cached
            continue
        }
        try {
            val icon: Drawable = pm.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bmp)
            icon.setBounds(0, 0, sizePx, sizePx)
            icon.draw(canvas)
            val image = bmp.asImageBitmap()
            iconCache.put(pkg, image)
            out[pkg] = image
        } catch (e: Throwable) {
            // 图标拿不到就让它退回首字母占位，不影响其他行
        }
    }
    return out
}
