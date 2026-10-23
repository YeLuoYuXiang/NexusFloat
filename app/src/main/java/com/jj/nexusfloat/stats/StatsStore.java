package com.jj.nexusfloat.stats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.BatteryManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计数据的落盘层：一个 SQLite 库，三张表。
 *
 * samples——原始采样点，一秒多一点一条都嫌密，所以按「采样间隔」存（默认 15 秒）。
 * 充电曲线、放电曲线、应用功耗归因全从这张表现算。
 *
 * sessions——充电会话（插上充电器到拔掉）的汇总行。明细会被 retention 清掉，
 * 汇总行永久留着，所以历史记录页就算看不到曲线，头部那几个数字也还在。
 *
 * periods——放电周期（拔电到下次插电）的汇总行，同上。
 *
 * 汇总行是「每次落点都刷新一遍」的：充电中和放电中的那一条会随采样不断被 UPDATE，
 * 这样 App 被杀、进程重启之后接着算，不会因为丢内存状态而把一次充电拆成两段。
 * updated_ts 就是用来判断有没有「同一时刻」的会话行可以接着续的。
 */
public final class StatsStore extends SQLiteOpenHelper {

    private static final String TAG = "StatsStore";

    // ---- 表名 / 列名 ----

    private static final String T_SAMPLES = "samples";
    private static final String T_SESSIONS = "sessions";
    private static final String T_PERIODS = "periods";

    // 列清单抽出来：readSession/readPeriod 是按列序号取值（getLong(0)、getFloat(5)…），
    // 一旦哪条 SELECT 少写或多写一列，取出来的就全是错位的数字，而且不会报错。
    // 让所有查询共用同一份清单，改动只有一个地方

    private static final String SESSION_COLUMNS =
            "id,start_ts,end_ts,start_level,end_level,charged_mah,avg_power_w,"
                    + "peak_power_w,avg_temp_c,peak_temp_c,plugged,capacity_mah,"
                    + "sample_count,ongoing";

    private static final String PERIOD_COLUMNS =
            "id,start_ts,end_ts,start_level,end_level,drain_mah,avg_power_w,"
                    + "screen_on_ms,total_ms,view_ms,sample_count,ongoing";

    /**
     * samples 表的列清单。readSample 是按**列序号**取值的（getLong(0)、getFloat(5)…），
     * 一旦哪条 SELECT 少写或多写一列，取出来的就是错位的数字，而且不会报错。
     * 让所有查询共用同一份清单，加列时只需要改这里 + readSample 一处。
     *
     * power_known 放在 power_w 后面、temp_c 前面，与 onCreate 的建表顺序一致。
     */
    private static final String SAMPLE_COLUMNS =
            "ts,dt_ms,level,voltage_mv,current_ma,power_w,power_known,temp_c,status,plugged,"
                    + "screen_on,fg_pkg";

    private static volatile StatsStore sInstance;

    private StatsStore(Context context) {
        super(context.getApplicationContext(),
                Constants.Stats.DB_NAME, null, Constants.Stats.DB_VERSION);
    }

    public static StatsStore get(Context context) {
        StatsStore inst = sInstance;
        if (inst == null) {
            synchronized (StatsStore.class) {
                inst = sInstance;
                if (inst == null) {
                    sInstance = inst = new StatsStore(context);
                }
            }
        }
        return inst;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_SAMPLES + " ("
                + "ts INTEGER PRIMARY KEY,"
                // 距上一个采样点的毫秒数。积分（充入电量、耗电）必须按真实间隔算：
                // 用户中途改了采样间隔，按固定值乘会把结果整体放大或缩小
                + "dt_ms INTEGER NOT NULL DEFAULT 0,"
                + "level INTEGER NOT NULL DEFAULT 0,"
                + "voltage_mv INTEGER NOT NULL DEFAULT 0,"
                // 带符号：正=流入电池（充），负=流出（放）。符号由插电状态决定，
                // 不信任内核 current_now 的符号约定（各家相反）
                + "current_ma INTEGER NOT NULL DEFAULT 0,"
                + "power_w REAL NOT NULL DEFAULT 0,"
                // 这一拍的功率/电流是不是「本拍真读到的」：1=是，0=读不到或被区间拒掉
                // （沿用值也算 0）。见 Sample#powerKnown 与 BatterySampler。
                // v2 新增：老库由 onUpgrade 补这一列。
                + "power_known INTEGER NOT NULL DEFAULT 1,"
                + "temp_c REAL NOT NULL DEFAULT 0,"
                + "status INTEGER NOT NULL DEFAULT 1,"
                + "plugged INTEGER NOT NULL DEFAULT 0,"
                + "screen_on INTEGER NOT NULL DEFAULT 0,"
                + "fg_pkg TEXT"
                + ")");

        db.execSQL("CREATE TABLE " + T_SESSIONS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ts INTEGER NOT NULL,"
                + "end_ts INTEGER NOT NULL,"
                + "start_level INTEGER NOT NULL DEFAULT 0,"
                + "end_level INTEGER NOT NULL DEFAULT 0,"
                + "charged_mah REAL NOT NULL DEFAULT 0,"
                + "avg_power_w REAL NOT NULL DEFAULT 0,"
                + "peak_power_w REAL NOT NULL DEFAULT 0,"
                + "avg_temp_c REAL NOT NULL DEFAULT 0,"
                + "peak_temp_c REAL NOT NULL DEFAULT 0,"
                + "plugged INTEGER NOT NULL DEFAULT 0,"
                + "capacity_mah REAL NOT NULL DEFAULT 0,"
                + "sample_count INTEGER NOT NULL DEFAULT 0,"
                + "ongoing INTEGER NOT NULL DEFAULT 0,"
                + "updated_ts INTEGER NOT NULL DEFAULT 0"
                + ")");

