package com.jj.nexusfloat.constant;

/**
 * 全局常量。按职责拆成几个子类，免得魔法字符串散得到处都是。
 *
 * 包结构（现在这样就行，不用动）：
 * xposed — LSPosed 入口，注入 SystemUI
 * bridge — 模块进程和 SystemUI 之间的 Remote / 反色桥接
 * collector — 性能数据采集（SystemUI 内的 + 模块 root 跑 GPU 的）
 * service — 广播和拉起采集线程
 * ui / ui.view — 调试页和状态栏监视器视图
 * utils — 反射、su、LogUtils 日志
 */
public final class Constants {

    public static final String TAG = "NexusFloat";

    private Constants() {}

    /** 时间间隔、阈值之类的运行参数 */
    public static final class Config {
        /**
         * 输出不输出调试日志（logcat 标签是 Constants.TAG，同时写进 LSPosed 模块日志）。
         *
         * 发布版是 false；排查问题时改成 true 重新编译安装就行。
         * v1.6.8~v1.6.11 的 debug 版开过它，用来查澎湃 OS 4 上监视器不显示、
         * 设置不生效的问题，现在定位修好了，就关回去。
         */
        public static final boolean LOG_ENABLED = false;

        /** 数据采集和 UI 刷新间隔（毫秒）的默认值 */
        public static final int UPDATE_INTERVAL_MS = 1000;
        /**
         * 采集周期的可调范围（毫秒）和步长。
         *
         * 下限 500ms：再快也没意义——TimeStats 差分本身就要求约 700ms 的跨度
         * （SF_TIMESTATS_MIN_SPAN_NS）才算出稳定帧率，周期短于它只会让一半的拍次空转，
         * 白耗电。
         *
         * 上限 2000ms：再慢监视条就跟不上画面变化了，数值看着像卡住。
         */
        public static final int UPDATE_INTERVAL_MIN_MS = 500;
        public static final int UPDATE_INTERVAL_MAX_MS = 2000;
        public static final int UPDATE_INTERVAL_STEP_MS = 100;
        /** su 命令超时（毫秒） */
        public static final int EXEC_TIMEOUT_MS = 8000;
        /** 等 XposedService 绑定时，每多少拍打一条 warn 日志 */
        public static final int SERVICE_BIND_WARN_EVERY_TICKS = 5;
        /** FPS 显示：低于这个值保留一位小数，否则显示整数 */
        public static final float FPS_INTEGER_THRESHOLD = 100f;
        /** 还没写进 Remote 时的占位标记 */
        public static final int UNPUBLISHED = Integer.MIN_VALUE;

        /** sysfs 节点全表扫连续没命中多少次就转退避 */
        public static final int SYSFS_MISS_GRACE = 3;
        /**
         * 转退避之后，每隔多少轮重扫一次候选表。
         *
         * v1.8.8 从 60 降到 5：60 拍退避意味着锁屏期间（measured_fps 全 0、
         * vsync 归零）fpsMiss 一路涨，亮屏后还得等最多一分钟的退避窗口才重扫恢复——
         * 用户实测报的就是「亮屏后 FPS 半分钟不更新」。
         * 5 拍（约 5 秒）省电够了，亮屏后也能很快恢复。
         */
        public static final int SYSFS_RESCAN_EVERY_TICKS = 5;

        private Config() {}
    }

    /** libxposed RemotePreferences / openRemoteFile 用的键 */
    public static final class Remote {
        public static final String PREFS_NAME = "perf";
        public static final String KEY_GPU_FREQ_MHZ = "gpu_freq_mhz";
        public static final String KEY_GPU_USAGE_PERCENT = "gpu_usage_pct";
        /** 竖屏显不显示监视器悬浮窗 */
        public static final String KEY_SHOW_PORTRAIT = "show_portrait";
        /** 横屏显不显示监视器悬浮窗 */
        public static final String KEY_SHOW_LANDSCAPE = "show_landscape";
        /**
         * 息屏（锁屏/灭屏）时还显不显示监视条（v1.8.10）。
         * 默认 false——息屏后屏幕本来就看不到东西，隐藏了还顺带停采集省电。
         */
        public static final String KEY_SHOW_SCREEN_OFF = "show_screen_off";
        public static final String KEY_UPDATED_AT = "updated_at";
        /** openRemoteFile 备用通道（跟 RemotePreferences 双写） */
        public static final String GPU_FREQ_FILE = "gpu_freq.txt";

        private Remote() {}
    }

    /**
     * CPU 温度（v1.8.0 加的）。
     *
     * Android 把所有温度源都挂在 /sys/class/thermal/thermal_zoneN 下面，
     * 编号随机型完全不同，唯一靠谱的判别方式是读每个 zone 的 type 文件，
     * 找含 "cpu" 的那一项（高通是 cpu0-1-usr / cpu_thermal，联发科是 mtktscpu 一类）。
     * 找到就把对应 zone 的 temp 路径缓存下来，之后每拍只读这一个文件。
     */
    public static final class Thermal {
        /** thermal zone 的 type 文件通配 */
        public static final String TYPE_GLOB = "/sys/class/thermal/thermal_zone*/type";
        /** type 值里含这个字样（转小写比较）就当 CPU 温度源 */
        public static final String TYPE_HINT = "cpu";
        /** temp 文件单位是千分之一摄氏度，除以 1000 得 ℃ */
        public static final int MILLI_DIVISOR = 1000;
        /** 合理温度下限，低于它当解析错误 */
        public static final float TEMP_MIN_C = 0f;
        /** 合理温度上限，芯片结温不会超 150℃ */
        public static final float TEMP_MAX_C = 150f;

        private Thermal() {}
    }

    /**
     * 监视条上各指标模块的开关。
     * KEYS 的顺序就是数组下标顺序，也是 App 界面的行顺序，App 端和 SystemUI 端共用，
     * 两边必须保持一致。
     *
     * CPU频率 / GPU频率 是 CPU / GPU 的子项：父项关了子项一起隐藏，父子关系见 PARENT。
     */
    public static final class Modules {
        public static final int IDX_CLOCK = 0;
        public static final int IDX_CLOCK_24H = 1;
        public static final int IDX_CPU = 2;
        public static final int IDX_CPU_FREQ = 3;
        public static final int IDX_GPU = 4;
        public static final int IDX_GPU_FREQ = 5;
        public static final int IDX_RAM = 6;
        public static final int IDX_ZRAM = 7;
        public static final int IDX_TEMP = 8;
        public static final int IDX_POWER = 9;
        public static final int IDX_CURRENT = 10;
        public static final int IDX_FPS = 11;
        /** v1.8.0 加的：CPU 温度（thermal_zone），追加到末尾，好让老下标不变 */
        public static final int IDX_CPU_TEMP = 12;

        /**
         * RemotePreferences 的存储键，下标跟上面的 IDX_* 一一对应。
         *
         * 开关是按键名读写而不是按下标，所以在中间插新项不会让已经存下的设置错位；
         * 但 IDX_* 常量得跟着挪。
         */
        public static final String[] KEYS = {
                "mod_clock",
                "mod_clock_24h",
                "mod_cpu",
                "mod_cpu_freq",
                "mod_gpu",
                "mod_gpu_freq",
                "mod_ram",
                "mod_zram",
                "mod_temp",
                "mod_power",
                "mod_current",
                "mod_fps",
                "mod_cpu_temp",
        };

        /** App 界面显示的标题，下标跟 KEYS 对应 */
        public static final String[] LABELS = {
                "时间",
                "24 小时制",
                "CPU",
                "CPU频率",
                "GPU",
                "GPU频率",
                "RAM",
                "ZRAM",
                "温度",
                "功率",
                "电流",
                "FPS",
                "CPU温度",
        };

        /**
         * 监视条上显示在数值前面的默认名称，下标跟 KEYS 对应。
         *
         * 默认取单个字母是为了省横向空间，用户可以在 App 里改成任意文字
         * （见 nameKey(int)），比如把 C 改成 CPU。
         *
         * 时间、CPU频率、GPU频率 是空串：频率是子项，数值显示在父项括号里；
         * 时间本身已经是 12:06 这种自解释的形式，前面再加个字母纯占地方。
         * hasName(int) 靠这个判断该模块支不支持自定义名称。
         */
        public static final String[] DEFAULT_NAMES = {
                "",
                "",
                "C",
                "",
                "G",
                "",
                "R",
                "Z",
                "T",
                "P",
                "I",
                "F",
                "CT",
        };

        /** 父模块下标，-1 就是顶层。父项一关，子项强制隐藏 */
        public static final int[] PARENT = {
                -1,
                IDX_CLOCK,
                -1,
                IDX_CPU,
                -1,
                IDX_GPU,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
        };

        public static final int COUNT = KEYS.length;

        /**
         * 显示顺序（v1.8.0 加的）：顶层模块在监视条上怎么排，元素是 IDX_*。
         *
         * 只排顶层模块——CPU频率 / GPU频率 / 24小时制是子项，跟着父模块走。
         * 存成逗号分隔的 IDX 列表，缺项 / 重复 / 非法值一律回退到默认顺序。
         */
        public static final String KEY_MODULE_ORDER = "module_order";

        /** 默认显示顺序：时间、CPU、GPU、RAM、ZRAM、温度、功率、电流、FPS、CPU温度 */
        public static final int[] DEFAULT_ORDER = {
                IDX_CLOCK, IDX_CPU, IDX_GPU, IDX_RAM, IDX_ZRAM,
                IDX_TEMP, IDX_POWER, IDX_CURRENT, IDX_FPS, IDX_CPU_TEMP,
        };

        /**
         * 判断这个数组是不是合法的顶层模块顺序：正好包含全部顶层 IDX、没有重复。
         * 混进了子项（PARENT != -1）就算非法。
         */
        public static boolean isValidOrder(int[] order) {
            if (order == null || order.length != DEFAULT_ORDER.length) {
                return false;
            }
            boolean[] seen = new boolean[COUNT];
            for (int idx : order) {
                if (idx < 0 || idx >= COUNT || PARENT[idx] != -1 || seen[idx]) {
                    return false;
                }
                seen[idx] = true;
            }
            return true;
        }

        /**
         * 自定义名称的存储键前缀。完整键名由 nameKey(int) 拼出来，
         * 比如 CPU 的名称存在 name_mod_cpu 下。
         *
         * 拼在模块键名后面，而不是另开一张下标表：模块键名本身就是稳定标识，
         * 加模块的时候不用再同步维护第二份顺序。
         */
        public static final String NAME_KEY_PREFIX = "name_";

        /** 自定义名称的长度上限。太长了监视条会把状态栏占满，反而没意义 */
        public static final int NAME_MAX_LEN = 6;

        /** 第 index 个模块自定义名称存在哪个键下 */
        public static String nameKey(int index) {
            return NAME_KEY_PREFIX + KEYS[index];
        }

