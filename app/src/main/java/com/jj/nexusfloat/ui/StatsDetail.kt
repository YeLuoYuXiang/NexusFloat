package com.jj.nexusfloat.ui

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 历史记录的详情页。
//
// 「统计」页上的充电历史 / 使用周期历史，点一条就进到这里。
//
// 为什么不新开一个 Activity / 引入导航：这一页是纯只读的，进去了只有两条出路
// （返回、删除），套一层导航栈换来的只是返回栈状态要额外维护。做成「同一个页面内
// 就地替换内容」，加上 BackHandler 接管系统返回键，用起来和真的跳页没有区别
// （见 StatsScreen 里 detailSessionId / detailPeriodId 的用法）。
// ============================================================

/** 详情页的时间戳：跨年的老记录只给「月-日 时:分」会让人认不出来 */
private val FULL_TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun fmtFullDateTime(ts: Long): String = FULL_TIME_FMT.format(Date(ts))

/** 一次充电记录的详情 */
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    /** 进行中的记录要跟着秒级刷新；已结束的是死数据，取一次就够 */
    ongoing: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    // key 一次性把整棵子树按记录 id 重建：否则换一条记录时 produceState 里还留着
    // 上一条的内容，会先闪一下上一次的曲线再跳成这次的
    key(sessionId) {
        SessionDetailBody(
            sessionId = sessionId,
            ongoing = ongoing,
            onBack = onBack,
            onDelete = onDelete
        )
    }
}

@Composable
private fun SessionDetailBody(
    sessionId: Long,
    ongoing: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(ongoing) {
        if (!ongoing) {
            return@LaunchedEffect
        }
        while (true) {
            delay(REFRESH_MS)
            refreshTick++
        }
    }

    val detail by produceState<StatsRepository.SessionDetail?>(
        initialValue = null,
        sessionId,
        refreshTick
    ) {
        value = withContext(Dispatchers.IO) {
            StatsRepository.loadSessionDetail(context, sessionId)
        }
    }

    DetailHeader("充电记录详情", onBack)

    val d = detail
    if (d == null) {
        LoadingCard("充电记录")
        return
    }
    val s = d.session
    if (s == null) {
        SectionCard(title = "充电记录") {
            EmptyHint("这条记录已经不在了，可能刚在列表里被删掉。返回列表刷新一下就能看到最新的。")
        }
        return
    }

    val now = System.currentTimeMillis()

    SectionCard(title = if (s.ongoing) "本次充电（进行中）" else "这一次充电") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (s.ongoing) "充电中" else "已完成",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (s.ongoing) ChargeColor else MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "共 ${fmtDuration(s.durationMs(now))}",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (s.ongoing) {
                "${fmtFullDateTime(s.startTs)} 开始，还在充"
            } else {
                "${fmtFullDateTime(s.startTs)} → ${fmtFullDateTime(s.endTs)}"
            },
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = if (s.ongoing) "已充入" else "充入电量",
                value = if (s.chargedMah > 0f) "${s.chargedMah.toInt()} mAh" else "-- mAh",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "电量变化",
                value = "${s.startLevel}% → ${s.endLevel}%",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "平均功率",
                value = if (s.avgPowerW > 0f) watt(s.avgPowerW) else "--",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "峰值功率",
                value = if (s.peakPowerW > 0f) watt(s.peakPowerW) else "--",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "平均温度",
                value = if (s.avgTempC > 0f) String.format(Locale.US, "%.1f°C", s.avgTempC) else "--",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "最高温度",
                value = if (s.peakTempC > 0f) String.format(Locale.US, "%.1f°C", s.peakTempC) else "--",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "充电速度",
                value = if (s.levelPerHour(now) > 0f) {
                    String.format(Locale.US, "%.0f %%/时", s.levelPerHour(now))
                } else {
                    "--"
                },
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "记录采样点",
                value = "${d.sampleCount} 个",
                modifier = Modifier.weight(1f)
            )
        }
        if (s.capacityMah > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "按本次充入电量反推，电池容量约 ${s.capacityMah.toInt()} mAh（粗略估算，仅供参考）",
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    SectionCard(title = "电量与充电功率曲线") {
        if (d.curve.size < 2) {
            EmptyHint(
                if (d.sampleCount < 2) {
                    "这次充电的采样明细已经不在了。明细只保留 45 天，上面那些数字是从" +
                            "永久保存的汇总里读出来的。"
                } else {
                    "这次充电太短，采样点还不够画曲线（至少需要两个点）。"
                }
            )
        } else {
            ChargeCurves(d.curve)
        }
    }

    DetailDeleteRow(
        ongoing = s.ongoing,
        what = "充电记录",
        onDelete = onDelete
    )
}

