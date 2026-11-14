package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经 Settings.Global 传设置的通道。
 *
 * 为什么需要第三条通道（v1.6.11）
 * 前两条各有前提，而澎湃 OS 4 上都不成立或者说不准：
 * RemotePreferences——要求模块自己的进程被框架注入。实测这机型上 LSPosed 只注入了
 * com.android.systemui，作用域表里就一条记录。
 * ContentProvider 快照——要求 SystemUI 能跨应用 query 模块进程。可能被 SELinux、
 * 包可见性或「进程还没拉起来」挡住，而且失败原因很难一个个排除。
 * Settings.Global 没这些前提：写侧有 root（settings put global），读侧是 system uid
 * 直接读 provider，不经过任何跨应用调用。只要 root 在就通。
 *
 * 代价是写入要 fork 一次 settings 命令，所以只在设置真的变了才写，
 * 不像 GPU 数据那样每秒刷。
 */
public final class SettingsChannel {

    /**
     * logcat 里能看到的标签。
     *
     * LogUtils 的输出只进 LSPosed 的模块日志，用户抓 logcat 时看不到；写入通道坏掉
     * 恰恰是最需要用户自己确认的一类问题（有没有 root、settings 命令能不能用），
     * 所以这里的诊断一律走 android.util.Log，保证 logcat 里抓得到。
     */
    private static final String TAG = "NexusFloat";

    /** 读侧缓存：整体替换，读的时候不用加锁 */
    private static volatile Map<String, String> values = new HashMap<>();
    private static volatile Map<String, String> types = new HashMap<>();
    /** 上次读到的原始串，没变就跳过解析 */
    private static volatile String lastRaw;
    private static volatile boolean everLoaded;

    private SettingsChannel() {}

    /** 快照有没有成功读到过一次 */
    public static boolean isReady() {
        return everLoaded;
    }

    // ---------------- 读侧（SystemUI 进程） ----------------