        /**
         * 这个模块在监视条上有没有独立的名称位。
         *
         * 时间、CPU频率、GPU频率 没有：频率的数值显示在父项括号里；
         * 时间本身就是自解释的 12:06，加个前缀只是占地方。
         */
        public static boolean hasName(int index) {
            return !DEFAULT_NAMES[index].isEmpty();
        }

        /**
         * 时间显示的格式。24 小时制是 13:06 这样，12 小时制是 1:06 这样。
         *
         * 12 小时制不加 AM/PM：状态栏本来就窄，而且是上午还是下午用户自己清楚。
         * 用 h 不用 hh 是为了省一位——单数字小时前面补个 0，在监视条上只显得局促。
         */
        public static final String CLOCK_PATTERN_24H = "HH:mm";
        public static final String CLOCK_PATTERN_12H = "h:mm";
        /** 时间列的宽度探针，取两位小时里最宽的情况 */
        public static final String CLOCK_PROBE = "23:59";

        /**
         * 「背景」开关：给监视条整体加一层圆角矩形灰底，免得白背景下看不清字。
         * 它不算指标模块，所以单开一个键；背景宽度由监视条自身宽度决定，
         * 各项指标增减时会自动伸缩。
         */
        public static final String KEY_BACKGROUND = "mod_background";
        public static final String LABEL_BACKGROUND = "背景";

        /**
         * 「自动反色」开关：字色跟着背景明暗自动取反。
         *
         * 挂在「背景」下面当子项，但它对两种情形都有用：开了背景就按背景色决定字色，
         * 没开背景就按状态栏后面的壁纸/应用色决定。判定用的是 SystemUI 自己算好的
         * 浅色状态栏标志（APPEARANCE_LIGHT_STATUS_BARS），不去采样像素——
         * 采样要抓屏，代价和权限都不划算。
         */
        public static final String KEY_AUTO_CONTRAST = "auto_contrast";
        public static final String LABEL_AUTO_CONTRAST = "自动反色";

        /**
         * 「字体颜色」：从 Ui.TEXT_COLORS 里选一个下标。
         *
         * 跟「自动反色」互斥——后者开了字色由背景明暗决定，这里选的颜色会被覆盖，
         * 所以界面上开了自动反色就把这一项置灰。
         */
        public static final String KEY_TEXT_COLOR = "text_color";
        public static final String LABEL_TEXT_COLOR = "字体颜色";

        /**
         * 「刷新时间」：采集和显示的周期，单位毫秒。
         *
         * 存毫秒不存秒：秒是浮点，跨进程传和比较都要处理精度；
         * 界面上按「x.x 秒」显示，换算只在显示层做。
         */
        public static final String KEY_UPDATE_INTERVAL = "update_interval_ms";
        public static final String LABEL_UPDATE_INTERVAL = "刷新时间";

        /**
         * 监视条整体位置微调（像素偏移）。
         *
         * 水平正值往右、垂直正值往下。默认 0，就是紧贴屏幕顶端居中。
         *
         * 用像素不用 dp：这是「对着自己的屏幕挪到合适位置」的操作，用户看到的就是
         * 像素级效果，换算成 dp 反而让步长在不同 DPI 上表现不一致。
         */
        public static final String KEY_OFFSET_X = "offset_x";
        public static final String KEY_OFFSET_Y = "offset_y";
        public static final String LABEL_OFFSET_X = "水平位置";
        public static final String LABEL_OFFSET_Y = "垂直位置";
        /**
         * 偏移的可调范围（像素）和步长。
         *
         * ±400px 够把监视条从顶部居中挪到屏幕任一角落附近；再大就整条移出可视区了，
         * 那就不叫「微调」了。步长 2px 精度和手感兼顾。
         */
        public static final int OFFSET_MIN_PX = -400;
        public static final int OFFSET_MAX_PX = 400;
        public static final int OFFSET_STEP_PX = 2;

        /**
         * CPU / GPU 柱状图开关：在数值旁边画一根占用率竖条。
         *
         * 只给 CPU 和 GPU：这两项是 0–100 的占用率，柱高有明确含义。
         * 频率、温度、功率没有固定量程，画成柱子只会误导。
         *
         * 作为 CPU / GPU 的子项挂在各自下面，父项关掉柱子一起消失。
         */
        public static final String KEY_CPU_BAR = "cpu_bar";
        public static final String LABEL_CPU_BAR = "柱状图";
        public static final String KEY_GPU_BAR = "gpu_bar";
        public static final String LABEL_GPU_BAR = "柱状图";

        /**
         * 「双电芯」开关：开了之后功率和电流都乘 2。
         *
         * 双电芯机型两颗电芯分别上报，power_supply 一般只给其中一颗的读数，
         * 所以功率和电流都只有实际值的一半。是不是双电芯没法从节点可靠判断
         * （有些 ROM 已经合并过了，翻倍反而错），就交给用户按机型自己决定。
         */
        public static final String KEY_DUAL_CELL = "dual_cell";
        public static final String LABEL_DUAL_CELL = "双电芯";
        /** 双电芯开着的时候功率和电流的倍率 */
        public static final float DUAL_CELL_MULTIPLIER = 2f;

        /** 字体大小（sp），App 界面上用加减按钮调 */
        public static final String KEY_FONT_SIZE = "font_size_sp";
        public static final String LABEL_FONT_SIZE = "字体大小";

        /** 字体加不加粗（v1.8.9），App 界面上用加减按钮调 */
        public static final String KEY_FONT_BOLD = "font_bold";
        public static final String LABEL_FONT_BOLD = "字体粗细";

        /**
         * 监视项目之间的间隔（dp），App 界面上用加减按钮调。
         *
         * 原先间隔恒等于 Ui.DIVIDER_WIDTH_DP，而且跟着字号等比缩放，字号一变疏密
         * 就跟着变，没法单独调。现在改成独立设定值，仍然按字号缩放，
         * 保证大字号下间隔不会显得太窄。
         */
        public static final String KEY_SPACING = "spacing_dp";
        public static final String LABEL_SPACING = "项目间隔";

        /**
         * 「仅在选定应用显示」开关。
         *
         * 关着（默认）的时候监视条在所有界面都显示。开了之后只有 KEY_APP_WHITELIST
         * 里的应用在前台才显示，其余时间连采集线程一起停掉，不耗电。
         */
        public static final String KEY_APP_FILTER = "app_filter";
        public static final String LABEL_APP_FILTER = "仅在选定应用显示";

        /**
         * 白名单包名集合，用 WHITELIST_SEPARATOR 分隔存成一个字符串。
         *
         * 不用 putStringSet：libxposed 的 RemotePreferences 跨进程传 Set 没传字符串稳，
         * 而包名本身不含分隔符，拼起来是安全的。
         */
        public static final String KEY_APP_WHITELIST = "app_whitelist";
        public static final String WHITELIST_SEPARATOR = "\n";

        /**
         * FPS 取数方案（v1.6.2 起改成逐个来源开关）。
         *
         * 旧版是「自动 / 高通 / 联发科」三选一，每个预设背后是一条写死的尝试链。
         * 问题是链条没法微调：某个来源在某机型上出数慢或者不准，用户只能整条换掉。
         * 现在六个来源各给一个开关，按 KEYS 的顺序依次试，关掉的直接跳过。
         *
         * 顺序就是优先级，不能随便调——面板节点排第一是因为它给的是瞬时值、
         * 没有平均窗口，高通机型上实测最准也最快；联发科没这个节点，
         * 自然会落到后面的应用级来源。
         *
         * @see #PRESETS
         */
        public static final class FpsSource {
            /** 面板实测帧率：高通 measured_fps，瞬时值没平均窗口 */
            public static final int IDX_PANEL = 0;
            /** SurfaceFlinger --latency 逐 layer 帧时间戳 */
            public static final int IDX_SF_LATENCY = 1;
            /** SurfaceFlinger TimeStats 帧数增量 */
            public static final int IDX_SF_TIMESTATS = 2;
            /** 联发科 FPSGO 逐进程帧率 */
            public static final int IDX_FPSGO = 3;
            /** 联发科 GED KPI 逐进程帧率 */
            public static final int IDX_GED = 4;
            /** Choreographer 计帧——实际是屏幕刷新率，不是应用帧率 */
            public static final int IDX_VSYNC = 5;

            /** 存储键，下标跟 IDX_* 一一对应 */
            public static final String[] KEYS = {
                    "fps_src_panel",
                    "fps_src_sf_latency",
                    "fps_src_sf_timestats",
                    "fps_src_fpsgo",
                    "fps_src_ged",
                    "fps_src_vsync",
            };

            /** 界面上显示的名称，用各来源的本名，不写代号 */
            public static final String[] LABELS = {
                    "面板 measured_fps",
                    "SurfaceFlinger latency",
                    "SurfaceFlinger TimeStats",
                    "FPSGO",
                    "GED KPI",
                    "Choreographer",
            };

            /**
             * 各来源在监视条上对应的诊断字母，跟 Fps.TAG_* 保持一致。
             * 界面上标在名称旁边，用户看到 60t 就知道该关哪一个。
             */
            public static final String[] TAGS = {
                    Fps.TAG_PANEL,
                    Fps.TAG_SF_BINDER + "/" + Fps.TAG_SF_ROOT + "/" + Fps.TAG_SF_DISPLAY,
                    Fps.TAG_SF_TIMESTATS,
                    Fps.TAG_FPSGO,
                    Fps.TAG_GED,
                    Fps.TAG_VSYNC,
            };

            /** 每个来源的适用范围和响应特性，界面上当副标题用 */
            public static final String[] DESCRIPTIONS = {
                    "高通专有，瞬时值、无延迟；联发科没有此节点",
                    "仅 Android 13 及更早有效，1 秒平均窗口",
                    "Android 14+ 的正解，需两次采样，约 1 秒延迟",
                    "联发科内核统计，多数天玑机型节点不存在",
                    "联发科 GED，FPSGO 缺失时的替代",
                    "屏幕刷新率，不是应用帧率；仅作最后兜底",
            };

            public static final int COUNT = KEYS.length;

            /**
             * 预设：一次点选就把开关填成常用组合，下标跟 PRESET_LABELS 对应。
             *
             * 留预设是因为多数用户不知道自己该开哪几个；但预设只是往开关里写值，
             * 写完照样能逐个微调，不像旧版那样锁死一条链。
             */
            public static final boolean[][] PRESETS = {
                    // 全部：与旧版「自动」等价，逐级兜底到屏幕刷新率
                    {true, true, true, true, true, true},
                    // 高通：面板优先，TimeStats 兜底，不退回刷新率
                    {true, false, true, false, false, false},
                    // 联发科：只走应用级来源，不读面板、不退回刷新率
                    {false, true, true, true, true, false},
            };
            public static final String[] PRESET_LABELS = {"全部", "高通", "联发科"};