/** 一轮使用周期的详情 */
@Composable
fun PeriodDetailScreen(
    periodId: Long,
    ongoing: Boolean,
    usagePermission: Boolean,
    onOpenUsageAccess: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    key(periodId) {
        PeriodDetailBody(
            periodId = periodId,
            ongoing = ongoing,
            usagePermission = usagePermission,
            onOpenUsageAccess = onOpenUsageAccess,
            onBack = onBack,
            onDelete = onDelete
        )
    }
}

@Composable
private fun PeriodDetailBody(
    periodId: Long,
    ongoing: Boolean,
    usagePermission: Boolean,
    onOpenUsageAccess: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(ongoing) {
        if (!ongoing) {
            return@LaunchedEffect
        }
        while (true) {
            delay(REFRESH_MS)
            refreshTick++
        }
    }

    val detail by produceState<StatsRepository.PeriodDetail?>(
        initialValue = null,
        periodId,
        refreshTick
    ) {
        value = withContext(Dispatchers.IO) {
            StatsRepository.loadPeriodDetail(context, periodId)
        }
    }

    DetailHeader("使用周期详情", onBack)

    val d = detail
    if (d == null) {
        LoadingCard("使用周期")
        return
    }
    val p = d.period
    if (p == null) {
        SectionCard(title = "使用周期") {
            EmptyHint("这轮记录已经不在了，可能刚在列表里被删掉。返回列表刷新一下就能看到最新的。")
        }
        return
    }

    val now = System.currentTimeMillis()

    SectionCard(title = if (p.ongoing) "本次使用（进行中）" else "这一轮使用") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (p.ongoing) "使用中" else "已结束",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (p.ongoing) ScreenOnColor else MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "共 ${fmtDuration(p.durationMs(now))}",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (p.ongoing) {
                "${fmtFullDateTime(p.startTs)} 拔的充电器，还在用"
            } else {
                "${fmtFullDateTime(p.startTs)} → ${fmtFullDateTime(p.endTs)}"
            },
            fontSize = 11.sp,
            color = MdThemeOnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "亮屏时间",
                value = fmtDuration(p.screenOnMs),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "待机时间",
                value = fmtDuration(p.standbyMs(now)),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "电量消耗",
                value = "${p.levelDrop()}%",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "耗电估算",
                value = estimateDrainText(p, d.capacityMah),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "平均功耗",
                value = if (p.avgPowerW > 0f) String.format(Locale.US, "%.2f W", p.avgPowerW) else "--",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                title = "耗电速度",
                value = if (p.levelPerHour(now) > 0f) {
                    String.format(Locale.US, "%.1f %%/时", p.levelPerHour(now))
                } else {
                    "--"
                },
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "记录采样点",
                value = "${d.sampleCount} 个",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        SegmentedBar(
            segments = listOf(
                p.screenOnMs.toFloat() to ScreenOnColor,
                p.standbyMs(now).toFloat() to StandbyColor
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(ScreenOnColor, "亮屏 ${fmtDuration(p.screenOnMs)}")
            Spacer(modifier = Modifier.width(14.dp))
            LegendDot(StandbyColor, "待机 ${fmtDuration(p.standbyMs(now))}")
        }

        if (!usagePermission) {
            Spacer(modifier = Modifier.height(10.dp))
            PermissionHint(onOpenUsageAccess)
        }
    }

    // 图标只在包名集合变化时才重新解码：分桶数据每 5 秒就会因为时长微调而变成
    // 一个新对象，直接拿它当 key 的话会每 5 秒重新解码一轮应用图标
    val towerPkgs = remember(d.fgBuckets) { d.fgBuckets.map { it.pkg }.distinct() }
    val towerIcons = remember(towerPkgs) { appIcons(context, towerPkgs) }
    val tower = remember(d.fgBuckets, d.buckets, towerIcons) {
        if (d.fgBuckets.isEmpty() || d.buckets <= 0) {
            null
        } else {
            AppTower(buckets = bucketize(d.fgBuckets, d.buckets), icons = towerIcons)
        }
    }

    SectionCard(title = "使用过程") {
        if (d.curve.size < 2) {
            EmptyHint(
                if (d.sampleCount < 2) {
                    "这一轮的采样明细已经不在了。明细只保留 45 天，上面那些数字是从" +
                            "永久保存的汇总里读出来的。"
                } else {
                    "采样点还不够画曲线。"
                }
            )
            return@SectionCard
        }
        val times = d.curve.map { it.ts }
        LineChart(
            lines = listOf(
                ChartLine(
                    label = "电量 (%)",
                    color = ScreenOnColor,
                    points = d.curve.map { it.level.toFloat() }
                )
            ),
            leftMin = 0f,
            leftMax = 100f,
            leftFormat = { "${it.toInt()}%" },
            leftStep = 20f,
            xTicks = axisTicks(times, X_TICK_USAGE_MS),
            xValues = times,
            appTower = tower
        )
        if (tower != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TimelineLegend(towerTotals(d.fgBuckets), appLabelsOf(context, d.fgBuckets), towerIcons)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "底下的每一格图标是一个应用。柱子高低＝那一段前台使用时长，图标自下而上" +
                        "按时长排，用得最久的在最下面；越高的柱说明那一段手机用得越多。" +
                        "息屏期间不记前台应用，所以塔上会有空档。" +
                        if (!usagePermission) {
                            "当前未授予「使用情况访问」，应用时长按采样估算，可能有出入。"
                        } else {
                            ""
                        },
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        if (!usagePermission) {
            Spacer(modifier = Modifier.height(10.dp))
            PermissionHint(onOpenUsageAccess)
        }
    }

    AppUsageCard(
        apps = d.apps,
        drainBaseMah = d.drainBaseMah,
        usagePermission = usagePermission,
        hasPeriod = true,
        onOpenUsageAccess = onOpenUsageAccess
    )

    DetailDeleteRow(
        ongoing = p.ongoing,
        what = "使用周期",
        onDelete = onDelete
    )
}

// ======================= 图标图例 =======================

/** 去重后的包名列表，给 appLabels 用（它按包名解析应用名） */
private fun appLabelsOf(context: android.content.Context, rows: List<StatsStore.FgBucket>):
        Map<String, String> {
    return appLabels(context, rows.map { it.pkg }.distinct())
}

/**
 * 图标图例。
 *
 * 塔上只有图标，认不出是哪个应用（尤其是图标风格接近的一堆国产 App），
 * 所以把这段里用得最久的几个列出来做对照。
 */
@Composable
private fun TimelineLegend(
    totals: List<Pair<String, Long>>,
    labels: Map<String, String>,
    icons: Map<String, ImageBitmap>
) {
    val shown = totals.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        shown.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (pkg, ms) ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIconBadge(icons[pkg], labels[pkg] ?: pkg)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = labels[pkg] ?: pkg,
                                fontSize = 10.sp,
                                color = MdThemeOnSurface,
                                maxLines = 1
                            )
                            Text(
                                text = fmtDuration(ms),
                                fontSize = 9.sp,
                                color = MdThemeOnSurfaceVariant
                            )
                        }
                    }
                }
                // 奇数个时补一个空位，免得最后一行那个被撑成一整行宽
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppIconBadge(icon: ImageBitmap?, name: String) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ToggleOffContainer),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurfaceVariant
            )
        }
    }
}

