package com.jj.nexusfloat.frame;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 帧率记录的本地存储。
 *
 * 单独一个库（nexus_frames.db）而不是塞进统计那个库：帧记录是「用户手动开一次、
 * 记一会儿」的高频采样，统计是「常年在后台默默记」的低频采样，频率差 15 倍、
 * 字段几乎不重叠。分开之后「清空统计数据」也不会误伤帧记录——用户清统计的
 * 时候大概率没想连游戏帧率历史一起没。
 *
 * 两张表：
 * - records：一次记录一行汇总（起止、应用、平均/最低/最高帧率、平均功耗温度……）
 * - samples：1 秒一个点，字段见 {@link Sample}
 *
 * 跟 StatsStore 同一套容错思路：所有 SQL 都包 try/catch，读不到就当没有，
 * 存储出问题绝不能把采样循环或者界面拖死。
 */
public final class FrameRecordStore extends SQLiteOpenHelper {

    private static final String TAG = "FrameRecordStore";

    private static final String T_RECORDS = "records";
    private static final String T_SAMPLES = "samples";

    private static volatile FrameRecordStore sInstance;

    public static FrameRecordStore get(Context context) {
        FrameRecordStore inst = sInstance;
        if (inst == null) {
            synchronized (FrameRecordStore.class) {
                inst = sInstance;
                if (inst == null) {
                    sInstance = inst = new FrameRecordStore(context.getApplicationContext());
                }
            }
        }
        return inst;
    }