            /**
             * 迁移标记。旧版存的是单个 fps_source 整数，升级后要按它推导出等价的
             * 开关组合，否则用户原本选好的方案会被重置成「全部」。
             */
            public static final String KEY_MIGRATED = "fps_src_migrated";

            private FpsSource() {}
        }

        /** 「FPS 来源」分区标题 */
        public static final String LABEL_FPS_SOURCE_LIST = "FPS 来源";

        /**
         * 「SurfaceFlinger 优先 root」开关。
         *
         * 关着（默认）的时候走 IBinder.dump()——Binder 调用不创建进程；
         * 开了之后先试 su 里的 dumpsys，每次取数多一次 fork+exec。
         *
         * 旧版的「联发科」方案强制开这一项，是 v1.5.6 为绕 SELinux 加的；
         * 后来证明真正修好天玑 9400 的是 TimeStats 而不是 root，所以改成默认关，
         * 只在 Binder 通道确实被拦的时候才需要手动开。
         */
        public static final String KEY_SF_PREFER_ROOT = "sf_prefer_root";
        public static final String LABEL_SF_PREFER_ROOT = "SurfaceFlinger 走 root";

        /**
         * 旧版的 FPS 方案键，只给 v1.6.2 的迁移逻辑读，不再写。
         *
         * 故意不加 @Deprecated：迁移代码必须读它，加了注解只会在每次编译时刷两条警告，
         * 而这个键迁移做完之后就自然没人碰了。
         */
        public static final String KEY_FPS_SOURCE = "fps_source";
        public static final int FPS_SOURCE_AUTO = 0;
        public static final int FPS_SOURCE_PANEL = 1;
        public static final int FPS_SOURCE_APP = 2;

        /**
         * 「FPS 诊断」开关：开了之后 FPS 数值前面加一个来源标记，
         * 用来确认当前到底取到了哪一级数据。见 Fps.TAG_SF 那几个。
         */
        public static final String KEY_FPS_DEBUG = "fps_debug";
        public static final String LABEL_FPS_DEBUG = "FPS 诊断";

        /**
         * App 界面的主题：浅色 / 深色 / 跟随系统。
         *
         * 只影响 App 自己的设置界面，跟监视条的字色没关系——后者由「背景」和
         * 「自动反色」决定，取的是状态栏后面的实际明暗，跟 App 主题无关。
         *
         * 存本地 SharedPreferences 就行，不必进 RemotePreferences：SystemUI 那边
         * 用不到。但为了省一套读写路径，还是走跟其他项一样的双写。
         */
        public static final String KEY_APP_THEME = "app_theme";
        public static final int APP_THEME_SYSTEM = 0;
        public static final int APP_THEME_LIGHT = 1;
        public static final int APP_THEME_DARK = 2;
        /** 三个选项的按钮文字，下标就是取值 */
        public static final String[] APP_THEME_LABELS = {"跟随系统", "浅色", "深色"};

        /**
         * 软件界面壁纸（v1.8.5 加的）：
         * 存用户自选图片在 filesDir 里的文件名；空串就是不用壁纸。
         * 配合「液态玻璃」材质——壁纸当背景，卡片做半透明玻璃效果。
         */
        public static final String KEY_UI_WALLPAPER = "ui_wallpaper";

        /**
         * 监视条空格自定义（v1.8.7 改版）：
         * 存「项目名+空格数」的逗号分隔串，比如 "ZRAM2,CPU1" 表示 ZRAM 前加 2 个空格、
         * CPU 前加 1 个空格。项目名用监视项目页的显示名（CPU / GPU / RAM / ZRAM /
         * 温度 / 功率…），不区分大小写。默认空串（监视条全紧贴，没空格）。
         */
        public static final String KEY_SPACE_BEFORE = "space_before";

        /**
         * 解析空格自定义串，返回每个模块前的空格数（下标跟 KEYS 对应）。
         * 非法项（名字匹配不上、空格数不是正整数）静默跳过；空串返回全 0。
         */
        public static int[] parseSpaceBefore(String raw) {
            int[] out = new int[COUNT];
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            for (String part : raw.split(",")) {
                String item = part.trim();
                if (item.isEmpty()) {
                    continue;
                }
                // 从末尾往前找数字部分：ZRAM2 → name=ZRAM, count=2
                int splitAt = item.length();
                while (splitAt > 0 && Character.isDigit(item.charAt(splitAt - 1))) {
                    splitAt--;
                }
                String name = item.substring(0, splitAt).trim();
                String numStr = item.substring(splitAt).trim();
                if (name.isEmpty() || numStr.isEmpty()) {
                    continue;
                }
                int count;
                try {
                    count = Integer.parseInt(numStr);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (count <= 0 || count > 9) {
                    continue; // 空格数限 1–9，防手滑
                }
                for (int i = 0; i < LABELS.length; i++) {
                    if (PARENT[i] == -1 && LABELS[i].equalsIgnoreCase(name)) {
                        out[i] = count;
                        break;
                    }
                }
            }
            return out;
        }

        private Modules() {}
    }

    /** 作用域相关包名 */
    public static final class Package {
        public static final String SYSTEM_UI = "com.android.systemui";
        public static final String MODULE = "com.jj.nexusfloat";

        private Package() {}
    }

    /** Manifest 里的组件全限定名 */
    public static final class Component {
        /** EarlyInitProvider 的 authority，外部 query 能拉起模块进程 */
        public static final String EARLY_INIT_AUTHORITY =
                Package.MODULE + ".earlyinit";

        /**
         * 借 EARLY_INIT_AUTHORITY 的 query 路径下发指令。
         *
         * SystemUI 需要在监视条隐藏时让模块进程停掉 root GPU 采集，但 RemotePreferences
         * 只能模块进程写、SystemUI 读，方向正好反着。复用现有的 Provider 传一个路径段就行，
         * 不用加组件也不用广播。
         */
        public static final String PATH_COLLECT_RESUME = "collect/resume";
        public static final String PATH_COLLECT_PAUSE = "collect/pause";

        /**
         * 设置快照的 query 路径。
         *
         * 为什么需要它（v1.6.10）
         * libxposed 的 RemotePreferences 要求模块自己的进程也被框架注入——XposedServiceHelper
         * 拿到的 service 就是那时候建立的。实测澎湃 OS 4 上 LSPosed 只把模块注入了
         * com.android.systemui，模块自己的进程没有（作用域表里就一条记录，日志里
         * (com.jj.nexusfloat) 零匹配）。于是 App 侧 getXposedService() 恒为 null：
         * 界面显示「LSP 未连接」；
         * 写设置时只落到本地 SharedPreferences，Remote 那半段被跳过，SystemUI 轮询 Remote
         * 永远读到空——所有开关都不生效；
         * GPU 采集第一行就因 service==null 返回，数据从不更新。
         * 这条路径让 SystemUI 反过来 query 模块进程，直接取本地 SharedPreferences 的快照。
         * 只要模块装着就能用，跟注不注入没关系。
         */
        public static final String PATH_PREFS = "prefs";
        /** 快照 Cursor 的列名：键、类型、值 */
        public static final String COL_KEY = "k";
        public static final String COL_TYPE = "t";
        public static final String COL_VALUE = "v";
        /** 值类型标记，用来把字符串还原成原本的类型 */
        public static final String TYPE_BOOL = "b";
        public static final String TYPE_INT = "i";
        public static final String TYPE_LONG = "l";
        public static final String TYPE_FLOAT = "f";
        public static final String TYPE_STRING = "s";

        /**
         * Settings.Global 里存设置快照的键名。
         *
         * 为什么还要第三条通道（v1.6.11）
         * 前两条都有前提：RemotePreferences 要模块自己的进程被注入（澎湃 OS 4 上不满足）；
         * ContentProvider 要 SystemUI 能跨应用 query 模块进程，这一步可能被 SELinux、
         * 包可见性或者进程没拉起来挡住，而且失败原因很难一个个排除。
         * Settings.Global 没这些前提：App 侧有 root，用 settings put global 写；
         * SystemUI 是 system uid，直接 Settings.Global.getString 读，不经任何跨应用调用。
         * 只要 root 在就通。
         * 值是 Base64 编码的 key=type:value 行集合。用 Base64 不用明文：设置里有中文
         * 自定义名称和可能的引号，直接拼进 shell 命令会被转义规则坑掉；Base64 之后
         * 只剩 ASCII 字母数字和 +/=。
         */
        public static final String SETTINGS_KEY = "nexusfloat_prefs";
        /** 快照文本里的行分隔符和键值分隔符 */
        public static final char SNAPSHOT_LINE_SEP = '\n';
        public static final char SNAPSHOT_KV_SEP = '=';
        public static final char SNAPSHOT_TYPE_SEP = ':';
        /**
         * settings put global 的超时（毫秒）。
         *
         * 比读 sysfs 的默认超时宽得多：settings 是个 shell 脚本，要起一个
         * app_process 再走 binder 调用，几百毫秒是常态。
         */
        public static final long SETTINGS_WRITE_TIMEOUT_MS = 4000L;

        private Component() {}
    }

    /**
     * SystemUI 反射 / Hook 用的类名、方法名、布局 id。
     *
     * 覆盖 Android 12 ~ 17。折叠状态栏 Fragment 的类名在版本之间改过好多次
     * （见 FRAGMENT_CANDIDATES），所以一律按候选列表逐个试，
     * 另外还有一条不依赖这个 Fragment 的兜底注入路径。
     */
    public static final class SystemUi {
        public static final String FRAGMENT_COLLAPSED_PKG =
                "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment";
        public static final String FRAGMENT_COLLAPSED_LEGACY =
                "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment";

        /**
         * 折叠状态栏 Fragment 的候选类名，新的排前面。
         *
         * Android 16 起 AOSP 把 CollapsedStatusBarFragment 重构改名成了
         * HomeStatusBarFragment（同包），后来又下沉到 ui.fragment 子包。旧类名在新系统上
         * 直接 ClassNotFound——这就是 v1.5.4 及以前在安卓 17 上「监视器完全不出现」的原因：
         * 两个候选都没命中，注入代码一次都没跑。
         */
        public static final String[] FRAGMENT_CANDIDATES = {
                "com.android.systemui.statusbar.phone.fragment.HomeStatusBarFragment",
                "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                "com.android.systemui.statusbar.phone.ui.fragment.HomeStatusBarFragment",
                "com.android.systemui.statusbar.phone.HomeStatusBarFragment",
                "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment",
                // Android 17：状态栏改用 Compose 重写了，Fragment 又挪了一层包
                "com.android.systemui.statusbar.phone.fragment.ui.HomeStatusBarFragment",
                "com.android.systemui.statusbar.core.HomeStatusBarFragment",
                "com.android.systemui.statusbar.home.HomeStatusBarFragment",
        };