        db.execSQL("CREATE TABLE " + T_PERIODS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ts INTEGER NOT NULL,"
                + "end_ts INTEGER NOT NULL,"
                + "start_level INTEGER NOT NULL DEFAULT 0,"
                + "end_level INTEGER NOT NULL DEFAULT 0,"
                + "drain_mah REAL NOT NULL DEFAULT 0,"
                + "avg_power_w REAL NOT NULL DEFAULT 0,"
                + "screen_on_ms INTEGER NOT NULL DEFAULT 0,"
                + "total_ms INTEGER NOT NULL DEFAULT 0,"
                + "view_ms INTEGER NOT NULL DEFAULT 0,"
                + "sample_count INTEGER NOT NULL DEFAULT 0,"
                + "ongoing INTEGER NOT NULL DEFAULT 0,"
                + "updated_ts INTEGER NOT NULL DEFAULT 0"
                + ")");

        // 曲线和时间轴查询全按时间范围扫，ts 上必须有索引
        db.execSQL("CREATE INDEX idx_samples_ts ON " + T_SAMPLES + "(ts)");
        db.execSQL("CREATE INDEX idx_sessions_start ON " + T_SESSIONS + "(start_ts DESC)");
        db.execSQL("CREATE INDEX idx_periods_start ON " + T_PERIODS + "(start_ts DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        LogUtils.i(TAG + " upgrade " + oldVersion + " -> " + newVersion);
        // v1 -> v2：samples 增加 power_known 列，用来区分「真 0」与「读不到/沿用值」。
        //
        // 幂等：先 PRAGMA 查一遍列是否已存在再加。onCreate 建出来的新库本来就有这一列，
        // 而升级路径里也可能因为各种原因被调用多次，直接 ALTER 会抛 duplicate column。
        if (oldVersion < 2 && !hasColumn(db, T_SAMPLES, "power_known")) {
            db.execSQL("ALTER TABLE " + T_SAMPLES
                    + " ADD COLUMN power_known INTEGER NOT NULL DEFAULT 1");
            // 历史行里的 power_w=0 分不出「真 0」还是「当时读不到」，但这次修的正是
            // 「读不到被当成 0 落库」——把它们标成未知（0）。否则用户**正在进行**的
            // 这次充电，平均值仍会被升级前已经落库的那些假 0 拖低，修了等于没修。
            // 真 0（涓流/待机时电流就是 0）被一起排除也不影响：它们本来对平均功率
            // 就没什么贡献，剔掉只会让数字更接近真实。
            db.execSQL("UPDATE " + T_SAMPLES + " SET power_known=0 WHERE power_w=0");
        }
    }