    /**
     * 从 Settings.Global 刷新快照。
     *
     * Settings.Global.getString 走的是 SettingsProvider，system uid 调它很轻
     * （有本地缓存），直接在轮询线程里做就行。不过还是只在原始串变化时才解析，
     * 省掉每秒一次的字符串切分。
     */
    public static void refresh(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            String raw = Settings.Global.getString(
                    ctx.getContentResolver(), Constants.Component.SETTINGS_KEY);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            if (raw.equals(lastRaw)) {
                return;
            }
            byte[] decoded = Base64.decode(raw, Base64.NO_WRAP);
            String text = new String(decoded, StandardCharsets.UTF_8);

            Map<String, String> v = new HashMap<>();
            Map<String, String> t = new HashMap<>();
            parse(text, v, t);

            // 解出来一个键都没有，但原文并不空：这串数据是坏的（被截断、或者 Base64
            // 解出来是乱码）。这时候绝不能拿空表覆盖上一份好快照——一旦覆盖，所有键都
            // 变成「这条通道里没有」，读取整体掉到默认值，现象就是「设置全丢、字号回到
            // 默认」。留着旧数据等下一次写入，比清空安全。
            if (v.isEmpty() && !text.isEmpty()) {
                LogUtils.w("SettingsChannel refresh: unparsable blob, keep previous snapshot");
                return;
            }

            values = v;
            types = t;
            lastRaw = raw;
            if (!everLoaded) {
                LogUtils.i("SettingsChannel loaded, " + v.size() + " keys");
            }
            everLoaded = true;
        } catch (Throwable e) {
            LogUtils.w("SettingsChannel refresh failed", e);
        }
    }

    /**
     * 解析 key=type:value 的行集合。
     *
     * 只在第一个 = 和后面第一个 : 处切：值里可能含这两个字符（自定义名称、
     * 白名单里的包名），多切一次内容就被截断了。
     */
    static void parse(String text, Map<String, String> outValues,
                      Map<String, String> outTypes) {
        for (String line : text.split(String.valueOf(Constants.Component.SNAPSHOT_LINE_SEP))) {
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf(Constants.Component.SNAPSHOT_KV_SEP);
            if (eq <= 0 || eq >= line.length() - 1) {
                continue;
            }
            String key = line.substring(0, eq);
            String rest = line.substring(eq + 1);
            int colon = rest.indexOf(Constants.Component.SNAPSHOT_TYPE_SEP);
            if (colon < 0) {
                continue;
            }
            outTypes.put(key, rest.substring(0, colon));
            outValues.put(key, rest.substring(colon + 1));
        }
    }

    public static boolean contains(String key) {
        return values.containsKey(key);
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = values.get(key);
        if (v == null || !Constants.Component.TYPE_BOOL.equals(types.get(key))) {
            return def;
        }
        return Boolean.parseBoolean(v);
    }

    public static int getInt(String key, int def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static float getFloat(String key, float def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String getString(String key, String def) {
        String v = values.get(key);
        return v != null ? v : def;
    }

    // ---------------- 写侧（模块 App 进程） ----------------

    /**
     * 把本地 SharedPreferences 整表写进 Settings.Global。
     *
     * 要 root：settings put global 需要 WRITE_SECURE_SETTINGS，普通应用拿不到。
     * 没 root 就静默失败，前两条通道照样能用。
     *
     * v1.9.4 起做了两件事，都是为了「写失败不要静默」：
     * 1. 显式带上 --user。settings 命令不带 --user 时按 current 处理，也就是
     *    「此刻的前台用户」。用户装在空间/工作资料里、或者写入发生时前台用户正在
     *    切换，写入就会落到另一个用户的表里，而 SystemUI 读的是它自己那份，永远读不到。
     *    这里用本进程 uid 推出的用户——写入者的用户和它想影响的 SystemUI 是同一个，
     *    这比写死 0 准确（有人把模块装在第二空间里用）。
     * 2. 写完读回校验。原先 execLines 的返回值直接丢掉，命令不存在、被 SELinux 拦、
     *    空间不足……全都表现为「返回 true，什么都没发生」，用户侧就是「改了没反应」
     *    而日志里一片安静。现在读回不一致会打一条 logcat 可见的警告。
     *
     * 返回写成功、并且读回校验也通过。
     */
    public static boolean publish(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
            // 盖一个新的修订号再整表编码：读侧靠它判断三条通道里哪一份最新。
            // apply() 会同步更新内存里的表，紧接着的 getAll() 立刻就能读到，
            // 不需要 commit() 那次磁盘同步
            prefs.edit()
                    .putLong(Constants.Remote.KEY_PREFS_REV, System.currentTimeMillis())
                    .apply();
            Map<String, ?> all = prefs.getAll();
            String text = encode(all);
            String b64 = Base64.encodeToString(
                    text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            RootShell shell = RootShell.get();
            if (shell.isDead()) {
                LogUtils.w("SettingsChannel publish skipped: no root shell");
                Log.w(TAG, "SettingsChannel: 没有 root，设置无法推到 SystemUI"
                        + "（Remote / provider 两条通道不受影响）");
                return false;
            }
            // Base64 只有 ASCII 字母数字和 +/=，但还是加了引号：'+' 在个别 shell
            // 里有特殊含义，而引号是零成本的
            String target = "--user " + currentUserId() + " global "
                    + Constants.Component.SETTINGS_KEY;
            String cmd = "settings put " + target + " " + RootShell.quote(b64);
            // 不能用 exec()：它的默认超时是给读 sysfs 设计的（几百毫秒），
            // 而 settings 命令得起一个 app_process，慢一个数量级
            shell.execLines(cmd, 1, Constants.Component.SETTINGS_WRITE_TIMEOUT_MS);

            // 读回校验：这一步是「写失败」唯一的证据来源。settings put 命令本身
            // 成功退出并不代表值真的落到了 SystemUI 会读的那个位置上
            List<String> back = shell.execLines(
                    "settings get " + target, 1, Constants.Component.SETTINGS_WRITE_TIMEOUT_MS);
            String got = (back == null || back.isEmpty()) ? null : back.get(0);
            if (!b64.equals(got)) {
                Log.w(TAG, "SettingsChannel publish 校验失败：写入 " + b64.length()
                        + " 个 b64 字符（" + all.size() + " 项，user "
                        + currentUserId() + "），读回 "
                        + (got == null ? "null（命令没输出，可能没 root 或被拦）"
                        : got.length() + " 个 b64 字符（内容不一致）")
                        + "；SystemUI 侧读到的会是旧值");
                return false;
            }
            LogUtils.i("SettingsChannel published, " + all.size()
                    + " keys, " + b64.length() + " b64 chars");
            return true;
        } catch (Throwable t) {
            LogUtils.w("SettingsChannel publish failed", t);
            Log.w(TAG, "SettingsChannel publish 异常：" + t);
            return false;
        }
    }

    /**
     * 本进程所在的用户 id。
     *
     * 从 uid 反推，不用 UserHandle.getUserId()——它是 @hide 的，公开 SDK 里根本编不过。
     * Android 的 uid 布局是 user_id * PER_USER_RANGE + app_id（PER_USER_RANGE 恒为
     * 100000，见 Process.FIRST_APPLICATION_UID 的定义），所以整除就是用户 id：
     * 主用户是 0，第二空间/XSpace 这类是 999，工作资料是 10，能一视同仁地算出来。
     */
    private static int currentUserId() {
        return Process.myUid() / PER_USER_RANGE;
    }

    /** Android 里每个用户分到的 uid 区间大小，见 Process.FIRST_APPLICATION_UID */
    private static final int PER_USER_RANGE = 100000;

    /**
     * 往 Settings.Global 写一个独立的键值（不走 prefs 打包）。
     *
     * 给「帧率同步」这种高频、单值的数据用：每秒都要更新，打包整个 prefs
     * 再写一遍太重。写入仍然走 root 的 settings 命令。
     *
     * 返回写成功没有。
     */
    public static boolean writeRaw(Context ctx, String key, String value) {
        if (ctx == null || key == null || value == null) {
            return false;
        }
        try {
            RootShell shell = RootShell.get();
            if (shell.isDead()) {
                return false;
            }
            // --user 的理由同 publish()：不带就是「当前前台用户」，可能在空间里
            String cmd = "settings put --user " + currentUserId() + " global " + key
                    + " " + RootShell.quote(value);
            shell.execLines(cmd, 1, Constants.Component.SETTINGS_WRITE_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            LogUtils.w("SettingsChannel writeRaw failed", t);
            return false;
        }
    }

    /**
     * 把 prefs 编码成 key=type:value 的行集合。
     *
     * 带类型标记的理由跟 provider 那条通道一样：跨进程只能传字符串，对端得知道
     * 原本是 boolean 还是 int，否则 "true" 和 "1" 分不清。
     *
     * 键名或值里含换行的项直接跳过——换行是行分隔符，混进去整份快照就废了。
     * 白名单用换行分隔包名，正好是这种情况，所以它单独把换行换成空格再存，
     * 由读侧还原（见 NexusBridge.getWhitelist）。
     */
    static String encode(Map<String, ?> all) {
        StringBuilder sb = new StringBuilder(all.size() * 32);
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            String type;
            if (v instanceof Boolean) {
                type = Constants.Component.TYPE_BOOL;
            } else if (v instanceof Integer) {
                type = Constants.Component.TYPE_INT;
            } else if (v instanceof Long) {
                type = Constants.Component.TYPE_LONG;
            } else if (v instanceof Float) {
                type = Constants.Component.TYPE_FLOAT;
            } else if (v instanceof String) {
                type = Constants.Component.TYPE_STRING;
            } else {
                continue;
            }
            String key = e.getKey();
            String value = String.valueOf(v);
            if (key.indexOf(Constants.Component.SNAPSHOT_LINE_SEP) >= 0) {
                continue;
            }
            // 值里的换行换成空格：白名单就是换行分隔的，不能破坏行结构。
            // 读侧的 getWhitelist 会按空格和换行两种分隔符都切一遍
            value = value.replace(Constants.Component.SNAPSHOT_LINE_SEP, ' ');
            sb.append(key)
                    .append(Constants.Component.SNAPSHOT_KV_SEP)
                    .append(type)
                    .append(Constants.Component.SNAPSHOT_TYPE_SEP)
                    .append(value)
                    .append(Constants.Component.SNAPSHOT_LINE_SEP);
        }
        return sb.toString();
    }
}