        /**
         * SystemUI 的 Application 类，本来想当个跟状态栏布局无关的兜底注入锚点。
         *
         * 澎湃 OS 4 上这个类根本不存在（实测 probe class ... -> false），MIUI 换掉了
         * Application 的类名。所以 v1.6.8 起不再依赖它，改成 hook framework 侧的
         * Instrumentation 和 Application——见下面两组常量。这里留着只是为了兼容和诊断。
         */
        public static final String CLASS_SYSTEMUI_APPLICATION =
                "com.android.systemui.SystemUIApplication";
        public static final String METHOD_ON_CREATE = "onCreate";

        /**
         * framework 侧的兜底锚点，全在 bootclasspath 里，任何 ClassLoader 都解析得了，
         * 所以不受 ROM 改类名影响。
         *
         * Instrumentation.callApplicationOnCreate(Application) 是 framework 调用应用
         * Application.onCreate 的统一入口，参数就是 Application 实例，不需要知道它的
         * 具体类名；Application.attach(Context) 是更早的时机，给个别绕过 Instrumentation
         * 标准路径的 ROM 用。
         */
        public static final String CLASS_INSTRUMENTATION = "android.app.Instrumentation";
        public static final String CLASS_APPLICATION = "android.app.Application";
        public static final String METHOD_CALL_APP_ON_CREATE = "callApplicationOnCreate";
        public static final String METHOD_ATTACH = "attach";

        /**
         * 兜底注入的另一个入口：Hook 装上时 SystemUI 的 Application 可能已经创建完了，
         * onCreate 再也不会被调用，这时候只能主动去取。
         */
        public static final String CLASS_ACTIVITY_THREAD = "android.app.ActivityThread";
        public static final String METHOD_CURRENT_APPLICATION = "currentApplication";

        /**
         * 轮询 currentApplication() 的次数和间隔。
         *
         * onPackageReady 是在 handleBindApplication 内部执行的，这会儿 Application 还没创建，
         * 必须等它返回之后才取得到。post 一次通常就够，但 Application 什么时候创建没法预知，
         * 多试几次代价极低。
         */
        public static final int APP_POLL_RETRIES = 40;
        public static final long APP_POLL_INTERVAL_MS = 250L;
        /**
         * Application 兜底注入的首次延迟（毫秒）。
         *
         * onCreate 的时候 WindowManagerService 往往还没准备好接受 SystemUI 的新窗口，
         * 加窗会抛 BadTokenException，所以得等。
         */
        public static final long FALLBACK_INJECT_DELAY_MS = 8000L;
        /** 兜底注入的重试间隔和次数：开机早期加窗失败是常态，得多试几次 */
        public static final long FALLBACK_INJECT_RETRY_MS = 5000L;
        /**
         * 兜底注入重试次数。
         *
         * v1.6.6 是 6 次，配上 8 秒首次延迟 + 5 秒间隔，总共只覆盖开机后约 38 秒。
         * 安卓 17 上 SystemUI 起得更慢（Compose 重写后初始化链更长），加窗在这个窗口里
         * 可能一次都不成功，之后就再没人重试了——表现就是监视器完全不出现。
         * 提到 20 次，覆盖到约 100 秒；每次失败只是抛一次 addView 异常，代价极低。
         */
        public static final int FALLBACK_INJECT_RETRIES = 20;
        /**
         * 密集重试用完之后，长期探测的间隔（毫秒）。
         *
         * 不彻底放弃：安卓 17 上 SystemUI 可能开机很久之后才具备加窗条件，也可能中途
         * 被系统重启过。彻底放弃就意味着用户得手动重启 SystemUI 才能恢复，
         * 而每 30 秒一次 addView 尝试的代价可以忽略。
         */
        public static final long FALLBACK_INJECT_IDLE_MS = 30000L;

        public static final String CLASS_DEPENDENCY = "com.android.systemui.Dependency";
        public static final String CLASS_DARK_DISPATCHER_POLICY =
                "com.android.systemui.statusbar.policy.DarkIconDispatcher";
        public static final String CLASS_DARK_RECEIVER_POLICY =
                CLASS_DARK_DISPATCHER_POLICY + "$DarkReceiver";
        public static final String CLASS_DARK_DISPATCHER_IMPL =
                "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl";
        public static final String CLASS_STATUS_BAR_ICON_VIEW =
                "com.android.systemui.statusbar.StatusBarIconView";

        public static final String METHOD_DEPENDENCY_GET = "get";
        public static final String METHOD_ON_VIEW_CREATED = "onViewCreated";
        public static final String METHOD_ON_DESTROY_VIEW = "onDestroyView";
        public static final String METHOD_ON_DARK_CHANGED = "onDarkChanged";
        public static final String METHOD_APPLY_ICON_TINT = "applyIconTint";
        public static final String METHOD_APPLY_DARK_INTENSITY = "applyDarkIntensity";
        public static final String METHOD_ADD_DARK_RECEIVER = "addDarkReceiver";
        public static final String METHOD_REMOVE_DARK_RECEIVER = "removeDarkReceiver";
        public static final String METHOD_APPLY_DARK = "applyDark";

        /** status_bar 根容器，监视器就注入到这里 */
        public static final String ID_STATUS_BAR = "status_bar";
        /** 采样已有图标 tint 时的候选 id */
        public static final String[] ID_TINT_REFERENCE = {
                "statusIcons", "system_icon_area", "battery", "clock"
        };

        /** DarkIconDispatcherImpl 上图标颜色的字段（MIUI 可能混淆了命名） */
        public static final String[] FIELD_ICON_TINT = {"mIconTint", "iconTint"};

        private SystemUi() {}
    }

    /** CPU 频率 sysfs（SystemUI 进程能直接读 scaling_cur_freq） */
    public static final class Cpu {
        public static final String DEVICE_DIR = "/sys/devices/system/cpu";
        public static final String CPUFREQ_DIR = DEVICE_DIR + "/cpufreq";
        public static final String SCALING_CUR_FREQ = "scaling_cur_freq";
        /** sysfs 里的值是 kHz，除以这个系数得 MHz 整数 */
        public static final int KHZ_TO_MHZ = 1000;

        private Cpu() {}
    }

    /**
     * GPU sysfs 节点：高通 KGSL、联发科 GED / Mali devfreq、通用 devfreq。
     *
     * 单位不再按路径硬编码。各家节点分别用 MHz / KHz / Hz，而这三个量级在真实 GPU
     * 频率下互不重叠，所以交给 SysfsReader 按数值量级自动判定，省得每来一个新平台
     * 就维护一张平行的单位表。
     *
     * 路径里允许出现 * 通配：root 通道由 shell 展开，直读通道由 SysfsReader 自己展开。
     */
    public static final class Gpu {
        public static final String KGSL_DEVICE = "/sys/class/kgsl/kgsl-3d0";
        public static final String GED_HAL = "/sys/kernel/ged/hal";
        public static final String GED_HAL_DEBUG = "/sys/kernel/debug/ged/hal";

        /** 合理的 GPU 频率区间（MHz），用来剔掉档位号之类的无效字段 */
        public static final int FREQ_MIN_MHZ = 50;
        public static final int FREQ_MAX_MHZ = 3000;

        /**
         * GPU 频率节点，按优先级排。
         *
         * 高通 KGSL：clock_mhz 给 MHz，devfreq/cur_freq 和 cur_gpu_clock_freq 给 Hz。
         *
         * 联发科 GED：current_freqency 的格式是「档位 频率KHz」。这个拼写错误来自
         * MTK 内核源码，别「修正」成 frequency，改了就读不到。
         *
         * Mali devfreq：目录名随平台变（比如 13000000.mali），所以用通配匹配。
         */
        public static final String[] FREQ_PATHS = {
                KGSL_DEVICE + "/clock_mhz",
                KGSL_DEVICE + "/devfreq/cur_freq",
                KGSL_DEVICE + "/cur_gpu_clock_freq",
                "/sys/class/devfreq/kgsl-3d0/cur_freq",
                GED_HAL + "/current_freqency",
                GED_HAL_DEBUG + "/current_freqency",
                "/sys/kernel/gpu/gpu_clock",
                "/sys/class/devfreq/*mali*/cur_freq",
                "/sys/class/devfreq/gpufreq/cur_freq",
                "/sys/devices/platform/*mali*/devfreq/*/cur_freq",
        };

        /**
         * GPU 占用节点，按优先级排；统一取第一个落在 0–100 的整数字段。
         *
         * gpu_busy_percentage — 高通，格式「N 100」
         * gpu_load            — 高通老驱动，整数百分比
         * gpu_utilization     — 联发科，格式「loading blocked idle」
         * gpu_loading         — 联发科 ged 模块参数，整数百分比
         */
        public static final String[] BUSY_PATHS = {
                KGSL_DEVICE + "/gpu_busy_percentage",
                KGSL_DEVICE + "/gpu_load",
                GED_HAL + "/gpu_utilization",
                GED_HAL_DEBUG + "/gpu_utilization",
                "/sys/kernel/gpu/gpu_busy",
                "/sys/module/ged/parameters/gpu_loading",
                "/sys/devices/platform/*mali*/utilization",
                "/proc/mali/utilization",
        };

        private Gpu() {}
    }