    private FrameRecordStore(Context context) {
        super(context, Constants.Frame.DB_NAME, null, Constants.Frame.DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_RECORDS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ts INTEGER NOT NULL,"
                + "end_ts INTEGER NOT NULL DEFAULT 0,"
                + "ongoing INTEGER NOT NULL DEFAULT 1,"
                + "pkg TEXT,"
                + "app_label TEXT,"
                + "avg_fps REAL NOT NULL DEFAULT 0,"
                + "min_fps REAL NOT NULL DEFAULT 0,"
                + "max_fps REAL NOT NULL DEFAULT 0,"
                + "avg_power_w REAL NOT NULL DEFAULT 0,"
                + "peak_power_w REAL NOT NULL DEFAULT 0,"
                + "avg_cpu REAL NOT NULL DEFAULT 0,"
                + "avg_gpu REAL NOT NULL DEFAULT 0,"
                + "avg_cpu_temp REAL NOT NULL DEFAULT 0,"
                + "peak_cpu_temp REAL NOT NULL DEFAULT 0,"
                + "avg_gpu_temp REAL NOT NULL DEFAULT 0,"
                + "peak_gpu_temp REAL NOT NULL DEFAULT 0,"
                + "battery_start INTEGER NOT NULL DEFAULT -1,"
                + "battery_end INTEGER NOT NULL DEFAULT -1,"
                + "sample_count INTEGER NOT NULL DEFAULT 0,"
                + "remark TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_SAMPLES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "record_id INTEGER NOT NULL,"
                + "ts INTEGER NOT NULL,"
                + "fps REAL NOT NULL DEFAULT 0,"
                + "fps_src TEXT DEFAULT '',"
                + "cpu_total REAL NOT NULL DEFAULT 0,"
                + "cpu_cores TEXT,"
                + "cpu_freqs TEXT,"
                + "gpu_freq INTEGER NOT NULL DEFAULT 0,"
                + "gpu_usage INTEGER NOT NULL DEFAULT -1,"
                + "cpu_temp REAL,"
                + "gpu_temp REAL,"
                + "ram_used_mb INTEGER NOT NULL DEFAULT 0,"
                + "ram_total_mb INTEGER NOT NULL DEFAULT 0,"
                + "power_w REAL NOT NULL DEFAULT 0,"
                + "battery_pct INTEGER NOT NULL DEFAULT -1)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frame_samples_rec "
                + "ON " + T_SAMPLES + " (record_id, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 全部建表语句都带 IF NOT EXISTS，重跑一遍也无害（老版本升级时可能补表）
        onCreate(db);
        if (oldVersion < 2) {
            // 一次性修复 9.0.0.0 首版的汇总列错位：那版 refreshAggregates 的 SQL
            // 多输出了一列「有效点数」，读取端从 idx1 起整体错位，已经落库的记录
            // 汇总全是错值。v2 删掉了那列，这里把用户手机上已存的记录逐条重算一遍，
            // 把错值纠正回来。同版本号覆盖安装不清数据，所以这条迁移能真正生效。
            fixAggregateMisalignment(db);
        }
    }

    /**
     * 重算全部记录的汇总行（仅用于 v1→v2 迁移）。
     *
     * 必须复用 onUpgrade 传进来的这个可写连接，不能再调 getWritableDatabase()：
     * 那会在数据库版本升级的事务中途重入，SQLiteOpenHelper 会卡住。
     */
    private void fixAggregateMisalignment(SQLiteDatabase db) {
        List<Long> ids = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id FROM " + T_RECORDS, null);
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " fixAggregateMisalignment query failed", t);
            return;
        } finally {
            if (c != null) {
                c.close();
            }
        }
        for (long id : ids) {
            refreshAggregates(db, id);
        }
    }

    // ======================= 数据对象 =======================

    /** 一次记录（一条游戏帧率会话） */
    public static final class Record {
        public long id;
        public long startTs;
        public long endTs;
        public boolean ongoing;
        /** 记录里采样点最多的那个应用；切了应用也不打断，以此为准 */
        public String pkg;
        public String appLabel;
        /** 平均帧率，只按有画面（fps>0）的采样点算 */
        public float avgFps;
        public float minFps;
        public float maxFps;
        public float avgPowerW;
        public float peakPowerW;
        public float avgCpu;
        public float avgGpu;
        public float avgCpuTemp;
        public float peakCpuTemp;
        public float avgGpuTemp;
        public float peakGpuTemp;
        public int batteryStart;
        public int batteryEnd;
        public int sampleCount;
        public String remark;

        public long durationMs(long now) {
            return (ongoing ? now : endTs) - startTs;
        }
    }

    /** 一个采样点 */
    public static final class Sample {
        public long ts;
        public float fps;
        /** 帧率来源诊断标记，见 Constants.Fps.TAG_*；空串表示没开诊断 */
        public String fpsSrc = "";
        /** 整机 CPU 占用（%），照抄监视条口径 */
        public float cpuTotal;
        /** 各核占用（%），下标 0 是 cpu0 */
        public float[] cpuCores;
        /** 各 cluster 频率（MHz），顺序跟 policy 目录一致 */
        public int[] cpuFreqs;
        public int gpuFreqMhz;
        /** GPU 占用（0–100），读不到是 -1 */
        public int gpuUsage = -1;
        /** ℃，读不到是 NaN */
        public float cpuTemp = Float.NaN;
        public float gpuTemp = Float.NaN;
        public int ramUsedMb;
        public int ramTotalMb;
        /** 电池功率（W），带符号：充电为正、放电为负 */
        public float powerW;
        /** 电量百分比，读不到是 -1 */
        public int batteryPct = -1;
    }

    /** 「按应用筛选」列表里的一行 */
    public static final class AppEntry {
        public String pkg;
        public String label;
        public int count;
    }

    // ======================= 写入 =======================

    /** 开一次记录，返回行 id；失败返回 -1 */
    public long beginRecord(long ts, String pkg, String label, int batteryPct) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("start_ts", ts);
            cv.put("end_ts", ts);
            cv.put("ongoing", 1);
            cv.put("pkg", pkg);
            cv.put("app_label", label);
            cv.put("battery_start", batteryPct);
            return getWritableDatabase().insert(T_RECORDS, null, cv);
        } catch (Throwable t) {
            LogUtils.w(TAG + " beginRecord failed", t);
            return -1L;
        }
    }

    /** 批量落一批采样点；空列表直接返回 */
    public void insertSamples(long recordId, List<Sample> samples) {
        if (recordId <= 0 || samples == null || samples.isEmpty()) {
            return;
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (Sample s : samples) {
                    ContentValues cv = new ContentValues();
                    cv.put("record_id", recordId);
                    cv.put("ts", s.ts);
                    cv.put("fps", s.fps);
                    cv.put("fps_src", s.fpsSrc);
                    cv.put("cpu_total", s.cpuTotal);
                    cv.put("cpu_cores", joinFloats(s.cpuCores));
                    cv.put("cpu_freqs", joinInts(s.cpuFreqs));
                    cv.put("gpu_freq", s.gpuFreqMhz);
                    cv.put("gpu_usage", s.gpuUsage);
                    if (!Float.isNaN(s.cpuTemp)) {
                        cv.put("cpu_temp", s.cpuTemp);
                    }
                    if (!Float.isNaN(s.gpuTemp)) {
                        cv.put("gpu_temp", s.gpuTemp);
                    }
                    cv.put("ram_used_mb", s.ramUsedMb);
                    cv.put("ram_total_mb", s.ramTotalMb);
                    cv.put("power_w", s.powerW);
                    cv.put("battery_pct", s.batteryPct);
                    db.insertWithOnConflict(T_SAMPLES, null, cv,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " insertSamples failed", t);
        }
    }

    /**
     * 收尾一次记录：按采样点重算汇总并封口。
     *
     * 汇总放 SQL 里算（跟 StatsStore#refreshSession 一个思路）：进程什么时候死
     * 都能从采样点把汇总重推出来，内存里那点累计值只是个加速缓存。
     */
    public void finishRecord(long recordId, long endTs, int batteryPct) {
        if (recordId <= 0) {
            return;
        }
        try {
            refreshAggregates(recordId);
            ContentValues cv = new ContentValues();
            cv.put("end_ts", endTs);
            cv.put("ongoing", 0);
            if (batteryPct >= 0) {
                cv.put("battery_end", batteryPct);
            }
            getWritableDatabase().update(T_RECORDS, cv, "id=?",
                    new String[]{String.valueOf(recordId)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " finishRecord failed", t);
        }
    }

    /**
     * 收掉所有 ongoing=1 的孤儿记录。
     *
     * 记录中途进程被杀（后台被 ROM 清了、手机没电了）时来不及收尾，那一行会一直
     * 挂着「进行中」。下次打开记录页 / 起服务时跑一遍：结束时间取最后一个采样点，
     * 汇总按采样点重算。样本太少的（低于 RECORD_MIN_SAMPLES）直接删——那一两秒
     * 是误触，留下来只会污染列表。
     *
     * skipRecordId 是**正在进行**的那条记录的 id（采样器还在往里写），必须跳过：
     * 不跳的话页面一刷新就把正在记的这条收了口，采样线程却还在往里塞点，
     * 数据从此对不上。
     */
    public void finalizeOrphans(long skipRecordId) {
        try {
            List<Long> orphans = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id FROM " + T_RECORDS + " WHERE ongoing=1", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (id != skipRecordId) {
                    orphans.add(id);
                }
            }
            c.close();
            for (long id : orphans) {
                int n = countSamples(id);
                if (n < Constants.Frame.RECORD_MIN_SAMPLES) {
                    deleteRecord(id);
                    continue;
                }
                long last = queryLastTs(id);
                finishRecord(id, last > 0 ? last : System.currentTimeMillis(), -1);
                LogUtils.i(TAG + " finalized orphan record " + id);
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " finalizeOrphans failed", t);
        }
    }

    public void setRemark(long recordId, String remark) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("remark", remark == null ? "" : remark.trim());
            getWritableDatabase().update(T_RECORDS, cv, "id=?",
                    new String[]{String.valueOf(recordId)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " setRemark failed", t);
        }
    }

    /** 删一条记录连同它的采样点；返回是否删了东西 */
    public boolean deleteRecord(long recordId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(T_SAMPLES, "record_id=?",
                        new String[]{String.valueOf(recordId)});
                int n = db.delete(T_RECORDS, "id=?",
                        new String[]{String.valueOf(recordId)});
                db.setTransactionSuccessful();
                return n > 0;
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " deleteRecord failed", t);
            return false;
        }
    }

    /** 批量删除；返回删掉的记录条数 */
    public int deleteRecords(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (long id : ids) {
            if (deleteRecord(id)) {
                n++;
            }
        }
        return n;
    }

    /** 清空全部帧记录；返回删掉的记录条数 */
    public int deleteAll() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                int n = db.delete(T_RECORDS, null, null);
                db.delete(T_SAMPLES, null, null);
                db.setTransactionSuccessful();
                return n;
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " deleteAll failed", t);
            return 0;
        }
    }

    /** 清理超过保留期的采样明细；记录头永久保留（同统计的做法） */
    public void pruneOldSamples() {
        long cutoff = System.currentTimeMillis()
                - Constants.Frame.SAMPLE_RETENTION_DAYS * 24L * 3600L * 1000L;
        try {
            getWritableDatabase().delete(T_SAMPLES, "ts<?",
                    new String[]{String.valueOf(cutoff)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " pruneOldSamples failed", t);
        }
    }

    // ======================= 查询 =======================

    /** 记录列表，新的在前 */
    public List<Record> queryRecords(int limit, String filterPkg) {
        List<Record> out = new ArrayList<>();
        try {
            String where = filterPkg == null ? "" : " WHERE pkg=?";
            String[] args = filterPkg == null ? null : new String[]{filterPkg};
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + RECORD_COLS + " FROM " + T_RECORDS + where
                            + " ORDER BY start_ts DESC LIMIT " + Math.max(1, limit),
                    args);
            while (c.moveToNext()) {
                out.add(readRecord(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryRecords failed", t);
        }
        return out;
    }

    public Record queryRecordById(long id) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + RECORD_COLS + " FROM " + T_RECORDS + " WHERE id=?",
                    new String[]{String.valueOf(id)});
            Record r = c.moveToFirst() ? readRecord(c) : null;
            c.close();
            return r;
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryRecordById failed", t);
            return null;
        }
    }

    private static final String RECORD_COLS =
            "id,start_ts,end_ts,ongoing,pkg,app_label,avg_fps,min_fps,max_fps,"
                    + "avg_power_w,peak_power_w,avg_cpu,avg_gpu,avg_cpu_temp,peak_cpu_temp,"
                    + "avg_gpu_temp,peak_gpu_temp,battery_start,battery_end,sample_count,remark";

    private static Record readRecord(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(0);
        r.startTs = c.getLong(1);
        r.endTs = c.getLong(2);
        r.ongoing = c.getInt(3) != 0;
        r.pkg = c.isNull(4) ? null : c.getString(4);
        r.appLabel = c.isNull(5) ? null : c.getString(5);
        r.avgFps = c.getFloat(6);
        r.minFps = c.getFloat(7);
        r.maxFps = c.getFloat(8);
        r.avgPowerW = c.getFloat(9);
        r.peakPowerW = c.getFloat(10);
        r.avgCpu = c.getFloat(11);
        r.avgGpu = c.getFloat(12);
        r.avgCpuTemp = c.getFloat(13);
        r.peakCpuTemp = c.getFloat(14);
        r.avgGpuTemp = c.getFloat(15);
        r.peakGpuTemp = c.getFloat(16);
        r.batteryStart = c.getInt(17);
        r.batteryEnd = c.getInt(18);
        r.sampleCount = c.getInt(19);
        r.remark = c.isNull(20) ? "" : c.getString(20);
        return r;
    }

    /** 一条记录的全部采样点，时间升序 */
    public List<Sample> querySamples(long recordId) {
        List<Sample> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SAMPLE_COLS + " FROM " + T_SAMPLES
                            + " WHERE record_id=? ORDER BY ts ASC",
                    new String[]{String.valueOf(recordId)});
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
     * 采样点抽稀查询。一小时 3600 个点、三小时就是一万多，全读进内存再交给
     * Canvas 既慢又看不出区别。做法跟 StatsStore 一致：COUNT 定步长、rowid 取模，
     * 首尾另外补上（曲线要贴着图的两边）。
     */
    public List<Sample> querySamplesDownsampled(long recordId, int maxPoints) {
        List<Sample> out = new ArrayList<>();
        int total = countSamples(recordId);
        if (total == 0) {
            return out;
        }
        int step = total <= maxPoints ? 1 : (int) Math.ceil(total / (double) maxPoints);
        try {
            String sql;
            String[] args;
            if (step > 1) {
                sql = "SELECT " + SAMPLE_COLS + " FROM " + T_SAMPLES
                        + " WHERE record_id=? AND (rowid % ?)=0 ORDER BY ts ASC";
                args = new String[]{String.valueOf(recordId), String.valueOf(step)};
            } else {
                sql = "SELECT " + SAMPLE_COLS + " FROM " + T_SAMPLES
                        + " WHERE record_id=? ORDER BY ts ASC";
                args = new String[]{String.valueOf(recordId)};
            }
            Cursor c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) {
                out.add(readSample(c));
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " querySamplesDownsampled failed", t);
        }
        if (step > 1) {
            Sample first = queryBoundary(recordId, true);
            Sample last = queryBoundary(recordId, false);
            if (first != null && (out.isEmpty() || out.get(0).ts != first.ts)) {
                out.add(0, first);
            }
            if (last != null && (out.isEmpty()
                    || out.get(out.size() - 1).ts != last.ts)) {
                out.add(last);
            }
        }
        return out;
    }

    private Sample queryBoundary(long recordId, boolean ascending) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT " + SAMPLE_COLS + " FROM " + T_SAMPLES
                            + " WHERE record_id=? ORDER BY ts " + (ascending ? "ASC" : "DESC")
                            + " LIMIT 1",
                    new String[]{String.valueOf(recordId)});
            Sample s = c.moveToFirst() ? readSample(c) : null;
            c.close();
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String SAMPLE_COLS =
            "ts,fps,fps_src,cpu_total,cpu_cores,cpu_freqs,gpu_freq,gpu_usage,"
                    + "cpu_temp,gpu_temp,ram_used_mb,ram_total_mb,power_w,battery_pct";

    private static Sample readSample(Cursor c) {
        Sample s = new Sample();
        s.ts = c.getLong(0);
        s.fps = c.getFloat(1);
        s.fpsSrc = c.isNull(2) ? "" : c.getString(2);
        s.cpuTotal = c.getFloat(3);
        s.cpuCores = parseFloats(c.getString(4));
        s.cpuFreqs = parseInts(c.getString(5));
        s.gpuFreqMhz = c.getInt(6);
        s.gpuUsage = c.getInt(7);
        s.cpuTemp = c.isNull(8) ? Float.NaN : c.getFloat(8);
        s.gpuTemp = c.isNull(9) ? Float.NaN : c.getFloat(9);
        s.ramUsedMb = c.getInt(10);
        s.ramTotalMb = c.getInt(11);
        s.powerW = c.getFloat(12);
        s.batteryPct = c.getInt(13);
        return s;
    }

    public int countSamples(long recordId) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + T_SAMPLES + " WHERE record_id=?",
                    new String[]{String.valueOf(recordId)});
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 全库采样点总数，记录页底部展示用 */
    public int countAllSamples() {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + T_SAMPLES, null);
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记录条数 */
    public int countRecords() {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + T_RECORDS, null);
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 有记录的应用列表（按记录数降序），给「按应用筛选」用 */
    public List<AppEntry> queryAppEntries() {
        List<AppEntry> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT pkg, MAX(app_label) AS label, COUNT(*) AS n FROM " + T_RECORDS
                            + " WHERE pkg IS NOT NULL AND pkg<>'' GROUP BY pkg ORDER BY n DESC",
                    null);
            while (c.moveToNext()) {
                AppEntry e = new AppEntry();
                e.pkg = c.getString(0);
                e.label = c.isNull(1) ? e.pkg : c.getString(1);
                e.count = c.getInt(2);
                out.add(e);
            }
            c.close();
        } catch (Throwable t) {
            LogUtils.w(TAG + " queryAppEntries failed", t);
        }
        return out;
    }

    // ======================= 内部 =======================

    /**
     * 按采样点重算一条记录的汇总行。
     *
     * 收尾时调一次；「进行中」的记录在页面刷新时也会调——汇总行平时不动，
     * 列表上的平均帧率才有实时数字。
     *
     * 平均帧率只按有画面（fps&gt;0）的点算：游戏停在暂停界面/读条时帧率就是 0，
     * 把这些 0 也平均进去会把「游戏本身的帧率」稀释掉；最低帧率同理。
     * fps=-1 表示「这一拍没读到」（与真 0 区分，见 FrameSampler#resolveFps），
     * CASE WHEN fps&gt;0 会把它和 0 一起排除。
     * 温度列存的是「读到的才写」，NULL 表示这一拍没读到，聚合里会被 AVG/MAX 自然跳过。
     */
    public void refreshAggregates(long recordId) {
        try {
            refreshAggregates(getWritableDatabase(), recordId);
        } catch (Throwable t) {
            LogUtils.w(TAG + " refreshAggregates failed", t);
        }
    }

    /**
     * 用调用方提供的可写连接重算一条记录的汇总行。
     *
     * 平时走 {@link #refreshAggregates(long)}（内部自取连接）；v1→v2 迁移时
     * onUpgrade 已经在数据库升级事务里持有一个连接，直接复用，不再重入取连接。
     *
     * SQL 输出 12 列，读取端按下标一一对应，务必同步改：
     * <pre>
     *   idx0  COUNT(*)                                    → sample_count
     *   idx1  AVG(CASE WHEN fps>0 THEN fps END)           → avg_fps
     *   idx2  MIN(CASE WHEN fps>0 THEN fps END)           → min_fps
     *   idx3  MAX(fps)                                    → max_fps
     *   idx4  AVG(ABS(power_w))                           → avg_power_w
     *   idx5  MAX(ABS(power_w))                           → peak_power_w
     *   idx6  AVG(cpu_total)                              → avg_cpu
     *   idx7  AVG(CASE WHEN gpu_usage>=0 THEN gpu_usage)  → avg_gpu
     *   idx8  AVG(cpu_temp)                               → avg_cpu_temp
     *   idx9  MAX(cpu_temp)                               → peak_cpu_temp
     *   idx10 AVG(gpu_temp)                               → avg_gpu_temp
     *   idx11 MAX(gpu_temp)                               → peak_gpu_temp
     * </pre>
     * （v1 曾在这 12 列前多插了一列「有效点数 SUM(...)」，导致读取端整体错位，
     * 已在 v2 删掉；上面这张表就是修完后的正确对应。）
     */
    private void refreshAggregates(SQLiteDatabase db, long recordId) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT COUNT(*),"                                                  // idx0
                            + "COALESCE(AVG(CASE WHEN fps>0 THEN fps END),0),"         // idx1
                            + "COALESCE(MIN(CASE WHEN fps>0 THEN fps END),0),"         // idx2
                            + "COALESCE(MAX(fps),0),"                                   // idx3
                            + "COALESCE(AVG(ABS(power_w)),0),"                          // idx4
                            + "COALESCE(MAX(ABS(power_w)),0),"                          // idx5
                            + "COALESCE(AVG(cpu_total),0),"                             // idx6
                            + "COALESCE(AVG(CASE WHEN gpu_usage>=0 THEN gpu_usage END),0)," // idx7
                            + "COALESCE(AVG(cpu_temp),0),"                              // idx8
                            + "COALESCE(MAX(cpu_temp),0),"                              // idx9
                            + "COALESCE(AVG(gpu_temp),0),"                              // idx10
                            + "COALESCE(MAX(gpu_temp),0)"                               // idx11
                            + " FROM " + T_SAMPLES + " WHERE record_id=?",
                    new String[]{String.valueOf(recordId)});
            ContentValues cv = new ContentValues();
            if (c.moveToFirst()) {
                int i = 0;
                cv.put("sample_count", c.getInt(i++));     // idx0
                cv.put("avg_fps", c.getFloat(i++));         // idx1
                cv.put("min_fps", c.getFloat(i++));         // idx2
                cv.put("max_fps", c.getFloat(i++));         // idx3
                cv.put("avg_power_w", c.getFloat(i++));     // idx4
                cv.put("peak_power_w", c.getFloat(i++));    // idx5
                cv.put("avg_cpu", c.getFloat(i++));         // idx6
                cv.put("avg_gpu", c.getFloat(i++));         // idx7
                cv.put("avg_cpu_temp", c.getFloat(i++));    // idx8
                cv.put("peak_cpu_temp", c.getFloat(i++));   // idx9
                cv.put("avg_gpu_temp", c.getFloat(i++));    // idx10
                cv.put("peak_gpu_temp", c.getFloat(i++));   // idx11
            } else {
                cv.put("sample_count", 0);
            }
            db.update(T_RECORDS, cv, "id=?", new String[]{String.valueOf(recordId)});
        } catch (Throwable t) {
            LogUtils.w(TAG + " refreshAggregates failed", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private long queryLastTs(long recordId) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT MAX(ts) FROM " + T_SAMPLES + " WHERE record_id=?",
                    new String[]{String.valueOf(recordId)});
            long ts = c.moveToFirst() ? c.getLong(0) : 0L;
            c.close();
            return ts;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String joinFloats(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Math.round(v[i] * 10f) / 10f);
        }
        return sb.toString();
    }

    private static String joinInts(int[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    private static float[] parseFloats(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            String[] parts = raw.split(",");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i]);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] parseInts(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            String[] parts = raw.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i]);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