    /** 表里有没有这一列。PRAGMA table_info 是只读查询，代价可以忽略 */
    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(nameIdx))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " hasColumn failed", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    // ======================= 模型 =======================

    /** 一个采样点 */
    public static final class Sample {
        public long ts;
        public long dtMs;
        public int level;
        public int voltageMv;
        /** 带符号 mA：正=充入，负=放出 */
        public int currentMa;
        /** 带符号 W */
        public float powerW;
        /**
         * 这一拍的 current/power 是不是**本拍真读到**的。
         *
         * false = 电流/功率读不到、被区间拒掉、或充电中沿用了上次有效值。默认 true，
         * 让没有显式赋值的调用方（测试、老代码路径）保持「已知」的语义。
         * 曲线据此把非实测点桥接起来、聚合据此把它们从平均/峰值里剔除。
         */
        public boolean powerKnown = true;
        public float tempC;
        public int status;
        public int plugged;
        public boolean screenOn;
        public String fgPkg;

        /** 电流绝对值（mA），画图用 */
        public int currentAbsMa() {
            return Math.abs(currentMa);
        }

        /**
         * 是不是在充电。
         *
         * 判据只用「插着电」+「不是放电状态」，不掺电流：电流读不到的时候
         * currentMa 是 0，旧实现用 {@code currentMa > 0} 当判据，会把插着电的机器
         * 显示成「使用中」，充电期间也不去持唤醒锁，曲线自然就稀稀拉拉。
         */
        public boolean charging() {
            return plugged != 0 && status != BatteryManager.BATTERY_STATUS_DISCHARGING;
        }
    }

    /** 一次充电会话 */
    public static final class Session {
        public long id;
        public long startTs;
        public long endTs;
        public int startLevel;
        public int endLevel;
        /** 估算充入电量（mAh） */
        public float chargedMah;
        public float avgPowerW;
        public float peakPowerW;
        public float avgTempC;
        public float peakTempC;
        public int plugged;
        /** 由充入电量和电量差反推的电池设计容量（mAh），够不够样本才给值 */
        public float capacityMah;
        public int sampleCount;
        public boolean ongoing;

        /** 会话时长（毫秒）。进行中的用当前时间算，不等到结束 */
        public long durationMs(long now) {
            long end = ongoing ? now : endTs;
            return Math.max(0L, end - startTs);
        }

        /** 电量增幅（百分点） */
        public int levelGain() {
            return endLevel - startLevel;
        }

        /**
         * 平均充电速度（%/小时）。
         *
         * 用时长直接除：充电速度本来就是前段快后段慢，一个恒定的「平均速度」
         * 只是给用户一个横向比较不同记录的量纲，不必纠结它是加权还是算术平均。
         */
        public float levelPerHour(long now) {
            long ms = durationMs(now);
            if (ms < 60_000L) {
                return 0f;
            }
            return levelGain() * 3_600_000f / ms;
        }
    }

    /** 一个放电周期（拔电到下次插电） */
    public static final class Period {
        public long id;
        public long startTs;
        public long endTs;
        public int startLevel;
        public int endLevel;
        /** 估算耗电（mAh） */
        public float drainMah;
        public float avgPowerW;
        /** 亮屏时长（毫秒）。有使用情况权限时取系统的精确值，否则按采样积分 */
        public long screenOnMs;
        /** 周期总时长（毫秒） */
        public long totalMs;
        /** 亮屏期间里名义上「在使用」的时间（毫秒），用来算亮屏占比 */
        public long viewMs;
        public int sampleCount;
        public boolean ongoing;

        /** 周期时长；进行中的用当前时间 */
        public long durationMs(long now) {
            return Math.max(0L, (ongoing ? now : endTs) - startTs);
        }

        /** 电量消耗（百分点），掉电为正 */
        public int levelDrop() {
            return startLevel - endLevel;
        }

        /**
         * 待机时长（毫秒）= 总时长 - 亮屏时长。
         *
         * 不叫「深睡」：这段时间屏幕虽然灭着，但后台进程、网络同步都还在跑，
         * 叫深睡会让用户以为手机真的在睡。
         */
        public long standbyMs(long now) {
            return Math.max(0L, durationMs(now) - effectiveScreenOnMs(now));
        }

        /** 进行中的周期里，亮屏时长要按已过去的时间裁剪，不然会大于总时长 */
        private long effectiveScreenOnMs(long now) {
            return Math.min(screenOnMs, durationMs(now));
        }

        /** 每小时耗电（%/h），横向比较不同周期的耗电快慢 */
        public float levelPerHour(long now) {
            long ms = durationMs(now);
            if (ms < 60_000L) {
                return 0f;
            }
            return levelDrop() * 3_600_000f / ms;
        }

        /** 预计能用多久（毫秒）；耗电速度为 0 时返回 -1 表示算不出来 */
        public long estimateRemainMs(long now) {
            float perHour = levelPerHour(now);
            if (perHour <= 0.01f) {
                return -1L;
            }
            long endLevel = ongoing ? 0 : this.endLevel;
            return (long) (Math.max(0, endLevel - lastKnownLevel) * 3_600_000f / perHour);
        }

        /** 进行中周期最后一次采样时读到的电量，用于估算剩余可用时长 */
        public int lastKnownLevel;
    }

    /** 一个应用在某个时间窗内的用量 */
    public static final class AppUsage {
        public String pkg;
        /** 前台时长（毫秒） */
        public long foregroundMs;
        /**
         * 归因到该应用的耗电（mAh）。
         *
         * 来源是采样积分：只能归给「那一刻正在前台」的应用，后台耗电会漏掉。
         * 界面上标成「前台耗电」，不假装它是完整耗电。
         */
        public float drainMah;
        /**
         * 该应用在前台期间的**平均功率**（W，绝对值）。
         *
         * 也是采样积分来的：把「前台是这个应用」的那些采样点的 power_w 取绝对值
         * 求平均。耗电 mAh 只说明「一共吃掉了多少」，平均功率才说明「吃得有多凶」——
         * 一个挂了 8 小时但平均只有 0.3W 的聊天软件，和一个 1 小时 4W 的游戏，
         * mAh 可能差不多，但用户该关注哪个一眼就能看出来。
         */
        public float avgPowerW;

        /** 占位用的空记录，合并系统时长时给「采样期间没抓到、系统记录里有」的应用补一行 */
        public AppUsage(String pkg) {
            this.pkg = pkg;
        }

        public AppUsage(String pkg, long foregroundMs, float drainMah, float avgPowerW) {
            this.pkg = pkg;
            this.foregroundMs = foregroundMs;
            this.drainMah = drainMah;
            this.avgPowerW = avgPowerW;
        }
    }

    /**
     * 应用图标塔（「使用过程」图里那一排图标列，界面上叫「草莓塔」）的一格：
     * 某个时间桶里某个应用的前台时长。
     *
     * 时间桶是等分的：把 [fromTs, toTs] 均分成 buckets 段，第 b 桶覆盖
     * [fromTs + span*b/buckets, fromTs + span*(b+1)/buckets)。
     *
     * 聚合放在 SQL 里做（GROUP BY 桶号 + 包名），所以一次查询最多返回
     * buckets × 应用数 行，而不是把整段区间上万个采样点读进内存再筛——详情页
     * 每 5 秒重装一次，读两万行再在 Kotlin 里分组是扛不住的。
     */
    public static final class FgBucket {
        /** 桶序号，0 是最早的一段 */
        public int bucket;
        public String pkg;
        /** 这一桶里该应用在前台的总时长（毫秒） */
        public long durationMs;
    }

    /** 全局汇总，统计页顶部用 */
    public static final class Totals {
        public int sessionCount;
        public float totalChargedMah;
        public long totalChargeMs;
        public float peakPowerW;
        public float avgChargeSpeedLevelPerHour;

        public int periodCount;
        public float totalDrainMah;
        public long totalScreenOnMs;
        public long totalPeriodMs;
        public float avgPeriodLevelPerHour;
    }

    // ======================= samples =======================

    /** 插入一个采样点；主键冲突（同一毫秒两条）就忽略，返回是否写入成功 */
    public boolean insertSample(Sample s) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("ts", s.ts);
            cv.put("dt_ms", s.dtMs);
            cv.put("level", s.level);
            cv.put("voltage_mv", s.voltageMv);
            cv.put("current_ma", s.currentMa);
            cv.put("power_w", s.powerW);
            cv.put("power_known", s.powerKnown ? 1 : 0);
            cv.put("temp_c", s.tempC);
            cv.put("status", s.status);
            cv.put("plugged", s.plugged);
            cv.put("screen_on", s.screenOn ? 1 : 0);
            if (s.fgPkg != null) {
                cv.put("fg_pkg", s.fgPkg);
            }
            return db.insertWithOnConflict(T_SAMPLES, null, cv,
                    SQLiteDatabase.CONFLICT_IGNORE) != -1L;
        } catch (Throwable t) {
            LogUtils.w(TAG + " insertSample failed", t);
            return false;
        }
    }

    /** 查一段时间的采样点，按时间升序 */
    public List<Sample> querySamples(long fromTs, long toTs) {
        List<Sample> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SAMPLE_COLUMNS + " FROM " + T_SAMPLES
                            + " WHERE ts>=? AND ts<=? ORDER BY ts ASC",
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) {
                out.add(readSample(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySamples failed", t);
        }
        return out;
    }

    /**
     * 查一段时间的采样点，但先做一次等距抽稀。
     *
     * 画曲线用的：一个放电周期可能横跨两三天、上万个点，全丢给 Compose 的
     * Canvas 去画既慢又看不出区别；而统计页每几秒重装一次数据，把上万行读进
     * 内存再筛的代价就完全不一样了。
     *
     * 抽稀在 SQL 里做：先数总行数定出步长，再用 rowid 取模筛。rowid 是自增的，
     * 和插入顺序（也就是时间顺序）一致，所以取模出来的点天然是等距的。
     * 没上窗口函数是因为 minSdk 26 上的 SQLite 还没有 ROW_NUMBER（3.25 起才有）。
     *
     * 首尾两点另外补：曲线要贴着图的两边，起点和「最新值」不能因为取模被漏掉。
     */
    public List<Sample> querySamplesDownsampled(long fromTs, long toTs, int maxPoints) {
        List<Sample> out = new ArrayList<>();
        if (maxPoints <= 1 || toTs <= fromTs) {
            return out;
        }
        int total = countSamples(fromTs, toTs);
        if (total == 0) {
            return out;
        }
        // 留 2 个位置给首尾
        int step = total <= maxPoints ? 1 : (int) Math.ceil(total / (double) (maxPoints - 2));
        if (step < 1) {
            step = 1;
        }
        try {
            String where = "ts>=? AND ts<=?";
            String[] args;
            if (step > 1) {
                where += " AND (rowid % ?) = 0";
                args = new String[]{String.valueOf(fromTs), String.valueOf(toTs), String.valueOf(step)};
            } else {
                args = new String[]{String.valueOf(fromTs), String.valueOf(toTs)};
            }
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SAMPLE_COLUMNS + " FROM " + T_SAMPLES
                            + " WHERE " + where + " ORDER BY ts ASC", args);
            while (c.moveToNext()) {
                out.add(readSample(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySamplesDownsampled failed", t);
        }

        if (step > 1) {
            // 补首尾。起点被取模漏掉的话曲线左边会悬空；最新点漏掉的话图上那个
            // 「当前值」圆点会停在过去某一刻，看着像数据不更新了
            Sample first = queryBoundarySample(fromTs, toTs, true);
            Sample last = queryBoundarySample(fromTs, toTs, false);
            if (first != null && (out.isEmpty() || out.get(0).ts != first.ts)) {
                out.add(0, first);
            }
            if (last != null && (out.isEmpty() || out.get(out.size() - 1).ts != last.ts)) {
                out.add(last);
            }
        }
        return out;
    }

    /** 区间里的第一行（ascending=true）或最后一行采样 */
    private Sample queryBoundarySample(long fromTs, long toTs, boolean ascending) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SAMPLE_COLUMNS + " FROM " + T_SAMPLES + " WHERE ts>=? AND ts<=?"
                            + " ORDER BY ts " + (ascending ? "ASC" : "DESC") + " LIMIT 1",
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            Sample s = c.moveToFirst() ? readSample(c) : null;
            c.close();
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 区间内的采样点数，定了抽稀步长就用它 */
    public int countSamples(long fromTs, long toTs) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + T_SAMPLES + " WHERE ts>=? AND ts<=?",
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 最新的一个采样点，没有就返回 null。
     *
     * 界面上的「当前状态」卡用它。比「查最近一小时再取最后一个」靠谱：用户开了
     * 「仅亮屏时记录」并且手机放了两小时，最近一小时里一个点都没有，
     * 界面就会显示成「还没有采到数据」，其实数据一直在。
     */
    public Sample queryLatestSample() {
        return queryBoundarySample(0L, Long.MAX_VALUE, false);
    }

    private static Sample readSample(Cursor c) {
        Sample s = new Sample();
        s.ts = c.getLong(0);
        s.dtMs = c.getLong(1);
        s.level = c.getInt(2);
        s.voltageMv = c.getInt(3);
        s.currentMa = c.getInt(4);
        s.powerW = c.getFloat(5);
        s.powerKnown = c.getInt(6) != 0;
        s.tempC = c.getFloat(7);
        s.status = c.getInt(8);
        s.plugged = c.getInt(9);
        s.screenOn = c.getInt(10) != 0;
        s.fgPkg = c.isNull(11) ? null : c.getString(11);
        return s;
    }

    // ======================= sessions =======================

    /** 开一次充电会话，返回新行 id */
    public long beginSession(long ts, int level, int plugged) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("start_ts", ts);
            cv.put("end_ts", ts);
            cv.put("start_level", level);
            cv.put("end_level", level);
            cv.put("plugged", plugged);
            cv.put("ongoing", 1);
            cv.put("updated_ts", ts);
            return getWritableDatabase().insert(T_SESSIONS, null, cv);
        } catch (Throwable t) {
            LogUtils.w(TAG + " beginSession failed", t);
            return -1L;
        }
    }

    /**
     * 按会话区间重算汇总并写回。
     *
     * 每落一个采样点就调一次，所以它得便宜：三个 SUM/AVG 聚合，区间有索引，
     * 几千行也就毫秒级。这样进程被杀之后重启，会话的中间状态不用靠内存恢复。
     *
     * endTs 传 0 表示还在进行中，只更新累计值不封口。
     */
    public void refreshSession(long id, long startTs, long startLevel,
                               long endTs, int endLevel, int plugged, boolean ongoing) {
        try {
            long to = endTs > 0 ? endTs : System.currentTimeMillis();
            Aggregate agg = aggregate(startTs, to, true);
            float capacity = 0f;
            long deltaLevel = endLevel - startLevel;
            // 电量涨得够多才有得推：涨 1% 的时候除以 0.01，误差被放大一百倍
            if (deltaLevel >= 20 && agg.chargedMah > 0) {
                capacity = agg.chargedMah / (deltaLevel / 100f);
            }
            ContentValues cv = new ContentValues();
            cv.put("end_ts", to);
            cv.put("end_level", endLevel);
            cv.put("charged_mah", agg.chargedMah);
            cv.put("avg_power_w", agg.avgPowerW);
            cv.put("peak_power_w", agg.peakPowerW);
            cv.put("avg_temp_c", agg.avgTempC);
            cv.put("peak_temp_c", agg.peakTempC);
            cv.put("capacity_mah", capacity);
            cv.put("sample_count", agg.count);
            cv.put("plugged", plugged);
            cv.put("ongoing", ongoing ? 1 : 0);
            cv.put("updated_ts", System.currentTimeMillis());
            getWritableDatabase().update(T_SESSIONS, cv, "id=?",
                    new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " refreshSession failed", t);
        }
    }

    /**
     * 结束一次会话。
     *
     * 太短的会话（不足 SESSION_MIN_SAMPLES 个采样点）直接删掉：插上充电器几秒又拔掉
     * 会留下一条「充入 0mAh、时长 3 秒」的记录，历史列表里全是这种就没法看了。
     * 删掉之后采样点还在表里，会被下一个放电周期顺带统计进去，不会凭空消失。
     */
    public void finishSession(long id, long startTs, long endTs,
                              int startLevel, int endLevel, int plugged) {
        try {
            refreshSession(id, startTs, startLevel, endTs, endLevel, plugged, false);
            Aggregate agg = aggregate(startTs, endTs, true);
            if (agg.count < Constants.Stats.SESSION_MIN_SAMPLES) {
                getWritableDatabase().delete(T_SESSIONS, "id=?",
                        new String[]{String.valueOf(id)});
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " finishSession failed", t);
        }
    }

    /** 最近的一次会话；进行中的排在最前 */
    public Session latestSession(boolean ongoingOnly) {
        List<Session> list = querySessions(1, ongoingOnly, 0);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查会话列表。
     *
     * ongoingOnly 为 true 时只返回未封口的那些；minStartTs 大于 0 时只返回这之后开始的，
     * 给「当前周期内发生的充电」用。
     */
    public List<Session> querySessions(int limit, boolean ongoingOnly, long minStartTs) {
        List<Session> out = new ArrayList<>();
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        if (ongoingOnly) {
            where.append(" AND ongoing=1");
        }
        if (minStartTs > 0) {
            where.append(" AND start_ts>=?");
            args.add(String.valueOf(minStartTs));
        }
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SESSION_COLUMNS + " FROM " + T_SESSIONS
                            + " WHERE " + where + " ORDER BY start_ts DESC LIMIT " + limit,
                    args.isEmpty() ? null : args.toArray(new String[0]));
            while (c.moveToNext()) {
                out.add(readSession(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySessions failed", t);
        }
        return out;
    }

    /**
     * 按 id 查一条充电会话；查不到（被删了）返回 null。
     *
     * 详情页用它重新取记录，而不是把列表里那一份传下去：列表里的对象已经被
     * 双电芯倍率缩放过一次，再交给详情页统一缩放就会乘两遍，功率凭空翻四倍。
     * 一律「只认 id、回库里重取」，就不会有这种二次加工。
     */
    public Session querySessionById(long id) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SESSION_COLUMNS + " FROM " + T_SESSIONS + " WHERE id=? LIMIT 1",
                    new String[]{String.valueOf(id)});
            Session s = c.moveToFirst() ? readSession(c) : null;
            c.close();
            return s;
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySessionById failed", t);
            return null;
        }
    }

    /**
     * 某时刻之前最近一次**已结束**的充电会话。
     *
     * 给使用周期估容量用：这一轮使用周期烧的电，正是它前面那次充电充进去的，
     * 用那次的「充入电量 ÷ 电量增幅」反推容量来换算百分比耗电，比拿最近一次
     * 充电（可能是这轮之后的新充电）更贴切。
     */
    public Session querySessionEndedBefore(long ts) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SESSION_COLUMNS + " FROM " + T_SESSIONS
                            + " WHERE ongoing=0 AND end_ts<=? ORDER BY end_ts DESC LIMIT 1",
                    new String[]{String.valueOf(ts)});
            Session s = c.moveToFirst() ? readSession(c) : null;
            c.close();
            return s;
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySessionEndedBefore failed", t);
            return null;
        }
    }

    private static Session readSession(Cursor c) {
        Session s = new Session();
        s.id = c.getLong(0);
        s.startTs = c.getLong(1);
        s.endTs = c.getLong(2);
        s.startLevel = c.getInt(3);
        s.endLevel = c.getInt(4);
        s.chargedMah = c.getFloat(5);
        s.avgPowerW = c.getFloat(6);
        s.peakPowerW = c.getFloat(7);
        s.avgTempC = c.getFloat(8);
        s.peakTempC = c.getFloat(9);
        s.plugged = c.getInt(10);
        s.capacityMah = c.getFloat(11);
        s.sampleCount = c.getInt(12);
        s.ongoing = c.getInt(13) != 0;
        return s;
    }

    // ======================= periods =======================

    public long beginPeriod(long ts, int level) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("start_ts", ts);
            cv.put("end_ts", ts);
            cv.put("start_level", level);
            cv.put("end_level", level);
            cv.put("ongoing", 1);
            cv.put("updated_ts", ts);
            return getWritableDatabase().insert(T_PERIODS, null, cv);
        } catch (Throwable t) {
            LogUtils.w(TAG + " beginPeriod failed", t);
            return -1L;
        }
    }

    public void refreshPeriod(long id, long startTs, int startLevel,
                              long endTs, int endLevel, boolean ongoing) {
        try {
            long to = endTs > 0 ? endTs : System.currentTimeMillis();
            Aggregate agg = aggregate(startTs, to, false);
            ContentValues cv = new ContentValues();
            cv.put("end_ts", to);
            cv.put("end_level", endLevel);
            cv.put("drain_mah", agg.chargedMah);
            cv.put("avg_power_w", agg.avgPowerW);
            cv.put("screen_on_ms", agg.screenOnMs);
            cv.put("total_ms", Math.max(0L, to - startTs));
            cv.put("sample_count", agg.count);
            cv.put("ongoing", ongoing ? 1 : 0);
            cv.put("updated_ts", System.currentTimeMillis());
            getWritableDatabase().update(T_PERIODS, cv, "id=?",
                    new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " refreshPeriod failed", t);
        }
    }

    /** 结束周期；太短（不足 PERIOD_MIN_MS）的删掉 */
    public void finishPeriod(long id, long startTs, int startLevel,
                             long endTs, int endLevel) {
        try {
            refreshPeriod(id, startTs, startLevel, endTs, endLevel, false);
            if (endTs - startTs < Constants.Stats.PERIOD_MIN_MS) {
                getWritableDatabase().delete(T_PERIODS, "id=?",
                        new String[]{String.valueOf(id)});
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " finishPeriod failed", t);
        }
    }

    public Period latestPeriod(boolean ongoingOnly) {
        List<Period> list = queryPeriods(1, ongoingOnly);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Period> queryPeriods(int limit, boolean ongoingOnly) {
        List<Period> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + PERIOD_COLUMNS + " FROM " + T_PERIODS
                            + (ongoingOnly ? " WHERE ongoing=1" : "")
                            + " ORDER BY start_ts DESC LIMIT " + limit, null);
            while (c.moveToNext()) {
                out.add(readPeriod(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryPeriods failed", t);
        }
        return out;
    }

    /** 按 id 查一轮使用周期；查不到返回 null。详情页用，理由同 {@link #querySessionById} */
    public Period queryPeriodById(long id) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + PERIOD_COLUMNS + " FROM " + T_PERIODS + " WHERE id=? LIMIT 1",
                    new String[]{String.valueOf(id)});
            Period p = c.moveToFirst() ? readPeriod(c) : null;
            c.close();
            return p;
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryPeriodById failed", t);
            return null;
        }
    }

    private static Period readPeriod(Cursor c) {
        Period p = new Period();
        p.id = c.getLong(0);
        p.startTs = c.getLong(1);
        p.endTs = c.getLong(2);
        p.startLevel = c.getInt(3);
        p.endLevel = c.getInt(4);
        p.drainMah = c.getFloat(5);
        p.avgPowerW = c.getFloat(6);
        p.screenOnMs = c.getLong(7);
        p.totalMs = c.getLong(8);
        p.viewMs = c.getLong(9);
        p.sampleCount = c.getInt(10);
        p.ongoing = c.getInt(11) != 0;
        return p;
    }

    /** 更新亮屏时长（有使用情况权限时用系统精确值覆盖采样积分的估算） */
    public void setPeriodScreenOnMs(long periodId, long screenOnMs) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("screen_on_ms", screenOnMs);
            getWritableDatabase().update(T_PERIODS, cv, "id=?",
                    new String[]{String.valueOf(periodId)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " setPeriodScreenOnMs failed", t);
        }
    }

    // ======================= 聚合 =======================

    /** 一次聚合的结果，会话和周期共用一套字段 */
    private static final class Aggregate {
        int count;
        /** 充入为正、放出为负的积分结果（mAh） */
        float chargedMah;
        float avgPowerW;
        float peakPowerW;
        float avgTempC;
        float peakTempC;
        long screenOnMs;
    }

    /**
     * 对一段区间的采样点做积分和极值统计。
     *
     * 积分按每点自带的 dt_ms 加权，不是「平均电流 × 总时长」：采样间隔可调，
     * 而且进程被杀、系统休眠都会让实际间隔和设定值对不上，只有逐点累加才是对的。
     * 第一个点的 dt_ms 是 0，所以它不贡献电量，只贡献极值。
     *
     * charging 为 true 时只累加充入方向（current_ma &gt; 0），否则只累加放出方向。
     * 一次充电过程里偶发的负电流（瞬态）不该倒扣充入电量。
     *
     * 平均/峰值功率只统计 power_known=1 的点，平均按 dt_ms 加权：
     * 老的 AVG(power_w) 会把「读不到被落成 0」的假 0 也算进去，平均功率被系统性
     * 拉低（用户反馈 20.3W 明显低于电量上升速度隐含的 ~40W 就是这么来的）；
     * 按 dt_ms 加权则是因为采样间隔可调、进程休眠也会让实际间隔对不上设定值。
     */
    private Aggregate aggregate(long fromTs, long toTs, boolean charging) {
        Aggregate agg = new Aggregate();
        try {
            String sign = charging ? ">0" : "<0";
            String sql = "SELECT COUNT(*),"
                    + "COALESCE(SUM(CASE WHEN current_ma" + sign + " THEN current_ma*dt_ms ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN power_known=1 THEN ABS(power_w)*dt_ms ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN power_known=1 THEN dt_ms ELSE 0 END),0),"
                    + "COALESCE(MAX(CASE WHEN power_known=1 THEN ABS(power_w) ELSE 0 END),0),"
                    + "COALESCE(AVG(temp_c),0),COALESCE(MAX(temp_c),0),"
                    + "COALESCE(SUM(CASE WHEN screen_on=1 THEN dt_ms ELSE 0 END),0)"
                    + " FROM " + T_SAMPLES + " WHERE ts>=? AND ts<=?";
            Cursor c = getReadableDatabase().rawQuery(sql,
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            if (c.moveToFirst()) {
                int idx = 0;
                agg.count = c.getInt(idx++);
                // 电流单位是 mA、时间单位是 ms，除 3.6e6 得 mAh
                agg.chargedMah = (float) Math.abs(c.getDouble(idx++) / 3_600_000d);
                double powerWeighted = c.getDouble(idx++);
                long powerWeightDt = c.getLong(idx++);
                agg.avgPowerW = powerWeightDt > 0
                        ? (float) (powerWeighted / powerWeightDt)
                        : 0f;
                agg.peakPowerW = c.getFloat(idx++);
                agg.avgTempC = c.getFloat(idx++);
                agg.peakTempC = c.getFloat(idx++);
                agg.screenOnMs = c.getLong(idx);
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " aggregate failed", t);
        }
        return agg;
    }

    /**
     * 一段时间内每个应用的前台时长、归因耗电和平均功率。
     *
     * 耗电（mAh）就是「这个应用在前台的那些采样点」上电池流出的电量之和。只能抓到
     * 前台期间的耗电：后台网络、定位、推送的耗电归不到具体应用头上，它们会以
     * 「系统与后台」的形式留在总量里（总量减各应用之和）。
     *
     * 平均功率同样只算这个应用在前台的那些点。取绝对值是因为放电期间的 power_w
     * 在库里是负的（符号表示方向），而这里要的是「功率多大」。
     *
     * 平均功率从 AVG(ABS(power_w)) 改成「按 dt_ms 加权、只算 power_known=1」，
     * 口径跟会话/周期汇总（aggregate）一致——假 0 不参与，采样间隔变化也不影响。
     */
    public List<AppUsage> queryAppUsage(long fromTs, long toTs) {
        Map<String, AppUsage> map = new HashMap<>();
        try {
            String sql = "SELECT fg_pkg,"
                    + "SUM(dt_ms),"
                    + "SUM(CASE WHEN current_ma<0 THEN -current_ma*dt_ms ELSE 0 END),"
                    + "SUM(CASE WHEN power_known=1 THEN ABS(power_w)*dt_ms ELSE 0 END),"
                    + "SUM(CASE WHEN power_known=1 THEN dt_ms ELSE 0 END)"
                    + " FROM " + T_SAMPLES
                    + " WHERE ts>=? AND ts<=? AND fg_pkg IS NOT NULL AND fg_pkg<>''"
                    + " GROUP BY fg_pkg";
            Cursor c = getReadableDatabase().rawQuery(sql,
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                if (pkg == null || pkg.isEmpty()) {
                    continue;
                }
                long fgMs = c.getLong(1);
                float mah = (float) Math.abs(c.getDouble(2) / 3_600_000d);
                double powerWeighted = c.getDouble(3);
                long powerWeightDt = c.getLong(4);
                float avgPowerW = powerWeightDt > 0
                        ? (float) (powerWeighted / powerWeightDt)
                        : 0f;
                map.put(pkg, new AppUsage(pkg, fgMs, mah, avgPowerW));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryAppUsage failed", t);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 按时间桶聚合每个应用的前台时长，给「使用过程」图里的应用图标塔用。
     *
     * 权重取采样点自带的 dt_ms，而不是「一条采样点算一次」：用户把采样间隔从
     * 5 秒改成 120 秒，同一段时间的采样条数会差 24 倍，按条数计会让密集区间
     * 凭空变成高峰。dt_ms 是采样线程自己算的真实间隔，不受设置影响。
     *
     * dt_ms 为 0 的（整库第一条采样、或者刚重启过）给 1 秒兜底：不给的话
     * 那条采样对应的应用时长就是 0，塔上会整格消失，看着像丢数据。
     */
    public List<FgBucket> queryForegroundBuckets(long fromTs, long toTs, int buckets) {
        List<FgBucket> out = new ArrayList<>();
        long span = toTs - fromTs;
        if (buckets <= 0 || span <= 0L) {
            return out;
        }
        try {
            // 桶号 = (ts - fromTs) * buckets / span。三个操作数都是整型，
            // SQLite 会做整数除法（向零截断），正好落在 [0, buckets) 里。
            // 末点 ts == toTs 时算出来是 buckets，下面读出来再夹一次
            String sql = "SELECT ((ts-?) * " + buckets + ") / ? AS b, fg_pkg,"
                    + " COALESCE(SUM(CASE WHEN dt_ms>0 THEN dt_ms ELSE 1000 END),0)"
                    + " FROM " + T_SAMPLES
                    + " WHERE ts>=? AND ts<=? AND fg_pkg IS NOT NULL AND fg_pkg<>''"
                    + " GROUP BY b, fg_pkg ORDER BY b ASC";
            Cursor c = getReadableDatabase().rawQuery(sql, new String[]{
                    String.valueOf(fromTs), String.valueOf(span),
                    String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) {
                String pkg = c.getString(1);
                if (pkg == null || pkg.isEmpty()) {
                    continue;
                }
                FgBucket b = new FgBucket();
                b.bucket = Math.max(0, Math.min(buckets - 1, c.getInt(0)));
                b.pkg = pkg;
                b.durationMs = c.getLong(2);
                out.add(b);
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryForegroundBuckets failed", t);
        }
        return out;
    }

    /**
     * 全局汇总。历史记录页顶部那几个数字，全部一次查完——分开查要扫好几遍表。
     *
     * 只统计已封口的记录：进行中的那一条数值还没定型，混进来会让「平均充电时长」
     * 这种指标随当前充电过程一直往下掉。
     */
    public Totals queryTotals() {
        Totals t = new Totals();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*),COALESCE(SUM(charged_mah),0),"
                            + "COALESCE(SUM(end_ts-start_ts),0),COALESCE(MAX(peak_power_w),0)"
                            + " FROM " + T_SESSIONS + " WHERE ongoing=0", null);
            if (c.moveToFirst()) {
                t.sessionCount = c.getInt(0);
                t.totalChargedMah = c.getFloat(1);
                t.totalChargeMs = c.getLong(2);
                t.peakPowerW = c.getFloat(3);
            }
            c.close();

            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*),COALESCE(SUM(drain_mah),0),COALESCE(SUM(screen_on_ms),0),"
                            + "COALESCE(SUM(total_ms),0)"
                            + " FROM " + T_PERIODS + " WHERE ongoing=0", null);
            if (c.moveToFirst()) {
                t.periodCount = c.getInt(0);
                t.totalDrainMah = c.getFloat(1);
                t.totalScreenOnMs = c.getLong(2);
                t.totalPeriodMs = c.getLong(3);
            }
            c.close();

            if (t.totalChargeMs > 60_000L) {
                t.avgChargeSpeedLevelPerHour = 0f;
            }
            if (t.periodCount > 0 && t.totalPeriodMs > 60_000L) {
                t.avgPeriodLevelPerHour = 0f;
            }
        } catch (Throwable t2) {
            LogUtils.w(TAG + " queryTotals failed", t2);
        }
        return t;
    }

    // ======================= 维护 =======================

    /**
     * 删掉一次充电记录。
     *
     * 连汇总行和这段时间的采样点一起删。只删汇总行的话，用户会看到「记录没了，
     * 可用空间一点没变」，而且那些点还留着，以后重新扫这段时间又会算出一份来，
     * 等于删了个寂寞。
     *
     * 区间由调用方给。界面用「本行的 [start_ts, end_ts]」：充电会话和放电周期在
     * 时间上天然首尾相接、互不重叠（插电→拔电→插电），所以删掉一个区间不会误伤
     * 别的记录。进行中的记录，调用方会把终点传成当前时间，否则刷新过的那部分
     * 采样点会留下来，等于没删干净。
     */
    public boolean deleteSession(long id, long startTs, long endTs) {
        return deleteRecord(T_SESSIONS, id, startTs, endTs);
    }

    /** 删掉一轮放电周期（充电后到下次充电之间的使用情况），同上 */
    public boolean deletePeriod(long id, long startTs, long endTs) {
        return deleteRecord(T_PERIODS, id, startTs, endTs);
    }

    private boolean deleteRecord(String table, long id, long startTs, long endTs) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(table, "id=?", new String[]{String.valueOf(id)});
            // endTs 小于等于 startTs 时只删单点。绝不退化成「删全表」：
            // 那一个判断失误就会把用户几个月的记录一次性抹掉
            if (endTs > startTs) {
                db.delete(T_SAMPLES, "ts>=? AND ts<=?",
                        new String[]{String.valueOf(startTs), String.valueOf(endTs)});
            } else {
                db.delete(T_SAMPLES, "ts=?", new String[]{String.valueOf(startTs)});
            }
            return true;
        } catch (Throwable t) {
            LogUtils.w(TAG + " deleteRecord(" + table + ") failed", t);
            return false;
        }
    }

    /** 删掉过老的采样明细；会话/周期汇总行不动 */
    public int pruneSamples() {
        long cutoff = System.currentTimeMillis()
                - Constants.Stats.SAMPLE_RETENTION_DAYS * 86_400_000L;
        try {
            return getWritableDatabase().delete(T_SAMPLES, "ts<?",
                    new String[]{String.valueOf(cutoff)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " pruneSamples failed", t);
            return 0;
        }
    }

    /** 清空全部统计数据 */
    public void clearAll() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(T_SAMPLES, null, null);
            db.delete(T_SESSIONS, null, null);
            db.delete(T_PERIODS, null, null);
        } catch (Throwable t) {
            LogUtils.w(TAG + " clearAll failed", t);
        }
    }

    public int countSamples() {
        try {
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + T_SAMPLES, null);
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }
}
