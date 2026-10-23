package com.jj.nexusfloat.stats;

import android.content.Context;
import android.content.SharedPreferences;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计页要的全部数据，一次装配好。
 *
 * 界面那边只调一个 {@link #load}，跑在一个后台线程上，回来就是一整屏的数字。
 * 拆成十几个 getter 让 Compose 各自去查的话，每次重组都是一轮 SQLite，
 * 一堆卡片一起重组就是几十轮——数据库再快也架不住这么问。
 *
 * 这个类不缓存：统计页不是每秒刷新的东西，用户点进来查一次就够。缓存反而要处理
 * 「什么时候失效」，多一份状态多一处 bug。
 */
public final class StatsRepository {

    private static final String TAG = "StatsRepository";

    /** 曲线最多画多少个点 */
    private static final int CURVE_MAX_POINTS = 180;

    private StatsRepository() {}

    /** 统计页的一屏数据 */
    public static final class Dashboard {
        /** 统计功能开着没有 */
        public boolean enabled;
        /** 有没有「使用情况访问」权限；没有的话应用时长只能靠采样积分估算 */
        public boolean usagePermission;
        /** 最近一次采样 */
        public StatsStore.Sample latest;
        /** 正在进行的充电会话，没有就是 null */
        public StatsStore.Session currentSession;
        /** 正在进行的放电周期，没有就是 null */
        public StatsStore.Period currentPeriod;
        /** 本次充电的曲线（进行中就是实时曲线） */
        public List<StatsStore.Sample> chargeCurve = new ArrayList<>();
        /** 本次放电周期的电量曲线，没有进行中的周期时用最近一次 */
        public List<StatsStore.Sample> dischargeCurve = new ArrayList<>();
        /**
         * 「使用过程」图里那条应用图标塔的分桶数据（见 StatsStore.FgBucket）。
         *
         * 跟 [StatsStore#queryForegroundBuckets] 的说明一致：聚合在 SQL 里做完，
         * 这一屏最多也就 buckets × 应用数 行，不会把整段区间上万条采样点拖进来。
         */
        public List<StatsStore.FgBucket> fgBuckets = new ArrayList<>();
        /** fgBuckets 用到的桶数；0 表示没有塔可画 */
        public int towerBuckets;
        /** 当前周期（或最近一次会话）里的应用用量，已按耗电降序 */
        public List<StatsStore.AppUsage> apps = new ArrayList<>();
        /**
         * 算应用耗电百分比用的分母（mAh）。
         *
         * 取这一轮「整机耗电」而不是「各应用耗电之和」：各应用只归因了前台那部分，
         * 用它们的和当分母的话，所有百分比会加起来正好 100%，看着像「后台一点电
         * 都不耗」，那是假的。用整机耗电当分母，各应用百分比加起来不足 100%，
         * 差额就是系统与后台——跟系统设置里的电池页是一个口径。
         */
        public float appDrainBaseMah;
        /** 历史充电记录 */
        public List<StatsStore.Session> sessionHistory = new ArrayList<>();
        /** 历史使用周期 */
        public List<StatsStore.Period> periodHistory = new ArrayList<>();
        /** 全局汇总 */
        public StatsStore.Totals totals = new StatsStore.Totals();
        /** 估算出的电池容量（mAh）；0 表示还算不出来 */
        public float capacityMah;
        /** 数据库里的采样点数，用来判断「是不是刚装上还没数据」 */
        public int sampleCount;
        /**
         * 电流是从哪条路读到的（见 BatterySampler#currentSourceName），
         * 全都读不到时为 null。机型兼容差异太大，把这条露在设置卡里，
         * 出问题时不用翻 logcat 就能看出是「三条路全不通」还是「值不对」。
         */
        public String currentSource;
        /** 时间戳：本屏数据是什么时候装配的 */
        public long loadedAt;
    }

    public static Dashboard load(Context context) {
        Dashboard d = new Dashboard();
        d.loadedAt = System.currentTimeMillis();
        try {
            StatsStore store = StatsStore.get(context);
            SharedPreferences prefs = context.getSharedPreferences(
                    Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
            d.enabled = prefs.getBoolean(Constants.Stats.KEY_ENABLED, true);
            d.usagePermission = UsageStatsReader.hasPermission(context);
            d.totals = store.queryTotals();
            d.sampleCount = store.countSamples();
            d.currentSource = BatterySampler.currentSourceName();

            d.currentSession = store.latestSession(true);
            d.currentPeriod = store.latestPeriod(true);

            d.latest = store.queryLatestSample();

            // 充电曲线：优先画正在进行的那次；没有就退回最近一次
            d.sessionHistory = store.querySessions(Constants.Stats.HISTORY_LIMIT, false, 0);
            StatsStore.Session curveSession = d.currentSession != null
                    ? d.currentSession
                    : (d.sessionHistory.isEmpty() ? null : d.sessionHistory.get(0));
            if (curveSession != null) {
                long to = curveSession.ongoing ? System.currentTimeMillis() : curveSession.endTs;
                d.chargeCurve = store.querySamplesDownsampled(
                        curveSession.startTs, to, CURVE_MAX_POINTS);
                d.capacityMah = curveSession.capacityMah;
            }

            // 容量估计：优先用正在充的这次，其次是历史里最近一次反推出来的。
            // 放在应用用量之前算，因为下面要用它估「整机耗电」当百分比分母
            if (d.capacityMah <= 0f) {
                StatsStore.Session lastCharged = store.latestSession(false);
                if (lastCharged != null) {
                    d.capacityMah = lastCharged.capacityMah;
                }
            }

            // 放电曲线和应用用量：优先用进行中的周期，没有就退回最近一次
            d.periodHistory = store.queryPeriods(Constants.Stats.HISTORY_LIMIT, false);
            StatsStore.Period usePeriod = d.currentPeriod;
            if (usePeriod == null && !d.periodHistory.isEmpty()) {
                usePeriod = d.periodHistory.get(0);
            }
            if (usePeriod != null) {
                long to = usePeriod.ongoing ? System.currentTimeMillis() : usePeriod.endTs;
                d.dischargeCurve = store.querySamplesDownsampled(
                        usePeriod.startTs, to, CURVE_MAX_POINTS);
                d.apps = buildAppUsage(context, store, usePeriod.startTs, to,
                        Constants.Stats.APP_RANK_LIMIT);
                // 「使用过程」图里那条应用图标塔。区间和电量曲线完全一致，
                // 所以塔的每一列跟曲线上同一时刻是一一对应的
                d.towerBuckets = Constants.Stats.USAGE_TOWER_BUCKETS;
                d.fgBuckets = store.queryForegroundBuckets(
                        usePeriod.startTs, to, d.towerBuckets);
                // 百分比分母：整机耗电。积分值（drain_mah）和容量反推值差得远的
                // 时候取大的那个——积分在息屏采样稀疏时偏小，反推值又依赖上一次
                // 充电估出来的容量，取大至少不会让百分比虚高到 100% 以上
                float byLevel = usePeriod.levelDrop() / 100f * d.capacityMah;
                d.appDrainBaseMah = Math.max(usePeriod.drainMah, byLevel);
            }
            // 一个能用的分母都没有（比如没权限、采样也太稀）时退回「各应用耗电之和」，
            // 这样至少百分比这一列还能看出相对大小，不会整列都是 0%
            if (d.appDrainBaseMah <= 0f) {
                float sum = 0f;
                for (StatsStore.AppUsage u : d.apps) {
                    sum += u.drainMah;
                }
                d.appDrainBaseMah = sum;
            }

            // 双电芯机型最后统一缩放一次，见 dualCellFactor 的说明
            applyScale(d, prefs.getBoolean(Constants.Modules.KEY_DUAL_CELL, false)
                    ? Constants.Modules.DUAL_CELL_MULTIPLIER : 1f);
        } catch (Throwable t) {
            LogUtils.w(TAG + " load failed", t);
        }
        return d;
    }

    /**
     * 双电芯倍率：开了就是 2，没开是 1。
     *
     * 双电芯机型的 power_supply 一般只上报其中一颗电芯的读数，电流和功率都只有
     * 实际值的一半。监视条那边一直有这个开关把它乘回来（见
     * collector.PerformanceCollector#collectBatteryData），统计页原来没跟它联动，
     * 所以整页功率正好是悬浮窗的一半。
     *
     * 倍率在**读数据时**应用，不是写采样点时乘进去：一是不动历史数据，二是用户
     * 拨一下开关整页数字立刻跟着变，符合「这是个显示/校准开关」的直觉。
     * 电流、功率、毫安时同乘一个系数，所以百分比、条形图这些相对量不受影响。
     */
    public static float dualCellFactor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(Constants.Modules.KEY_DUAL_CELL, false)
                ? Constants.Modules.DUAL_CELL_MULTIPLIER : 1f;
    }

    /** 把整屏数据里所有电流 / 功率 / 毫安时相关的量按倍率缩放 */
    private static void applyScale(Dashboard d, float f) {
        if (f == 1f) {
            return;
        }
        scaleSample(d.latest, f);
        for (StatsStore.Sample s : d.chargeCurve) {
            scaleSample(s, f);
        }
        for (StatsStore.Sample s : d.dischargeCurve) {
            scaleSample(s, f);
        }
        scaleSession(d.currentSession, f);
        for (StatsStore.Session s : d.sessionHistory) {
            scaleSession(s, f);
        }
        scalePeriod(d.currentPeriod, f);
        for (StatsStore.Period p : d.periodHistory) {
            scalePeriod(p, f);
        }
        for (StatsStore.AppUsage u : d.apps) {
            u.drainMah *= f;
            u.avgPowerW *= f;
        }
        d.totals.totalChargedMah *= f;
        d.totals.peakPowerW *= f;
        d.totals.totalDrainMah *= f;
        d.appDrainBaseMah *= f;
        // 容量是「充入电量 ÷ 电量增幅」反推的，充入电量翻了倍，容量也得跟着翻，
        // 不然「电池容量约 xxx mAh」会显示成实际值的一半
        d.capacityMah *= f;
    }

    private static void scaleSample(StatsStore.Sample s, float f) {
        if (s == null) {
            return;
        }
        s.powerW *= f;
        s.currentMa = Math.round(s.currentMa * f);
    }

    private static void scaleSession(StatsStore.Session s, float f) {
        if (s == null) {
            return;
        }
        s.chargedMah *= f;
        s.avgPowerW *= f;
        s.peakPowerW *= f;
        s.capacityMah *= f;
    }

    private static void scalePeriod(StatsStore.Period p, float f) {
        if (p == null) {
            return;
        }
        p.drainMah *= f;
        p.avgPowerW *= f;
    }

    /**
     * 组装应用用量榜。
     *
     * 前台时长用系统的（有权限时）而不是采样的：系统那份是从 Activity 生命周期
     * 事件算的，精确到毫秒，采样积分只能精确到「一个采样间隔里它是不是前台」，
     * 一个 15 秒的间隔里用户切了三次应用，我们只会记到最后那个。
     *
     * 耗电没有系统数据可用，只能靠采样积分，所以还是采样的那份。
     *
     * 两份数据合并之后按耗电降序，耗电为 0 的排后面——用户看这个榜主要是想知道
     * 「谁在费我的电」，纯时长排前面的往往是微信这种一直挂着的应用。
     */
    private static List<StatsStore.AppUsage> buildAppUsage(
            Context context, StatsStore store, long from, long to, int limit) {
        Map<String, StatsStore.AppUsage> merged = new HashMap<>();

        for (StatsStore.AppUsage u : store.queryAppUsage(from, to)) {
            merged.put(u.pkg, u);
        }

        if (UsageStatsReader.hasPermission(context)) {
            UsageStatsReader.Snapshot snap = UsageStatsReader.summarize(context, from, to);
            for (Map.Entry<String, Long> e : snap.foregroundMs.entrySet()) {
                StatsStore.AppUsage u = merged.get(e.getKey());
                if (u == null) {
                    u = new StatsStore.AppUsage(e.getKey());
                    merged.put(e.getKey(), u);
                }
                u.foregroundMs = e.getValue();
            }
        }

        List<StatsStore.AppUsage> list = new ArrayList<>(merged.values());
        Collections.sort(list, new Comparator<StatsStore.AppUsage>() {
            @Override
            public int compare(StatsStore.AppUsage a, StatsStore.AppUsage b) {
                int byDrain = Float.compare(b.drainMah, a.drainMah);
                return byDrain != 0 ? byDrain : Long.compare(b.foregroundMs, a.foregroundMs);
            }
        });
        if (list.size() > limit) {
            list = new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    /** 清空全部统计数据（会话、周期、采样点） */
    public static void clearAll(Context context) {
        StatsStore.get(context).clearAll();
    }

    /**
     * 删掉一条充电记录（连同这段时间的采样点）。
     *
     * 界面把记录整行都存下来了，删的时候直接用行里的 id 和时间区间，
     * 不需要再去库里查一遍。
     *
     * 进行中的记录也能删：汇总行没了，采样线程下一拍会重新建一条，
     * 效果就是「从此刻重新开始记这一轮」。所以区间终点要用**当前时间**而不是
     * 行里那个还在不停被刷新的 end_ts，否则中间那段采样点会留下来。
     */
    public static boolean deleteSession(Context context, StatsStore.Session s) {
        if (s == null) {
            return false;
        }
        long to = s.ongoing ? System.currentTimeMillis() : s.endTs;
        return StatsStore.get(context).deleteSession(s.id, s.startTs, to);
    }

    /** 删掉一轮放电周期记录（连同这段时间的采样点）。进行中的同上，从此刻重新开始 */
    public static boolean deletePeriod(Context context, StatsStore.Period p) {
        if (p == null) {
            return false;
        }
        long to = p.ongoing ? System.currentTimeMillis() : p.endTs;
        return StatsStore.get(context).deletePeriod(p.id, p.startTs, to);
    }

    /**
     * 一次充电会话的曲线，历史记录页展开某条记录时用。
     * 明细被保留策略清掉之后会返回空列表，界面按「曲线已过期」处理。
     */
    public static List<StatsStore.Sample> loadCurve(long fromTs, long toTs, Context context) {
        return StatsStore.get(context).querySamplesDownsampled(fromTs, toTs, CURVE_MAX_POINTS);
    }

    // ======================= 详情页 =======================

    /**
     * 点进某一条充电记录之后看到的一屏数据。
     *
     * 跟 {@link Dashboard} 分开而不是复用：总览页要的是「最近一次 / 正在进行的那一次」，
     * 详情页要的是「用户点的那一条」，两者只是结构像，取数条件完全不同。
     */
    public static final class SessionDetail {
        /** 记录本身；null 表示这条记录已经不在了（比如刚在列表里删掉） */
        public StatsStore.Session session;
        /** 开始充电到结束（进行中就是到此刻）的采样曲线 */
        public List<StatsStore.Sample> curve = new ArrayList<>();
        /**
         * 这个区间里还剩多少条采样明细。
         *
         * 汇总行是永久保留的，明细只留 45 天，所以点开一条很老的记录会看到
         * 数字齐全但曲线是空的。界面用它区分「刚插上还没采到」和「明细已过期」，
         * 两种情况的提示语完全不一样。
         */
        public int sampleCount;
    }

    /** 点进某一轮使用周期之后看到的一屏数据 */
    public static final class PeriodDetail {
        public StatsStore.Period period;
        /** 耗电曲线（电量 + 功率） */
        public List<StatsStore.Sample> curve = new ArrayList<>();
        /** 这一轮里各应用的前台时长、归因耗电和平均功率 */
        public List<StatsStore.AppUsage> apps = new ArrayList<>();
        public List<StatsStore.FgBucket> fgBuckets = new ArrayList<>();
        public int buckets;
        /** 应用耗电百分比的分母（本轮整机耗电），口径同总览页 */
        public float drainBaseMah;
        /** 反推出来的电池容量，用来把「掉了几格电」换算成 mAh */
        public float capacityMah;
        public int sampleCount;
    }

    /**
     * 装配一条充电记录的详情。
     *
     * 必须是「按 id 回库里重取」，不能直接用列表里传下来的对象：列表那份已经被
     * 双电芯倍率缩放过，这里再统一缩放一次就会乘两遍（功率变成四倍）。规则很简单——
     * 进来的只有 id，所有数字都从这个方法里第一次读出来，缩放也只在这里做一次。
     */
    public static SessionDetail loadSessionDetail(Context context, long sessionId) {
        SessionDetail d = new SessionDetail();
        try {
            StatsStore store = StatsStore.get(context);
            StatsStore.Session s = store.querySessionById(sessionId);
            if (s == null) {
                return d;
            }
            d.session = s;
            long to = s.ongoing ? System.currentTimeMillis() : s.endTs;
            d.curve = store.querySamplesDownsampled(s.startTs, to, CURVE_MAX_POINTS);
            d.sampleCount = store.countSamples(s.startTs, to);
            // 充电记录页只画电量/功率/温度曲线，不叠应用图标塔：插着电的时候
            // 用户多半没在动手机，这段时间的前台应用也没有解释力

            float f = dualCellFactor(context);
            if (f != 1f) {
                scaleSession(s, f);
                for (StatsStore.Sample sm : d.curve) {
                    scaleSample(sm, f);
                }
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " loadSessionDetail failed", t);
        }
        return d;
    }

    /**
     * 装配一轮使用周期的详情。
     *
     * 百分比分母（drainBaseMah）在缩放**之前**用原始值算：三个候选量（积分耗电、
     * 电量差 × 容量、各应用耗电之和）都是同一量纲，先算完再一起乘倍率，和
     * 总览页的顺序一致。要是先缩放一部分再算，取 max 的结果会偏向缩放过的那个。
     */
    public static PeriodDetail loadPeriodDetail(Context context, long periodId) {
        PeriodDetail d = new PeriodDetail();
        try {
            StatsStore store = StatsStore.get(context);
            StatsStore.Period p = store.queryPeriodById(periodId);
            if (p == null) {
                return d;
            }
            d.period = p;
            long to = p.ongoing ? System.currentTimeMillis() : p.endTs;
            d.curve = store.querySamplesDownsampled(p.startTs, to, CURVE_MAX_POINTS);
            d.sampleCount = store.countSamples(p.startTs, to);
            d.apps = buildAppUsage(context, store, p.startTs, to,
                    Constants.Stats.APP_RANK_DETAIL_LIMIT);
            d.buckets = Constants.Stats.USAGE_TOWER_BUCKETS;
            d.fgBuckets = store.queryForegroundBuckets(p.startTs, to, d.buckets);

            // 容量优先用「这一轮开始之前那次已结束的充电」反推的值：那正是给这一轮
            // 充进去的电。历史被清空时退回最近一次充电，再没有就是 0（界面上不显示
            // 换算出来的 mAh，只显示积分值）
            StatsStore.Session prev = store.querySessionEndedBefore(p.startTs);
            if (prev == null) {
                prev = store.latestSession(false);
            }
            if (prev != null) {
                d.capacityMah = prev.capacityMah;
            }

            float byLevel = p.levelDrop() / 100f * d.capacityMah;
            d.drainBaseMah = Math.max(p.drainMah, byLevel);
            if (d.drainBaseMah <= 0f) {
                float sum = 0f;
                for (StatsStore.AppUsage u : d.apps) {
                    sum += u.drainMah;
                }
                d.drainBaseMah = sum;
            }

            float f = dualCellFactor(context);
            if (f != 1f) {
                scalePeriod(p, f);
                for (StatsStore.Sample sm : d.curve) {
                    scaleSample(sm, f);
                }
                for (StatsStore.AppUsage u : d.apps) {
                    u.drainMah *= f;
                    u.avgPowerW *= f;
                }
                d.drainBaseMah *= f;
                d.capacityMah *= f;
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " loadPeriodDetail failed", t);
        }
        return d;
    }
}
