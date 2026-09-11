package com.jj.nexusfloat.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jj.nexusfloat.ui.theme.*
import com.jj.nexusfloat.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用选择列表里的一项 */
data class AppEntry(
    val packageName: String,
    val label: String
)

/**
 * 读已安装应用列表。
 *
 * 要 QUERY_ALL_PACKAGES：Android 11 起应用默认只能看到自己，外加少数声明了 queries
 * 的包，没这个权限列表基本是空的。它是安装时授予的 normal 权限，运行时申请不了也
 * 没必要申请；只用来在本地列出可选应用，不联网。
 *
 * includeAll 传 true 就是全部已安装应用，false 只列有启动图标的那些。
 */
fun loadInstalledApps(context: Context, includeAll: Boolean): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    return try {
        val packages: List<String> = if (includeAll) {
            pm.getInstalledApplications(0).map { it.packageName }
        } else {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        }
        packages.asSequence()
            .distinct()
            .filter { it != self }
            .map { pkg ->
                val label = try {
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                AppEntry(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    } catch (e: Exception) {
        LogUtils.w("loadInstalledApps failed", e)
        emptyList()
    }
}

/** Drawable 转 ImageBitmap。图标是按需加载的，只有列表里看得见的项会走到这儿。 */
private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap? {
    return try {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun AppIcon(packageName: String, pm: PackageManager) {
    val sizePx = 96
    // 按包名缓存：LazyColumn 复用条目的时候不会重复解码
    val icon = remember(packageName) {
        try {
            pm.getApplicationIcon(packageName).toImageBitmap(sizePx)
        } catch (e: Exception) {
            null
        }
    }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ToggleOffContainer)
        )
    }
}

/**
 * 应用选择页：勾一下哪些应用在前台时才显示监视条。
 *
 * selected 是当前已选的包名集合，onToggle 是勾上/取消某个包名的回调。
 */
@Composable
fun AppPickerScreen(
    modifier: Modifier = Modifier,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    var includeAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    // 列表加载丢到后台线程：应用一多，getInstalledApplications + loadLabel 会把主线程卡住
    LaunchedEffect(includeAll) {
        apps = null
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context, includeAll)
        }
    }

    val list = apps
    val filtered = remember(list, query) {
        val q = query.trim().lowercase()
        when {
            list == null -> emptyList()
            q.isEmpty() -> list
            else -> list.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "返回", fontSize = 14.sp, color = MdThemePrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "选择应用",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurface
                )
                Text(
                    text = "已选 ${selected.size} 个",
                    fontSize = 12.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text(text = "清空", fontSize = 14.sp, color = StatusDisconnected)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(text = "搜索应用名或包名", fontSize = 14.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "显示全部应用",
                fontSize = 13.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = includeAll,
                onCheckedChange = { includeAll = it }
            )
        }

        HorizontalDivider(color = CardStroke)

        when {
            list == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MdThemePrimary)
            }

            filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (list.isEmpty()) "读不到应用列表" else "没有匹配的应用",
                    fontSize = 13.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        pm = pm,
                        checked = selected.contains(app.packageName),
                        onCheckedChange = { onToggle(app.packageName, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    pm: PackageManager,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (checked) ToggleOnContainer else ToggleOffContainer)
            .border(1.dp, if (checked) ToggleOnBorder else CardStroke, shape)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = app.packageName, pm = pm)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 14.sp,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                color = MdThemeOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Checkbox(
            checked = checked,
            // 整行接管点击，Checkbox 只当指示用
            onCheckedChange = null
        )
    }
}