    /**
     * FPS 数据源。
     *
     * 四级来源，准确度依次递减：
     * 1. SurfaceFlinger 帧时间戳（SF_ARG_LATENCY）——按 layer 取，也就是按窗口取，
     *    天然对准前台应用，而且跟芯片厂商无关。主力来源。
     * 2. 联发科 FPSGO fstb（FPSGO_PATHS）——内核按进程统计的 queue buffer 帧率。
     *    挂载点随机型变，有些 ROM 已经关掉了。
     * 3. 高通面板实测帧率（SYSFS_PATHS）——面板实际刷出的帧数，静止时会掉下来，
     *    近似可用。联发科没有对应节点。
     * 4. Choreographer 计帧——见 READ_FAILED 的说明，实测的是屏幕刷新率而不是应用帧率，
     *    只当最后兜底。
     */
    public static final class Fps {
        public static final float MAX_VALID = 1000f;
        /**
         * 高通面板帧率候选路径（v1.8.4 恢复成 v1.7.6 实测可用的那版）。
         *
         * v1.7.6-debug 在澎湃 / ColorOS 的骁龙 8 Gen 3 / 8 Elite 上面板 p 是正常的。
         * 之后的 v1.7.9（缓存保留 + 删 direct 通道）和 v1.8.x（多轮改写）都回归了。
         * 这版恢复 v1.7.6 的完整取数链。
         *
         * 高通每个 crtc 都挂 measured_fps，主屏那个才是实际读数，其余恒 0。
         * 骁龙 8 Gen 3 / 8 Elite 上主屏 crtc 的父目录是「外部 crtc」，实测路径长这样
         * /sys/class/drm/card0-sde-crtc-2/device/card0-sde-crtc-0/measured_fps，
         * 所以候选表里必须有深路径通配。
         */
        public static final String[] SYSFS_PATHS = {
                // 一条通配覆盖 sde-crtc-*、card*-DSI-*，以及以后可能出现的连接器命名
                "/sys/class/drm/*/measured_fps",
                "/sys/class/graphics/*/measured_fps",
                "/sys/class/drm/res_info_fps",
                // SoC 平台目录：drm 下面一般是 card0-sde-crtc-N
                "/sys/devices/platform/soc/*/drm/*/measured_fps",
                // 骁龙 8 Gen 3 / 8 Elite 实测：主屏 crtc 的父目录是「外部 crtc」，
                // 比如 /sys/class/drm/card0-sde-crtc-2/device/card0-sde-crtc-0/measured_fps
                "/sys/class/drm/*/device/*/measured_fps",
                "/sys/class/drm/*/device/*/panel/measured_fps",
                "/sys/devices/platform/soc/*/drm/*/*/measured_fps",
                "/sys/devices/platform/soc/*/drm/*/device/*/measured_fps",
                "/sys/devices/platform/soc/*/drm/*/device/*/panel/measured_fps",
                // 部分 ROM 把面板帧率放在 panel 子目录或者换了字段名
                "/sys/class/drm/*/panel/measured_fps",
                "/sys/class/drm/*/fps",
                // debugfs 位置：user 版一般挂不上，但有 root 的时候能读
                "/sys/kernel/debug/dri/0/measured_fps",
                "/sys/kernel/debug/dri/*/measured_fps",
                Gpu.GED_HAL + "/fps",
                Gpu.GED_HAL_DEBUG + "/fps",
        };

        /**
         * vsync 时间戳候选路径（v1.7.2 起，v1.8.4 跟着面板方案一起恢复）。
         *
         * 跟 measured_fps 一一对应，用的是同一组通配。
         * 主屏 crtc 的 vsync_event 是 kernel 写进去的 vsync 中断时间戳
         * （VSYNC=<nanos>），闲置 crtc 恒为 0。两个时间戳一差分就是帧率，
         * 跟 measured_fps 完全独立。
         */
        public static final String[] VSYNC_PATHS = {
                "/sys/class/drm/*/vsync_event",
                "/sys/class/drm/*/device/*/vsync_event",
                "/sys/class/drm/*/device/*/panel/vsync_event",
                "/sys/devices/platform/soc/*/drm/*/vsync_event",
                "/sys/devices/platform/soc/*/drm/*/*/vsync_event",
                "/sys/devices/platform/soc/*/drm/*/device/*/vsync_event",
                "/sys/devices/platform/soc/*/drm/*/device/*/panel/vsync_event",
                "/sys/class/drm/*/panel/vsync_event",
                "/sys/kernel/debug/dri/0/vsync_event",
                "/sys/kernel/debug/dri/*/vsync_event",
        };

        /**
         * vsync_event 差分的最小时间间隔（纳秒）。
         * 相当于「大于 240fps 的信号一律当不可信」——240 是消费级面板的物理上限。
         */
        public static final long VSYNC_MIN_DELTA_NS = 4_200_000L; // 1/240s ≈ 4.17ms

        /**
         * measured_fps 报告里判「活动 vs 静止」的阈值（v1.7.2 起）。
         *
         * 高通节点格式是 "fps: 22.7 duration:1000000 frame_count:23"。
         * frame_count 是这个采样窗口里实际提交的帧数：0 是真静止，
         * 大于等于 FPS_ACTIVE_MIN_FRAMES 才算「真有面板在跑」。
         * 锁屏/息屏态的 fps: 0.7 frame_count: 1（一秒一帧的伪活动）会被排掉。
         */
        public static final int FPS_ACTIVE_MIN_FRAMES = 2;

        /**
         * measured_fps 报告里三个字段的解析结果。
         *
         * 高通节点实际格式是 "fps: 22.7 duration:1000000 frame_count:23"，
         * 三个字段都得看。只看 fps 会被锁屏态的 0.7 frame_count: 1 骗到。
         * isActive 由 frame_count 决定，fps 仍然是读数。
         */
        public static final class FpsResult {
            public final float fps;
            public final int frames;

            public FpsResult(float fps, int frames) {
                this.fps = fps;
                this.frames = frames;
            }

            public static final FpsResult INACTIVE = new FpsResult(0f, 0);

            public boolean isActive() {
                return frames >= Constants.Fps.FPS_ACTIVE_MIN_FRAMES;
            }
        }

        /**
         * 联发科 FPSGO frame-stabilizer 状态节点。
         *
         * 挂载点随内核版本、fpsgo 版本和厂商裁剪而变，也没有可靠的探测手段，只能穷举。
         * 已知的形态：
         * /sys/kernel/fpsgo/fstb/ —— Android R 起 debugfs 在 user 版被禁用，FPSGO 改用
         *      sysfs（内核里是 fpsgo_sysfs_create_dir(NULL, "fstb", &fstb_kobj)）。
         * /sys/kernel/debug/fpsgo/fstb/ —— O/P/Q 的 debugfs 位置。
         * /proc/perfmgr/fstb/ —— 早期 fpsgo 挂在 perfmgr 的 procfs 下，天玑 1200（ColorOS 13）
         *      这类老平台比较多见。
         * /proc/fpsgo/fstb/ —— 部分厂商内核搬到了这里。
         * /sys/kernel/fpsgo_v3/、fpsgo_v8 —— 新内核把版本号写进目录名了。
         * 用户在天玑 9400（ColorOS 16）和天玑 1200（ColorOS 13）上都报过
         * No such file or directory，所以不能只靠 FPSGO。
         */
        public static final String[] FPSGO_PATHS = {
                "/sys/kernel/fpsgo/fstb/fpsgo_status",
                "/sys/kernel/debug/fpsgo/fstb/fpsgo_status",
                "/proc/perfmgr/fstb/fpsgo_status",
                "/proc/fpsgo/fstb/fpsgo_status",
                "/proc/perfmgr/fpsgo/fstb/fpsgo_status",
                "/sys/kernel/fpsgo_v3/fstb/fpsgo_status",
                "/sys/kernel/fpsgo_v8/fstb/fpsgo_status",
                "/sys/kernel/debug/fpsgo_v3/fstb/fpsgo_status",
                "/proc/fpsgo_v3/fstb/fpsgo_status",
                "/sys/kernel/fpsgo/fstb/fstb_info",
                "/proc/perfmgr/fstb/fstb_info",
        };

        /**
         * 联发科 GED KPI 逐进程帧率节点。
         *
         * FPSGO 之外的另一条联发科路子：GED（GPU Extension Device）的 KPI 模块也统计
         * 每个 BQ（BufferQueue）的帧率。内容长这样：
         *
         * 0x900000001d3d, com.tencent.tmgp, fps: 30, ...
         *
         * 格式各版本差得很大，所以解析上只做「找 pid/包名那一行里的帧率数字」，
         * 不假定固定列位。
         */
        public static final String[] GED_KPI_PATHS = {
                Gpu.GED_HAL + "/gpu_kpi",
                Gpu.GED_HAL_DEBUG + "/gpu_kpi",
                Gpu.GED_HAL + "/ged_kpi",
                Gpu.GED_HAL_DEBUG + "/ged_kpi",
                "/proc/ged/gpu_kpi",
                "/sys/kernel/ged/hal/fps_gpu_info",
        };

        /**
         * fpsgo_status 表头里定位列号用的字段名。内核输出长这样：
         *
         * tid	bufID		name		currentFPS	targetFPS	FPS_margin	HWUI	t_gpu	policy
         * 19158	0x900000001d3d	com.tencent.tmg	30		60		0		1	4218750	(1,2)
         * dfps_ceiling:60
         *
         * 列数随内核版本变（老版本没有 bufID，新版本尾部多出 HWUI/t_gpu/policy），
         * 所以按表头定位，不写死下标。
         */
        public static final String FPSGO_COL_FPS = "currentFPS";
        public static final String FPSGO_COL_NAME = "name";
        /** 没表头时的兜底列号，对应上面那个格式 */
        public static final int FPSGO_DEFAULT_NAME_COL = 2;
        public static final int FPSGO_DEFAULT_FPS_COL = 3;

        /**
         * 进程名前缀匹配需要的最小长度。
         *
         * 内核里 proc_name 取自 task comm，受 TASK_COMM_LEN 限制最长 15 个可见字符，
         * 所以长包名会被截断（com.tencent.tmgp.sgame → com.tencent.tmg），必须做前缀匹配。
         * 而包名普遍以 com. 开头，要是允许极短的进程名做前缀，一个叫 com 的进程
         * 就会匹配上所有应用。
         */
        public static final int FPSGO_NAME_MIN_LEN = 8;

        // ---- SurfaceFlinger 帧时间戳：跟芯片厂商无关的应用级帧率来源 ----

        /** ServiceManager 是隐藏 API，只能反射；SystemUI 是平台应用，不受这个限制 */
        public static final String SF_CLASS_SERVICE_MANAGER = "android.os.ServiceManager";
        public static final String SF_METHOD_GET_SERVICE = "getService";
        public static final String SF_SERVICE = "SurfaceFlinger";

        /** 列出所有 layer 的名字，一行一个 */
        public static final String SF_ARG_LIST = "--list";
        /**
         * 取指定 layer 最近 128 帧的时间戳。
         *
         * 输出首行是 vsync 周期（纳秒），后面每行是三列制表符分隔的纳秒时间戳：
         * desiredPresentTime、actualPresentTime、frameReadyTime。
         * layer 名是精确匹配，所以必须先用 SF_ARG_LIST 拿到完整名字。
         */
        public static final String SF_ARG_LATENCY = "--latency";

        /** dump 没权限时输出里会带的字样，靠它判定这一路彻底不能用了 */
        public static final String SF_PERMISSION_DENIED = "Permission Denial";

        /**
         * layer 名里出现这个字样说明是 SurfaceView。
         *
         * 游戏和视频播放器都把画面画在 SurfaceView 上，它的宿主 Activity 层常常
         * 一帧都不更新，所以 SurfaceView 优先。
         */
        public static final String SF_HINT_SURFACE_VIEW = "SurfaceView";