// ======================= 通用小件 =======================

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MdThemePrimary.copy(alpha = 0.14f))
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = "‹ 返回",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MdThemeOnSurface
        )
    }
}

@Composable
private fun LoadingCard(title: String) {
    SectionCard(title = title) {
        Text(
            text = "正在读取这一条的明细…",
            fontSize = 12.sp,
            color = MdThemeOnSurfaceVariant,
            modifier = Modifier.padding(4.dp)
        )
    }
}

/**
 * 详情页底部的删除入口。
 *
 * 跟列表里的「删除」小按钮一样是两段式，但这里用整行 + 一句后果说明：
 * 在详情页里删掉的就是用户正看着的这一条，得先说清楚会丢什么。
 */
@Composable
private fun DetailDeleteRow(ongoing: Boolean, what: String, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (confirm) TempColor.copy(alpha = 0.15f) else ToggleOffContainer)
            .clickable {
                if (confirm) {
                    onDelete()
                } else {
                    confirm = true
                }
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (ongoing) "重置这一轮记录" else "删除这条$what",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (confirm) TempColor else MdThemeOnSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (ongoing) {
                    "从此刻重新开始记，之前的曲线和汇总都会清掉"
                } else {
                    "连同这段时间的采样点一起删掉，不可恢复"
                },
                fontSize = 10.sp,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (confirm) "再点一次确认" else "删除",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (confirm) TempColor else CardStroke
        )
    }
}

/** 功率文本。10W 以上不用给两位小数，读数本身没那么准 */
private fun watt(value: Float): String {
    return if (value >= 10f) {
        String.format(Locale.US, "%.1f W", value)
    } else {
        String.format(Locale.US, "%.2f W", value)
    }
}
