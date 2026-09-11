package com.jj.nexusfloat.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.bridge.StatusBarTintBridge;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.service.GpuCollectorLauncher;
import com.jj.nexusfloat.ui.MonitorOverlay;
import com.jj.nexusfloat.ui.view.MonitorView;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.ReflectUtils;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 模块入口：往 SystemUI 注入 MonitorView，在模块进程里启动 GPU 采集。
 *
 * 注入有两条路：
 * 1. Hook 折叠状态栏 Fragment 的 onViewCreated，能拿到状态栏 View 树，可以用退化
 *    模式（当 status_bar 的子 View）和反色桥接。类名在各 Android 版本间改过好几回，
 *    所以按 Constants.SystemUi#FRAGMENT_CANDIDATES 一个个试。
 * 2. Hook SystemUIApplication.onCreate，只拿 Context，延迟几秒后直接建独立悬浮窗。
 *    这条路不依赖任何状态栏类名，是安卓 17 这类新系统上 Fragment 全改名之后的保命通道。
 *
 * 两条路都走通时以第一条为准（第二条发现已经注入过就跳过）。
 */
public class ModuleMain extends XposedModule {

    private MonitorView monitorView;
    private MonitorOverlay overlay;
    private StatusBarTintBridge tintBridge;

    /** 已经成功注入过一次，兜底路径靠它避免重复注入 */
    private volatile boolean injected;
    /** 兜底注入排程了没有，免得两个入口各排一轮 */
    private volatile boolean fallbackScheduled;
    /** 兜底注入还剩几次重试机会 */
    private int fallbackRetriesLeft = Constants.SystemUi.FALLBACK_INJECT_RETRIES;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        LogUtils.xposed(this, Log.DEBUG, "onPackageReady: " + pkg);

        // 模块自己的进程：把 root GPU 采集线程起起来
        if (Constants.Package.MODULE.equals(pkg)) {
            LogUtils.xposed(this, Log.INFO, "Module process ready");
            GpuCollectorLauncher.startInProcess();
            return;
        }

        if (!Constants.Package.SYSTEM_UI.equals(pkg)) {
            return;
        }

        LogUtils.xposed(this, Log.INFO, "SystemUI loaded, starting hook...");

        // SystemUI 这边要绑 Remote，才能读到 GPU 这类跨进程数据
        try {
            NexusBridge.bindModule(this);
        } catch (Throwable t) {
            LogUtils.xposed(this, Log.ERROR, "NexusBridge bind failed", t);
        }

        ClassLoader classLoader = param.getClassLoader();
        // 诊断用：把 ClassLoader 和进程信息打出来。澎湃/MIUI 把状态栏 UI 放在
        // miui.systemui.plugin 里用独立 ClassLoader 加载，这种情况下下面那些 Fragment
        // 候选一个都找不到，只能靠 Application 兜底那条路
        LogUtils.xposed(this, Log.INFO, "classLoader=" + classLoader);
        dumpSystemUiFlavor(classLoader);

        tintBridge = new StatusBarTintBridge(classLoader);
        hookMiui12StatusBarTint(classLoader);

        // 注入折叠状态栏 Fragment：类名随版本变过，一个个候试着来
        boolean hooked = false;
        for (String candidate : Constants.SystemUi.FRAGMENT_CANDIDATES) {
            if (tryHookFragment(classLoader, candidate)) {
                hooked = true;
                break;
            }
        }
        if (!hooked) {
            LogUtils.xposed(this, Log.WARN,
                    "No status bar fragment matched, relying on Application fallback");
        }