        /**
         * 含这些字样的 layer 只是容器、占位或者系统架构层，永远没有帧记录，直接排掉。
         *
         * 匹配时统一转小写比较（见 SurfaceFlingerFpsReader.isNoiseLayer），所以这里全写成小写。
         * v1.5.6 里 "Dim Layer" 的大小写跟 ColorOS 实际输出的 "Dim layer" 对不上，没过滤掉。
         *
         * 前 5 项是 ColorOS / AOSP 的显示架构层，它们排在 --list 输出的最前面。
         * v1.5.6 的候选上限是 4，于是候选全被这些层占满，真正的应用层一个都没进去——
         * 就算 dump 通了，帧率也恒为 0。
         */
        public static final String[] SF_NOISE_HINTS = {
                "windowedmagnification",
                "hidedisplaycutout",
                "onehanded",
                "fullscreenmagnification",
                "displayarea",
                "display 0",
                "leaf:",
                "task=",
                "activityrecord",
                "leash",
                "input consumer",
                "inputmethod",
                "wallpaper",
                "dim layer",
                "containerlayer",
                "background for ",
                "bbq wrapper",
                "screendecor",
                "navigationbar",
                "statusbar",
                "notificationshade",
                "hidden",
                "blurregion",
                "rounded",
        };

        /**
         * layer 名里含这个字符说明它带包名/类名，是真正的应用窗口层。
         *
         * --list 里应用层长这样
         * cf27fec com.android.launcher/com.android.launcher.Launcher#2838，
         * 斜杠分隔包名和 Activity 名。靠它把应用层排到候选前面。
         */
        public static final char SF_HINT_COMPONENT = '/';

        /**
         * 部分 ROM（实测 ColorOS 13 / 16）在每次 dumpsys 输出前插一行
         * ---- TIME: 2026-09-03 20:59:24.152 ----。
         *
         * v1.5.6 的格式判定要求首个非空行必须是纯数字的 vsync 周期，被这行头一顿就误判成
         * 「本机不支持 --latency」，进而把整条 SurfaceFlinger 路径永久作废——这正是天玑机型
         * 三个方案都报 x 的原因。现在遇到以它开头的行直接跳过。
         */
        public static final String SF_HEADER_PREFIX = "----";

        /** 挑活跃 layer 时最多看几个候选：每个都要单独 dump 一次 */
        public static final int SF_MAX_CANDIDATES = 10;
        /**
         * 判定 dump 格式时最多往下扫几行。
         *
         * 不能只看首行：ROM 会插输出头（见 SF_HEADER_PREFIX）；也不能扫全篇，
         * 否则完整 dump 里的数字行会被当成 latency 数据。
         */
        public static final int SF_FORMAT_PROBE_LINES = 6;
        /**
         * 帧率统计窗口（纳秒）：数「最近这段时间内上屏了多少帧」。
         * 取 1 秒，跟刷新周期同量纲，数出来的帧数直接就是帧率。
         */
        public static final long SF_WINDOW_NS = 1_000_000_000L;
        /**
         * 判定「帧记录环已经装满」的帧数下限。
         *
         * FrameTracker 的环是 128 格，其中一格是正在填的当前帧，所以最多读到 127 条历史记录。
         * 高刷屏上 127 帧装不满 1 秒（144Hz 只够 0.88 秒），这时候窗口内帧数会被环容量压住，
         * 得改用环内时间跨度算。留一点余量，不写死 127。
         */
        public static final int SF_RING_FULL_MIN = 120;
        /**
         * 选定的 layer 连续多少轮不出帧就换一个试试。
         *
         * 选错 layer 和画面真的静止，表现都是不出帧，没法立刻区分；
         * 隔几轮换一个探一下，代价小，也不会让静止画面来回跳。
         */
        public static final int SF_REPICK_IDLE_TICKS = 3;
        /**
         * 锁定 layer 后最多连续用几拍，之后强制全量重扫一次。
         *
         * 应用内换页、进出全屏播放器都会让真正出帧的 layer 换一个，而旧 layer 未必立刻销毁
         * （它可能还在慢速出帧）。定期重扫能纠正这类「锁在次要 layer 上了」的情况，
         * 代价是每 15 秒一次全量 dump。
         */
        public static final int SF_RESCAN_EVERY_TICKS = 15;
        /**
         * 单次 Binder dump 的等待上限（毫秒）。
         *
         * 400ms 在游戏满载时偏紧：SurfaceFlinger 忙着合成，未必来得及应答，超时会被记成
         * 一次失败。放宽到 800ms，仍然远小于 1 秒的采集周期。
         */
        public static final long SF_DUMP_TIMEOUT_MS = 800L;
        /** 经 root 跑 dumpsys 要 fork 进程，比 Binder 慢，上限放宽 */
        public static final long SF_ROOT_TIMEOUT_MS = 1500L;
        /** Binder 通道被拒时的退化命令 */
        public static final String SF_DUMPSYS_CMD = "dumpsys SurfaceFlinger";
        /**
         * 完整 dump 开头的固定字样。
         *
         * SurfaceFlinger 遇到不认识的参数时会退回打印完整 dump，里面有大量三列数字行，
         * 硬解会算出离谱的帧率。靠这个识别并放弃本轮。
         */
        public static final String SF_FULL_DUMP_MARKER = "Build configuration:";
        /**
         * dump 输出的保留行数上限。--latency 固定 128 行左右，
         * --list 层数多的时候能到上百行，留足余量就行。
         */
        public static final int SF_DUMP_MAX_LINES = 512;
        /**
         * 通道 / 格式判定连续失败多少次之后才真正放弃这条通道。
         *
         * v1.5.6 把这些判定做成了一次性的永久开关：开机早期 SurfaceFlinger 还没就绪、
         * 锁屏时没有可探的 layer、ROM 多打一行输出头，碰上任一种整条路就废到 SystemUI 重启为止。
         * 改成计数之后，偶发失败能自愈。
         */
        public static final int SF_CHANNEL_MAX_FAILURES = 5;
        /**
         * 转退避之后每隔多少轮真试一次。
         *
         * v1.5.7 沿用了 SYSFS_RESCAN_EVERY_TICKS = 60，退避期长达一分钟，期间诊断标记
         * 恒为 7（退避中），把真正的失败原因整个盖住了——用户实测报的 v7/x7 就是这么来的。
         * SF 的探测代价远小于全表扫 sysfs，5 轮足够。
         */
        public static final int SF_BACKOFF_EVERY_TICKS = 5;
        /**
         * --list 输出的 layer 名尾部会带 #序号（getDebugName()），
         * 而 --latency 匹配用的是不含序号的 getName()。
         *
         * Android 12 起 --list 改用 getDebugName()，两者从此对不上，拿 --list 的原名去查
         * --latency 会一个都匹配不上。所以查询前先剥掉 #序号；为了兼容还在用 getName()
         * 列表的旧 ROM，两种形式都试。
         */
        public static final char SF_SEQ_SEPARATOR = '#';

        // ---- TimeStats：Android 14+ 上 --latency 的替代 ----

        /**
         * --latency 背后是 SurfaceFlinger 的 FrameTracker，Android 14 起被 FrameTimeline
         * 取代了，参数还在但不再填帧数据。
         *
         * 实测天玑 1200 / Android 13 能取到值（标记 60b/60r），同一份代码在天玑 9400 /
         * Android 16 上所有候选层的帧环都是空的（标记 5）。TimeStats 是官方替代：
         * 它按 layer 累计 totalFrames，两次采样的差除以时间差就是真实帧率，
         * 不依赖 FrameTracker。
         */
        public static final String SF_ARG_TIMESTATS = "--timestats";
        /** TimeStats 默认是关的，得先打开才有数据 */
        public static final String SF_TIMESTATS_ENABLE = "-enable";
        /**
         * 清空已经累计的统计。
         *
         * 必须在 -enable 之后紧接着执行一次：-dump 是按累计帧数排名取前 N 个 layer 的，
         * 要是 ROM 出厂就开着 TimeStats，前几名会被开机以来累计了几小时的 launcher /
         * SystemUI 占满。这些层在全屏游戏下恰恰完全静止，于是「取增量最大的 layer」
         * 永远得到 0——这就是 v1.6.2 在天玑 / Android 16 上一进应用就显示 0t 的根因。
         *
         * 清零之后排名只反映清零之后的活动，正在渲染的层几秒内就会升到前面。
         * 只在启用时清一次：反复清会把差分基线也一起清掉。
         */
        public static final String SF_TIMESTATS_CLEAR = "-clear";
        /** 取出当前统计 */
        public static final String SF_TIMESTATS_DUMP = "-dump";
        /** 只要前几个 layer，免得输出太长 */
        public static final String SF_TIMESTATS_MAXLAYERS = "-maxlayers";
        /**
         * 取几个 layer 的统计。
         *
         * v1.6.2 取 8 个，配上按累计帧数排名的行为，前台应用的层常常根本不在结果里。
         * 提到 32：够覆盖一台机器上同时存在的活跃层，又不会让输出膨胀到拖慢每秒一次的
         * 采集（每个 layer 段约 20 行）。
         */
        public static final String SF_TIMESTATS_LAYER_COUNT = "32";
        /** layer 段的起始标记，后面紧跟 layer 名 */
        public static final String SF_TIMESTATS_LAYER_MARKER = "layerName = ";
        /** 累计总帧数字段。出现在 layer 段内是该层的，出现在之前是全局的 */
        public static final String SF_TIMESTATS_TOTAL_FRAMES = "totalFrames = ";
        /**
         * TimeStats 输出的行数上限。
         *
         * 32 个 layer × 每段约 20 行 ≈ 640 行，加上全局段留足余量。截断只会丢掉排名靠后的层，
         * 不影响正在渲染的那个。
         */
        public static final int SF_TIMESTATS_MAX_LINES = 900;
        /**
         * 两次 TimeStats 采样的最小间隔（纳秒）。
         *
         * 帧率是帧数差除以时间差算出来的，间隔太短会因为取整误差剧烈抖动。
         */
        public static final long SF_TIMESTATS_MIN_SPAN_NS = 700_000_000L;
        /**
         * 两次采样的最大间隔（纳秒）。超过就丢掉旧基线重新计时——
         * 中间可能息屏或者切了应用，跨度太长算出来的是平均值而不是当前帧率。
         */
        public static final long SF_TIMESTATS_MAX_SPAN_NS = 5_000_000_000L;
        /**
         * 连续多少拍所有 layer 的增量都是 0 就重新清零统计。
         *
         * 画面真的静止时增量为 0 是正常的，所以不能一见 0 就重清。但要是排名被陈旧的高累计层
         * 占满（切换应用后新层挤不进前 N），表现同样是持续 0，而且不会自己恢复。
         * 给足静止的宽限之后重清一次，让排名重新反映当前活动。
         */
        public static final int SF_TIMESTATS_ZERO_TICKS_BEFORE_CLEAR = 5;

