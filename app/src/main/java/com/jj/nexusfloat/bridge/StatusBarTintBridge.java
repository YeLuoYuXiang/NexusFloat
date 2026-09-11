package com.jj.nexusfloat.bridge;

import android.content.res.ColorStateList;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.ui.view.MonitorView;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.ReflectUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * SystemUI 状态栏图标反色桥接（Android 12 / MIUI 13）。
 *
 * 监视器在状态栏中部，不在左右 icon 的 tintAreas 里，所以不能对 MonitorView 调
 * getTint(areas, view)（那样恒为白色），得直接用系统下发的 tint。
 */
public final class StatusBarTintBridge {

    private final ClassLoader classLoader;
    private MonitorView monitorView;
    private Object dispatcher;
    private Object darkReceiver;
    private int lastTint = Constants.Config.UNPUBLISHED;

    public StatusBarTintBridge(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * 注册 DarkReceiver，顺便从 dispatcher / 旁边的图标采一下初始颜色。
     */
    public void attach(MonitorView view, View statusBarRoot) {
        detach();
        monitorView = view;
        lastTint = Constants.Config.UNPUBLISHED;

        try {
            Class<?> iface = classLoader.loadClass(Constants.SystemUi.CLASS_DARK_DISPATCHER_POLICY);
            dispatcher = ReflectUtils.callStaticNoArgs(
                    classLoader.loadClass(Constants.SystemUi.CLASS_DEPENDENCY),
                    Constants.SystemUi.METHOD_DEPENDENCY_GET,
                    iface);
            if (dispatcher == null) {
                LogUtils.w("DarkIconDispatcher null from Dependency");
            } else {
                registerDarkReceiver();
            }
        } catch (Throwable t) {
            LogUtils.w("DarkIconDispatcher register failed", t);
        }

        view.post(() -> {
            syncFromDispatcherFields();
            syncFromReferenceIcon(statusBarRoot);
        });
    }

    /** 摘掉 DarkReceiver，免得泄漏 */
    public void detach() {
        if (dispatcher != null && darkReceiver != null) {
            try {
                Class<?> receiverClass = classLoader.loadClass(Constants.SystemUi.CLASS_DARK_RECEIVER_POLICY);
                Method remove = dispatcher.getClass().getMethod(
                        Constants.SystemUi.METHOD_REMOVE_DARK_RECEIVER, receiverClass);
                remove.invoke(dispatcher, darkReceiver);
            } catch (Throwable t) {
                LogUtils.w("removeDarkReceiver failed", t);
            }
        }
        darkReceiver = null;
        dispatcher = null;
        monitorView = null;
        lastTint = Constants.Config.UNPUBLISHED;
    }

    /** applyIconTint / applyDarkIntensity 之后同步一下 mIconTint */
    public void onDispatcherTint(Object dispatcherImpl) {
        if (dispatcherImpl == null) {
            return;
        }
        int tint = readIntField(dispatcherImpl, Constants.SystemUi.FIELD_ICON_TINT, Constants.Config.UNPUBLISHED);
        if (tint != Constants.Config.UNPUBLISHED) {
            applyTint(tint);
        }
    }

    /** StatusBarIconView.onDarkChanged 传进来的 tint */
    public void onIconDarkChanged(int tint) {
        applyTint(tint);
    }

    private void registerDarkReceiver() throws Exception {
        Class<?> receiverClass = classLoader.loadClass(Constants.SystemUi.CLASS_DARK_RECEIVER_POLICY);
        darkReceiver = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{receiverClass},
                new DarkReceiverHandler());
        Method add = dispatcher.getClass().getMethod(
                Constants.SystemUi.METHOD_ADD_DARK_RECEIVER, receiverClass);
        add.invoke(dispatcher, darkReceiver);
        try {
            Method applyDark = dispatcher.getClass().getMethod(
                    Constants.SystemUi.METHOD_APPLY_DARK, receiverClass);
            applyDark.invoke(dispatcher, darkReceiver);
        } catch (NoSuchMethodException ignored) {
        }
        LogUtils.i("Registered DarkIconDispatcher receiver");
    }

    private void syncFromDispatcherFields() {
        if (dispatcher == null) {
            return;
        }
        int tint = readIntField(dispatcher, Constants.SystemUi.FIELD_ICON_TINT, Constants.Config.UNPUBLISHED);
        if (tint != Constants.Config.UNPUBLISHED) {
            applyTint(tint);
        }
    }

    /** 从时钟、电量这些已有图标的 imageTint 采样，首次对齐用 */
    private void syncFromReferenceIcon(View statusBarRoot) {
        if (statusBarRoot == null) {
            return;
        }
        int color = sampleIconTint(statusBarRoot);
        if (color != 0) {
            applyTint(color);
        }
    }

    private int sampleIconTint(View root) {
        for (String id : Constants.SystemUi.ID_TINT_REFERENCE) {
            View v = ReflectUtils.findViewByIdName(root, id);
            if (v instanceof ViewGroup) {
                int c = sampleTintFromGroup((ViewGroup) v);
                if (c != 0) {
                    return c;
                }
            } else if (v instanceof ImageView) {
                int c = readImageTint((ImageView) v);
                if (c != 0) {
                    return c;
                }
            }
        }
        if (root instanceof ViewGroup) {
            return sampleTintFromGroup((ViewGroup) root);
        }
        return 0;
    }

    private static int sampleTintFromGroup(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                int c = readImageTint((ImageView) child);
                if (c != 0) {
                    return c;
                }
            }
            if (child instanceof ViewGroup) {
                int c = sampleTintFromGroup((ViewGroup) child);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static int readImageTint(ImageView imageView) {
        ColorStateList csl = imageView.getImageTintList();
        return csl != null ? csl.getDefaultColor() : 0;
    }

    private void applyTint(int tint) {
        MonitorView view = monitorView;
        if (view == null || tint == lastTint) {
            return;
        }
        lastTint = tint;
        view.post(() -> view.onColorsChanged(tint));
    }

    private static int readIntField(Object obj, String[] names, int fallback) {
        for (String name : names) {
            try {
                return ReflectUtils.getIntField(obj, name);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private final class DarkReceiverHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (!Constants.SystemUi.METHOD_ON_DARK_CHANGED.equals(method.getName())
                    || args == null || args.length < 3) {
                return null;
            }
            try {
                if (args[1] instanceof Float) {
                    onIconDarkChanged((Integer) args[2]);
                }
            } catch (Throwable t) {
                LogUtils.w("DarkReceiver callback failed", t);
            }
            return null;
        }
    }
}