        // 不管 Fragment 命中没有，Application 兜底都挂上：
        // 命中了它会看到 injected==true 直接跳过；没命中它就是唯一的注入通道
        hookApplicationFallback(classLoader);
    }

    /**
     * 诊断用：探一下这台机器的 SystemUI 是什么形态，好判断注入为什么没生效。
     *
     * 只在 LOG_ENABLED 打开时才有意义，一个个 Class.forName 的开销在正式版里不划算，
     * 所以整个方法体都被日志开关短路了。
     */
    private void dumpSystemUiFlavor(ClassLoader classLoader) {
        if (!LogUtils.isEnabled()) {
            return;
        }
        // 这些类只要有一个在，就说明状态栏 UI 是 MIUI 插件承载的，
        // 而插件用的是独立 ClassLoader——AOSP 那些 Fragment 名注定找不到
        String[] miuiProbes = {
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarPolicy",
                "com.miui.systemui.SystemUIApplication",
                "com.android.systemui.MiuiSystemUIApplication",
                "miui.systemui.SystemUIApplication",
                "com.android.systemui.SystemUIApplication",
        };
        for (String name : miuiProbes) {
            boolean found;
            try {
                Class.forName(name, false, classLoader);
                found = true;
            } catch (Throwable t) {
                found = false;
            }
            LogUtils.xposed(this, Log.INFO, "probe class " + name + " -> " + found);
        }
        // 实测澎湃 4 上 Application 的类名被换掉了，把真实类名打出来，以后好直接命中
        Context app = currentApplication();
        LogUtils.xposed(this, Log.INFO, "currentApplication at onPackageReady = "
                + (app == null ? "null (Application not created yet)"
                : app.getClass().getName()));
    }

    /**
     * Hook Fragment 生命周期：onViewCreated 里注入监视器，onDestroyView 里解除反色注册。
     */
    private boolean tryHookFragment(ClassLoader classLoader, String className) {
        LogUtils.xposed(this, Log.DEBUG, "tryHookFragment: " + className);
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);

            Method onViewCreated = findOnViewCreated(clazz);
            if (onViewCreated == null) {
                LogUtils.xposed(this, Log.WARN, "onViewCreated not found in " + className);
                return false;
            }

            hook(onViewCreated).intercept(chain -> {
                Object result = chain.proceed();
                View root = (View) chain.getArg(0);
                if (root != null) {
                    injectMonitorView(root.getContext(), root);
                }
                return result;
            });

            for (Method m : clazz.getDeclaredMethods()) {
                if (Constants.SystemUi.METHOD_ON_DESTROY_VIEW.equals(m.getName())
                        && m.getParameterTypes().length == 0) {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (tintBridge != null) {
                            tintBridge.detach();
                        }
                        return result;
                    });
                    break;
                }
            }

            LogUtils.xposed(this, Log.INFO, "Successfully hooked " + className);
            return true;
        } catch (ClassNotFoundException e) {
            LogUtils.xposed(this, Log.DEBUG, "Class not found: " + className);
            return false;
        } catch (Exception e) {
            LogUtils.xposed(this, Log.ERROR, "Error hooking " + className, e);
            return false;
        }
    }

    /**
     * 跟应用侧类名无关的兜底注入。
     *
     * v1.6.8 之前为什么在澎湃 OS 4 上完全失效：
     * 实测日志（澎湃 4 / 安卓 17）：
     *
     *     classLoader=...[zip file "/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"]
     *     probe class com.android.systemui.statusbar.phone.MiuiPhoneStatusBarPolicy -> true
     *     probe class com.android.systemui.SystemUIApplication -> false
     *
     * 澎湃把 Application 的类名换掉了，AOSP 的 SystemUIApplication 根本不存在，
     * 于是原来那条 hook 直接 ClassNotFound。
     *
     * 配套的「直接取现成 Application」同样拿不到东西：onPackageReady 是在
     * ActivityThread.handleBindApplication → LoadedApk.getClassLoader 里被调用的，
     * 那一刻 Application 对象还没 new 出来，currentApplication() 返回 null。原来的
     * 代码 instanceof Context 判定为假，既不注入也不打日志，故障完全无声。
     *
     * 现在有三条路：
     * 1. hook Instrumentation.callApplicationOnCreate(Application)。它在 bootclasspath
     *    里，任何 ClassLoader 都能解析，而且每个应用进程必经此处，ROM 怎么改
     *    Application 类名都不受影响。
     * 2. hook Application.attach(Context)（同样是 framework 类），时机更早一点，
     *    个别 ROM 会绕过 Instrumentation 的标准路径。
     * 3. 往主线程 post 一个延迟轮询，不依赖任何 hook。onPackageReady 正在
     *    handleBindApplication 内部执行，post 出去的任务必然在它返回之后才跑，
     *    那时候 Application 已经存在了。这条是最后的保险。
     *
     * 三条都汇到 scheduleFallbackInject，那儿自带去重。
     */
    private void hookApplicationFallback(ClassLoader classLoader) {
        hookInstrumentation();
        hookApplicationAttach();
        pollForApplication();
    }

    /**
     * Hook Instrumentation.callApplicationOnCreate(Application)。
     *
     * 这是 framework 调应用 Application.onCreate 的统一入口，参数就是 Application
     * 实例本身，所以不用管它具体叫什么类名。
     */
    private void hookInstrumentation() {
        try {
            Class<?> instrumentation = Class.forName(
                    Constants.SystemUi.CLASS_INSTRUMENTATION);
            Class<?> appClass = Class.forName(Constants.SystemUi.CLASS_APPLICATION);
            Method call = instrumentation.getDeclaredMethod(
                    Constants.SystemUi.METHOD_CALL_APP_ON_CREATE, appClass);
            hook(call).intercept(chain -> {
                Object result = chain.proceed();
                Object app = chain.getArg(0);
                if (app instanceof Context) {
                    LogUtils.xposed(this, Log.INFO,
                            "callApplicationOnCreate hit: " + app.getClass().getName());
                    scheduleFallbackInject((Context) app);
                }
                return result;
            });
            LogUtils.xposed(this, Log.INFO,
                    "Hooked Instrumentation.callApplicationOnCreate (fallback 1)");
        } catch (Throwable t) {
            LogUtils.xposed(this, Log.WARN, "Instrumentation hook skipped", t);
        }
    }

    /**
     * Hook Application.attach(Context)，比 onCreate 更早的时机。
     *
     * attach 是包级私有方法，只能靠 getDeclaredMethod 拿到。这时候 Context 还没走完
     * onCreate，所以仍然走延迟注入，不马上加窗。
     */
    private void hookApplicationAttach() {
        try {
            Class<?> appClass = Class.forName(Constants.SystemUi.CLASS_APPLICATION);
            Method attach = appClass.getDeclaredMethod(
                    Constants.SystemUi.METHOD_ATTACH, Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                Object app = chain.getThisObject();
                if (app instanceof Context) {
                    LogUtils.xposed(this, Log.INFO,
                            "Application.attach hit: " + app.getClass().getName());
                    scheduleFallbackInject((Context) app);
                }
                return result;
            });
            LogUtils.xposed(this, Log.INFO,
                    "Hooked Application.attach (fallback 2)");
        } catch (Throwable t) {
            LogUtils.xposed(this, Log.WARN, "Application.attach hook skipped", t);
        }
    }

    /**
     * 不依赖 hook 的最后一条保险：延迟轮询 currentApplication()。
     *
     * onPackageReady 跑在 handleBindApplication 内部，那会儿 Application 还没建出来，
     * 直接取必然是 null。但 post 出去的任务会在 handleBindApplication 返回之后才执行，
     * 到那时对象已经在了。
     *
     * 用轮询不用单次：Application 什么时候建出来没法预知，多试几次代价极低。
     */
    private void pollForApplication() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int left = Constants.SystemUi.APP_POLL_RETRIES;

            @Override
            public void run() {
                if (injected || fallbackScheduled) {
                    return;
                }
                Context app = currentApplication();
                if (app != null) {
                    LogUtils.xposed(ModuleMain.this, Log.INFO,
                            "poll got application: " + app.getClass().getName());
                    scheduleFallbackInject(app);
                    return;
                }
                if (left-- > 0) {
                    handler.postDelayed(this, Constants.SystemUi.APP_POLL_INTERVAL_MS);
                } else {
                    LogUtils.xposed(ModuleMain.this, Log.ERROR,
                            "poll gave up: currentApplication() stayed null");
                }
            }
        });
    }

    /** 反射取当前进程的 Application，取不到返回 null */
    private Context currentApplication() {
        try {
            Class<?> at = Class.forName(Constants.SystemUi.CLASS_ACTIVITY_THREAD);
            Method current = at.getMethod(Constants.SystemUi.METHOD_CURRENT_APPLICATION);
            Object app = current.invoke(null);
            return (app instanceof Context) ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 延迟试一把兜底注入；加窗失败就按 FALLBACK_INJECT_RETRY_MS 再来几次。
     *
     * 开机早期 WindowManagerService 未必肯接受 SystemUI 的新窗口，一次不成就放弃的话
     * 监视条永远出不来，所以必须重试。
     *
     * 重试用完之后还没注入成功，就转成低频长期重试，而不是彻底放弃：安卓 17 上
     * SystemUI 可能在开机很久之后才具备加窗条件（也可能中途被系统重启过），
     * 彻底放弃就意味着非得手动重启 SystemUI 才能恢复。低频探测每次只是一次 addView
     * 尝试，代价可以忽略。
     */
    private void scheduleFallbackInject(Context appContext) {
        if (fallbackScheduled) {
            return;
        }
        fallbackScheduled = true;
        LogUtils.xposed(this, Log.INFO, "scheduleFallbackInject: delay="
                + Constants.SystemUi.FALLBACK_INJECT_DELAY_MS + "ms context="
                + appContext.getClass().getName());
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (injected) {
                    return;
                }
                LogUtils.xposed(ModuleMain.this, Log.INFO,
                        "Fallback inject attempt, retries left=" + fallbackRetriesLeft);
                injectMonitorView(appContext, null);
                if (injected) {
                    return;
                }
                long delay = fallbackRetriesLeft-- > 0
                        ? Constants.SystemUi.FALLBACK_INJECT_RETRY_MS
                        : Constants.SystemUi.FALLBACK_INJECT_IDLE_MS;
                handler.postDelayed(this, delay);
            }
        }, Constants.SystemUi.FALLBACK_INJECT_DELAY_MS);
    }

    private static Method findOnViewCreated(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (Constants.SystemUi.METHOD_ON_VIEW_CREATED.equals(m.getName())
                    && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0] == View.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * 建 MonitorView 放进独立悬浮窗；悬浮窗建不起来时，要是手上有状态栏 View 树，
     * 就退回加到 R.id.status_bar 上。
     *
     * context 是建窗用的 Context，不能为 null；root 是状态栏 Fragment 的根 View，
     * 兜底路径下是 null，那种情况只走悬浮窗。
     */
    private void injectMonitorView(Context context, View root) {
        if (context == null) {
            LogUtils.xposed(this, Log.ERROR, "injectMonitorView: context is null, give up");
            return;
        }
        LogUtils.xposed(this, Log.INFO, "injectMonitorView: context=" + context
                + " root=" + root);

        FrameLayout statusBar = null;
        if (root != null) {
            View statusBarRoot =
                    ReflectUtils.findViewByIdName(root, Constants.SystemUi.ID_STATUS_BAR);
            if (statusBarRoot instanceof FrameLayout) {
                statusBar = (FrameLayout) statusBarRoot;
            } else {
                // 找不到 status_bar 也不直接放弃：悬浮窗用不着它，
                // 只是没法用退化模式和反色桥接而已
                LogUtils.xposed(this, Log.WARN,
                        "status_bar not found, overlay-only mode: " + statusBarRoot);
            }
        }

        // 重复注入的话先把旧实例摘掉（悬浮窗和状态栏两条路径都得清）
        if (tintBridge != null) {
            tintBridge.detach();
        }
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        if (monitorView != null && monitorView.getParent() instanceof ViewGroup) {
            ((ViewGroup) monitorView.getParent()).removeView(monitorView);
        }
        injected = false;

        // 把模块进程唤醒，保证 GPU root 采集和 Remote 写入在跑
        // 必须等用户解锁之后才能拉起来（免得 Direct Boot 下系统把 Provider 查询堵住）
        wakeCollectorSafely(context);

        monitorView = new MonitorView(context);
        // 先按用户设定的字号来，后面窗口/布局高度才能按实际字号算
        monitorView.setFontSizeSp(NexusBridge.getFontSizeSp());
        overlay = new MonitorOverlay(context);

        // 悬浮窗独立于状态栏窗口，全屏横屏下也不会被一起藏掉
        if (overlay.start(monitorView)) {
            injected = true;
            LogUtils.xposed(this, Log.INFO, "MonitorView shown in overlay window");
        } else if (statusBar != null) {
            LogUtils.xposed(this, Log.WARN, "Overlay unavailable, falling back to status_bar child");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    monitorView.getBarHeightPx());
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            monitorView.setLayoutParams(lp);
            statusBar.addView(monitorView);
            // 退化模式下两个开关照样生效（只切 visibility）
            overlay.startFallback(monitorView);
            injected = true;
        } else {
            // 悬浮窗没建成、又没状态栏可挂：让 injected 保持 false，留给兜底路径重试
            LogUtils.xposed(this, Log.ERROR, "Overlay failed and no status_bar available");
            overlay.stop();
            overlay = null;
            monitorView = null;
            return;
        }

        // 反色桥接不依赖状态栏 View：DarkIconDispatcher 是从 Dependency 里取的，
        // statusBar 只用来首次采样参考图标的颜色（传 null 就跳过采样）。
        // 必须无条件注册——兜底注入那条路上 root 是 null，要是要求 statusBar 非空，
        // 「自动反色」就永远拿不到系统 tint，只能一直白字
        if (tintBridge != null) {
            tintBridge.attach(monitorView, statusBar);
        }

        LogUtils.xposed(this, Log.INFO, "MonitorView injected");
    }

    private void wakeCollectorSafely(Context context) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager != null && userManager.isUserUnlocked()) {
            GpuCollectorLauncher.wakeFromExternal(context);
        } else {
            LogUtils.xposed(this, Log.INFO, "Device locked, waiting for USER_UNLOCKED to wake collector");
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                        LogUtils.xposed(ModuleMain.this, Log.INFO, "USER_UNLOCKED received, waking collector");
                        GpuCollectorLauncher.wakeFromExternal(ctx);
                        try {
                            ctx.unregisterReceiver(this);
                        } catch (Throwable t) {
                            // Ignore
                        }
                    }
                }
            };
            context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }
    }

    /**
     * MIUI 12 / A12：盯着图标 tint 的变化，同步给监视器。
     */
    private void hookMiui12StatusBarTint(ClassLoader classLoader) {
        try {
            Class<?> impl = Class.forName(Constants.SystemUi.CLASS_DARK_DISPATCHER_IMPL, false, classLoader);
            for (Method m : impl.getDeclaredMethods()) {
                String name = m.getName();
                if (!Constants.SystemUi.METHOD_APPLY_ICON_TINT.equals(name)
                        && !Constants.SystemUi.METHOD_APPLY_DARK_INTENSITY.equals(name)) {
                    continue;
                }
                if (m.getParameterTypes().length > 1) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    if (tintBridge != null) {
                        tintBridge.onDispatcherTint(chain.getThisObject());
                    }
                    return result;
                });
                LogUtils.xposed(this, Log.INFO, "Hooked DarkIconDispatcherImpl." + name);
            }
        } catch (Throwable t) {
            LogUtils.xposed(this, Log.WARN, "DarkIconDispatcherImpl hook skipped", t);
        }

        try {
            Class<?> iconView = Class.forName(Constants.SystemUi.CLASS_STATUS_BAR_ICON_VIEW, false, classLoader);
            for (Method m : iconView.getDeclaredMethods()) {
                if (!Constants.SystemUi.METHOD_ON_DARK_CHANGED.equals(m.getName())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 3 || params[1] != float.class || params[2] != int.class) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    if (tintBridge != null) {
                        tintBridge.onIconDarkChanged((Integer) chain.getArg(2));
                    }
                    return result;
                });
                LogUtils.xposed(this, Log.INFO, "Hooked StatusBarIconView.onDarkChanged");
                break;
            }
        } catch (Throwable t) {
            LogUtils.xposed(this, Log.WARN, "StatusBarIconView hook skipped", t);
        }
    }
}