        /**
         * TimeStats 认可的最低帧率。
         *
         * 为什么需要下限（v1.7.0）
         * 取「增量最大的 layer」时，要是前台应用的层拿不到增量（掉出 top-N 排名，
         * 或者跨 seq 对不上），唯一还有增量的就剩系统 UI 心跳层——状态栏、通知栏，
         * 以及监视条自己的 NexusFloatOverlay，后者每秒恰好 1 帧。
         * 于是帧率显示成 1~5，看着像掉帧，其实是量错了对象。
         * 真实的交互画面不会长期低于 8 fps（那已经是肉眼可见的卡死了），
         * 而系统 UI 心跳恰好落在 1~5。低于这个值的增量一律忽略：宁可报 0（静止）
         * 也不报一个明显错误的个位数——0 至少诚实，个位数会让用户以为是性能问题。
         */
        public static final float SF_TIMESTATS_MIN_FPS = 8f;

        /**
         * 面板节点探测（PanelProbe）的单目录结果上限和超时。
         *
         * find 遍历 sysfs 可能很慢，而且个别机型的 debugfs 里有成百上千个同名节点。
         * 限量既保护 shell 的行缓冲，也免得界面被刷屏。
         */
        public static final int PROBE_MAX_RESULTS = 40;
        public static final long PROBE_TIMEOUT_MS = 8000L;

        // ---- 诊断标记：开了「FPS 诊断」之后加在数值前面，用来确认取到了哪一级 ----

        /** SurfaceFlinger 帧时间戳，走 Binder */
        public static final String TAG_SF_BINDER = "b";
        /** SurfaceFlinger 帧时间戳，走 root dumpsys */
        public static final String TAG_SF_ROOT = "r";
        /**
         * SurfaceFlinger 帧时间戳，不带层名的兜底。
         *
         * 逐层匹配全失败时启用，取到的是合成器级上屏时间，不保证严格等于前台应用的渲染帧率，
         * 所以单独标记一下，方便判断可信度。
         */
        public static final String TAG_SF_DISPLAY = "d";
        /**
         * SurfaceFlinger TimeStats 逐 layer 帧数增量。
         *
         * Android 14+ 上 --latency 已经没数据了，这是官方替代路径。
         */
        public static final String TAG_SF_TIMESTATS = "t";
        /** 联发科 FPSGO 逐进程帧率 */
        public static final String TAG_FPSGO = "f";
        /** 联发科 GED KPI 逐进程帧率 */
        public static final String TAG_GED = "g";
        /** 面板实测帧率（高通 measured_fps） */
        public static final String TAG_PANEL = "p";
        /** Choreographer 计帧——实际是屏幕刷新率，不是应用帧率 */
        public static final String TAG_VSYNC = "v";
        /** 所有应用级来源都挂了，而且当前方案不允许退回 Choreographer */
        public static final String TAG_NONE = "x";

        // ---- SurfaceFlinger 失败细分标记：诊断模式下附在 x 后面，指明断在哪一步 ----

        /**
         * SF 这一路本轮为什么没出数。
         *
         * v1.5.6 只有一个笼统的 x，用户报「三个方案都是 x」的时候没法判断是通道不通、
         * 参数不支持还是候选层选错了，只能靠猜。细分之后一眼就能定位。
         */
        /** 两个通道（Binder / root）都已经判定不可用 */
        public static final String SF_FAIL_NO_CHANNEL = "0";
        /** --list 没有输出 */
        public static final String SF_FAIL_LIST_EMPTY = "1";
        /** SurfaceFlinger 不认识参数，退回打印了完整 dump */
        public static final String SF_FAIL_UNSUPPORTED = "2";
        /** --list 有输出，但过滤完没剩下可探的 layer */
        public static final String SF_FAIL_NO_CANDIDATE = "3";
        /** --latency 的输出格式没认出来 */
        public static final String SF_FAIL_BAD_FORMAT = "4";
        /** 候选层都探过了，没一个有帧记录 */
        public static final String SF_FAIL_NO_ACTIVE = "5";
        /** 锁定的 layer 已经没了（Activity 重建会让名字里的序号变） */
        public static final String SF_FAIL_LAYER_GONE = "6";
        /** 退避期里跳过本轮，不算真失败 */
        public static final String SF_FAIL_BACKOFF = "7";
        /** TimeStats 也没可用数据（打不开，或者输出里没有 layer 段） */
        public static final String SF_FAIL_NO_TIMESTATS = "8";
        /** TimeStats 已经取到基线，还在等下一次采样凑够时间跨度 */
        public static final String SF_FAIL_TIMESTATS_WARMUP = "9";

        /**
         * collectFpsData 全部来源都失败时的标记值，由 Choreographer 兜底。
         *
         * 注意 Choreographer 计帧在 SystemUI 进程里实测的是屏幕刷新率：
         * frame callback 跟着 VSync 走，只要持续 post 就每个 VSync 回调一次，
         * 跟前台应用画不画、画多少帧没关系。所以 60Hz 屏上恒为 60、120Hz 屏上恒为 120。
         * 这是 v1.5.2 及以前联发科机型「30 帧游戏显示 60」的原因。
         * 在 SystemUI 进程里测不到别的应用的渲染帧率，所以只能留着它当最后兜底。
         */
        public static final float READ_FAILED = -1f;
        /** Choreographer 计帧的最小统计窗口（毫秒）；小于采集周期，保证每轮都能出值 */
        public static final long FRAME_WINDOW_MS = 500L;

        private Fps() {}
    }

    /** /proc、thermal 这些路径 */
    public static final class Proc {
        public static final String STAT = "/proc/stat";
        public static final String MEMINFO = "/proc/meminfo";
        public static final String THERMAL_CLASS = "/sys/class/thermal";
        public static final String[] CPU_THERMAL_KEYWORDS = {"cpu", "soc", "mtktscpu"};

        private Proc() {}
    }

    /**
     * 电池电流 / 电压 / 功率 sysfs 节点。
     *
     * BatteryManager.BATTERY_PROPERTY_CURRENT_NOW 在不少联发科机型上恒返回 0
     * （HAL 没实现这个 property），会导致功率显示 0，所以必须能退回直读 sysfs。
     *
     * Linux power_supply class 的 ABI 规定 current_now 是 µA、voltage_now 是 µV、
     * power_now 是 µW，但有些 ROM 实际给的是 mA / mV，所以单位由 SysfsReader
     * 按数值量级判定。
     */
    public static final class Battery {
        /**
         * 直接给功率的节点。
         *
         * 只当最后兜底，不能优先用：power_now 是 power_supply ABI 里的可选属性，
         * 各家实现语义不一致。部分高通 ROM 在这里上报的是充电器额定功率或设计功率
         * 这类恒定值（比如恒为 10000000，也就是 10W），不是瞬时功率，优先采用会让
         * 功率读数锁死——这正是 v1.4.4 在高通平台恒显示 -10W 的原因。
         * current_now × voltage_now 是普遍实现而且语义明确的组合，应该优先走那条路。
         */
        public static final String[] POWER_PATHS = {
                "/sys/class/power_supply/battery/power_now",
                "/sys/class/power_supply/bms/power_now",
        };

        public static final String[] CURRENT_PATHS = {
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/battery/BatteryAverageCurrent",
                "/sys/class/power_supply/battery/batt_current_now",
                "/sys/class/power_supply/main/current_now",
        };

        public static final String[] VOLTAGE_PATHS = {
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/bms/voltage_now",
                "/sys/class/power_supply/battery/batt_vol",
                "/sys/class/power_supply/main/voltage_now",
        };

        /** 电流/电压节点的候选单位除数：µ 优先，其次 m */
        public static final float[] CURRENT_DIVISORS = {1_000_000f, 1_000f};
        /** power_now 按 Linux ABI 固定当 µW；µW 和 mW 区间会重叠，不猜 */
        public static final float[] POWER_DIVISORS = {1_000_000f};

        /**
         * 合理电流区间（A）。区间上下限之比小于 1000，这样 µA 和 mA 两种解释的
         * 原始数值区间不会重叠，单位判定就是唯一的：
         * µA 要 X∈[5e4,2e7]，mA 要 X∈[50,2e4]。
         *
         * v1.8.10：上限从 20A 收紧到 12A——手机单电芯充电电流极少超过 12A
         * （120W 快充也多在 10A 上下），20A 太宽会让节点偶发的大值通过校验，
         * 表现就是「0.5W 突然跳到 60W」。
         */
        public static final float CURRENT_MIN_A = 0.05f;
        public static final float CURRENT_MAX_A = 12f;
        /** 合理电压区间（V），覆盖单/双电芯；同样保证 µV 和 mV 不重叠 */
        public static final float VOLTAGE_MIN_V = 2.5f;
        public static final float VOLTAGE_MAX_V = 20f;
        /** 合理功率区间（W） */
        public static final float POWER_MIN_W = 0.05f;
        public static final float POWER_MAX_W = 200f;
        /** 读不到电压时的兜底值（V） */
        public static final float VOLTAGE_FALLBACK_V = 4.0f;
        /**
         * 读不到功率时最多沿用上次有效值几轮（1 轮 = 一个采集周期）。
         *
         * 兜底只为消掉单次读取的抖动。要是无限期沿用，一旦数据源永久失效，
         * 屏幕上就留着一个永不变化的数字，反而比显示 0 更误导。
         */
        public static final int STALE_MAX_TICKS = 5;

        /**
         * power_now 连续上报同一个数值多少轮之后就判定为静态值，永久弃用。
         *
         * 真实瞬时功率不可能连续十秒一字不差，所以数值恒定就说明这节点报的是
         * 额定功率之类的静态量。这是对 POWER_PATHS 语义不一致的兜底防护：
         * 就算某机型的电流路径也不可用，也不会锁死在一个假读数上。
         */
        public static final int POWER_CONSTANT_REPEATS = 10;

        /**
         * 功率突变守卫（v1.8.10）。
         *
         * 实测充电时偶发「正常 +0.5W 突然显示 +60W」：某一拍电流节点瞬时跳到 10A 以上
         * （或者单位解析落到了 µA/mA 边界的错误分支），电压乘电流就得到一个仍然落在
         * POWER_MAX_W 之内的假值，光靠区间过滤抓不住。真实功率不会在一拍之内翻几十倍，所以：
         * 新值超过上次有效值 POWER_SPIKE_RATIO 倍、且差值超过 POWER_SPIKE_MIN_DELTA_W 时，
         * 判为疑似尖峰，先沿用上次有效值；
         * 连续 POWER_SPIKE_CONFIRM_TICKS 拍都这么大，才认定为真实变化
         * （比如真插上了快充），予以采纳。
         */
        public static final float POWER_SPIKE_RATIO = 8f;
        public static final float POWER_SPIKE_MIN_DELTA_W = 5f;
        public static final int POWER_SPIKE_CONFIRM_TICKS = 3;

        private Battery() {}
    }

    /** 状态栏监视器 UI 尺寸和宽度探针 */
    public static final class Ui {
        /** 默认字号（sp）；实际字号由用户经 App 加减按钮调 */
        public static final float TEXT_SIZE_SP = 7f;
        /** 字号可调范围和步长（sp） */
        public static final float TEXT_SIZE_MIN_SP = 5f;
        public static final float TEXT_SIZE_MAX_SP = 20f;
        public static final float TEXT_SIZE_STEP_SP = 1f;

        /**
         * 监视条高度的下限（dp），对应默认字号。
         *
         * v1.6.6 及以前把它当成实际高度用了（乘字号比例后直接当窗口高度），于是大字号
         * 或者中文字形一超出这个值就被窗口裁掉——这就是「调大字体显示不全」的原因：
         * 20sp 时算出 22.9dp，而 20sp 的行高本身就超过 22dp。
         *
         * 现在实际高度由 MonitorView.getBarHeightPx() 实测得出，这个常量只当下限，
         * 保证内容为空的时候窗口不会塌成 0 高。
         */
        public static final float MONITOR_BAR_HEIGHT_DP = 8f;
        /** 监视项目间隔的默认值和可调范围（dp），实际值仍然按字号等比缩放 */
        public static final float DIVIDER_WIDTH_DP = 8f;
        public static final float DIVIDER_MIN_DP = 0f;
        public static final float DIVIDER_MAX_DP = 24f;
        public static final float DIVIDER_STEP_DP = 1f;
        public static final float BAR_HORIZONTAL_MARGIN_DP = 2f;
        public static final float CPU_BAR_WIDTH_DP = 24f;
        public static final float GPU_BAR_WIDTH_DP = 5f;
        public static final float BAR_HEIGHT_DP = 6f;
        public static final float BAR_SPACING_PX = 2f;
        public static final float BAR_HIGH_USAGE_THRESHOLD = 85f;

        /**
         * 固定列宽在 measureText 之外额外留的余量（dp）。
         *
         * measureText 给的是字形前进宽度（advance），而有些字形的实际墨迹会超出 advance——
         * 中文标点和某些字体的斜体尤其明显，表现就是最后一个字符右侧被裁掉一条。
         * 用固定的像素余量而不是按比例放大：比例余量在小字号下不够、在大字号下又过头。
         */
        public static final float TEXT_WIDTH_SLACK_DP = 1f;

        public static final int COLOR_BAR_NORMAL = 0xFF4285F4;
        public static final int COLOR_BAR_HIGH = 0xFFEA4335;
        public static final int COLOR_BAR_BG = 0x40888888;

        /** 监视条文字和柱条底色的默认色（白）。可以用「字体颜色」改成其他预设色 */
        public static final int COLOR_TEXT = 0xFFFFFFFF;
        /**
         * 「自动反色」判定为浅色背景时改用的深色字。
         *
         * 柱条底槽不需要对应的常量：MultiCoreBarView 直接把当前字色降到半透明当底槽，
         * 深色字的时候自然就是深色槽。
         */
        public static final int COLOR_TEXT_DARK = 0xFF111111;
        /**
         * 「字体颜色」的预设色板。
         *
         * 不做取色器：状态栏上的字只有几个像素高，饱和度或明度稍低就看不清，
         * 让用户自由取色多半只会选出一个读不了的颜色。这里给的都是深色和浅色壁纸上
         * 都还能分辨的高对比色。
         *
         * 下标 0 是白色，也就是默认值，跟旧版本一致。
         */
        public static final int[] TEXT_COLORS = {
                0xFFFFFFFF, // 白
                0xFF111111, // 黑
                0xFF6DB0FF, // 蓝
                0xFF34D399, // 绿
                0xFFFBBF24, // 黄
                0xFFF87171, // 红
                0xFFC084FC, // 紫
                0xFF22D3EE, // 青
        };
        /** 色板各项的名称，下标跟 TEXT_COLORS 对应 */
        public static final String[] TEXT_COLOR_NAMES = {
                "白", "黑", "蓝", "绿", "黄", "红", "紫", "青",
        };
        /**
         * 状态栏图标色的 luma 上限，低于它判定「系统在用深色图标」，
         * 也就是背景是浅色的。
         *
         * 系统的深色图标一般是 #33333333 一类（luma ≈ 51），浅色图标是纯白（luma = 255）。
         * 取 128 当中间点，两边余量都很足。
         */
        public static final int TINT_DARK_LUMA_MAX = 128;

        /**
         * 等宽字体下测固定列宽用的最长样例（别用 ems）。
         *
         * 必须覆盖实际可能出现的最宽值：v1.7.0 及以前这里是 "99%"，而 CPU 占用满载时
         * 是 "100%"，被 clip() 截成了 "100"——百分号凭空消失。列宽多一个字符，
         * 换满载时显示正确。
         *
         * v1.8.10：v1.8.9 一度改成 3 字符的 "88%"，满载丢百分号的问题又回来了，
         * 所以改回 4 字符。列宽固定不变，8%→10% 也不会顶动监视条。
         */
        public static final String PROBE_USAGE = "100%";
        /** 最大频率长度，比如 3333 */
        public static final String PROBE_CPU_FREQ = "9999";
        public static final String PROBE_GPU = "999";
        /**
         * FPS 探针（v1.8.8）：前面那个空格是缓冲位——数值左对齐时从第二列开始，
         * 冒号后始终空一格，十位数/百位数变化（比如 60→120）不会顶到冒号，
         * 视觉不跳。列宽按「空格+99.9」算。
         */
        public static final String PROBE_FPS = " 99.9";
        /**
         * 开了「FPS 诊断」之后数值尾部要跟来源字母，失败时还多一位阶段编号
         * （比如 x3、v1），所以留两位余量；同样带前导空格缓冲。
         */
        public static final String PROBE_FPS_DEBUG = " 99.9xx";
        public static final String PROBE_PCT = "100%";
        public static final String PROBE_TEMP = "99.9°C";
        /**
         * CPU 温度探针（v1.8.0）。电池温度是 99.9°C 五位，
         * CPU 结温三位数很常见（满载 100℃+），取 100°C 四位做最宽情况。
         */
        public static final String PROBE_CPU_TEMP = "100°C";
        /**
         * 功率探针。
         *
         * 不含符号位：符号由 %+.2f 现场产生，而正负号本身占的宽度跟数字一样
         * （等宽字体），所以探针只需要覆盖数字部分的最宽情况。
         * v1.7.0 及以前探针带 +，而 Gravity.CENTER 又让实际值在列内居中，
         * 正负号前后就多出半格空白——用户看到的「+ - 前面有多的空」就是这么来的。
         * 改成左对齐（见 MonitorView）之后符号紧贴前一项。
         */
        public static final String PROBE_POWER = "-99.99W";
        /**
         * 电流以整数 mA 显示。
         *
         * 留 5 位数字：Battery.CURRENT_MAX_A 是 20A，也就是最大 20000mA，
         * 用 4 位会被截成 -20000m。大功率快充在电池侧上万毫安是常态。
         */
        public static final String PROBE_CURRENT = "-99999mA";

        public static final String GPU_NA = "N/A";

        /**
         * 「背景」开关开着时监视条的圆角矩形背景色。
         * 取半透明深灰：不完全挡住壁纸，又能保证白字在浅色背景上看得清。
         */
        public static final int COLOR_BACKGROUND = 0xB3303030;
        /** 背景圆角半径 */
        public static final float BACKGROUND_CORNER_RADIUS_DP = 4f;
        /** 背景开着时左右内边距，免得文字贴到圆角边缘 */
        public static final float BACKGROUND_PADDING_H_DP = 4f;

        private Ui() {}
    }

    /**
     * 独立悬浮窗参数。监视器不再当 status_bar 的子 View，
     * 否则全屏应用隐藏状态栏时监视器会一起消失。
     */
    public static final class Overlay {
        /** 方便在 dumpsys window 里认出来 */
        public static final String WINDOW_TITLE = "NexusFloatOverlay";

        /**
         * 候选窗口类型，按优先级排，取第一个 addView 成功的。
         *
         * 2006 TYPE_SYSTEM_OVERLAY：system uid 专用，层级高于全屏应用，也不随状态栏隐藏。
         * 2017 TYPE_SECURE_SYSTEM_OVERLAY：同样是 system-only，层级更高，
         *      有些新系统收紧了 2006 之后仍然放行这个。
         * 2038 TYPE_APPLICATION_OVERLAY：A8+ 的通用悬浮层，当主要回退。
         * 2024 TYPE_NAVIGATION_BAR_PANEL：system-only，个别 ROM 只放行导航栏相关的类型。
         * 2015 TYPE_STATUS_BAR_SUB_PANEL：最后兜底，个别 ROM 只放行状态栏相关的类型。
         *
         * 用字面量而不是常量引用：TYPE_SYSTEM_OVERLAY 已经被标废弃，
         * 而且有些编译配置下引用 system-only 类型会触发 lint 报错。
         */
        public static final int[] WINDOW_TYPES = {2006, 2017, 2038, 2024, 2015};

        /**
         * 轮询间隔（毫秒）：检查竖屏/横屏开关有没有被 App 端改过。
         * RemotePreferences 没有跨进程变更回调，所以只能轮询；
         * 屏幕旋转另有 ACTION_CONFIGURATION_CHANGED 会即时触发。
         */
        public static final long WATCH_INTERVAL_MS = 1000L;

        /**
         * 「需要采集」指令的心跳周期（以 WATCH_INTERVAL_MS 为单位）。
         *
         * 指令只在状态变化时下发，但模块进程被系统回收后重启时默认是不采集的，
         * SystemUI 并不知情。所以在需要采集期间每 30 秒再发一次，当心跳自愈。
         * 「暂停」不复发，否则会把已经被回收的进程反复拉起来。
         */
        public static final int SIGNAL_REPEAT_TICKS = 30;

        /**
         * 设置快照的最小刷新间隔（毫秒）。
         *
         * 快照走 ContentProvider 的 binder 调用，比读 RemotePreferences 贵，所以要限流。
         * 设置改动的生效延迟本来就是秒级，1 秒够了。
         */
        public static final long SNAPSHOT_REFRESH_MS = 1000L;

        private Overlay() {}
    }

    /** 后台线程名 */
    public static final class ThreadName {
        public static final String GPU_COLLECTOR = "GpuCollector";
        public static final String NEXUS_COLLECTOR = "NexusCollector";
        public static final String EXEC_READER = "ExecUtils-reader";
        public static final String COLLECT_SIGNAL = "NexusCollectSignal";
        /** SurfaceFlinger dump 的读取线程；跟采集线程分开，免得管道写满时互相等死 */
        public static final String SF_DUMP = "NexusSfDump";
        /** 设置快照的刷新线程；query 是同步 binder 调用，不能占主线程 */
        public static final String PREFS_SNAPSHOT = "NexusPrefsSnapshot";

        private ThreadName() {}
    }
}
