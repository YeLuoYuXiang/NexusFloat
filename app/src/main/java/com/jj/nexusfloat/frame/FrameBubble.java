package com.jj.nexusfloat.frame;

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;
import com.jj.nexusfloat.utils.RootShell;

/**
 * 帧记录的悬浮控制球。
 *
 * 为什么走 SYSTEM_ALERT_WINDOW 而不是画进 SystemUI：记录采样跑在本模块自己的
 * 进程里（取数器、数据库都在这儿），控制球跟着采样走才是同一个生命周——点球
 * 直接切换记录状态，不经过任何跨进程通道。代价是要「显示在其他应用上层」权限，
 * 本模块有 root，没有授权时先试着用 appops 静默授一次，不行再把用户领到设置页。
 *
 * 交互（全部由自己处理，不依赖系统手势）：
 * - 点按：未记录 → 开始记录；记录中 → 停止并保存
 * - 拖动：挪位置，松手后位置存 prefs，下次出现还在原地
 * - 长按：收掉悬浮球并停服务（记录中的会先保存）
 *
 * 外观：一个圆角长方形（胶囊，44dp 宽 / 22dp 高）。未记录时棕黄
 * （Constants.Frame.BUBBLE_IDLE_COLOR）并写小字 fps，记录中变红、显示实时
 * 帧率整数并带一圈沿矩形描边的闪烁提示环——用户在游戏里瞄一眼就知道还在不在记。
 *
 * 坐标存的是比例不是像素：横屏拖到最右边存的是 x=1.0，
 * 竖屏回来按竖屏的可移动范围换算，球还在右边沿，不会跑到屏幕外。转屏时靠
 * DisplayManager 的回调重新落位，见 {@link #reposition()}。
 */
public final class FrameBubble {

    private static final String TAG = "FrameBubble";
    /** 旧版存的绝对像素，只用于读老数据做一次换算 */
    private static final String KEY_X = "frame_bubble_x";
    private static final String KEY_Y = "frame_bubble_y";
    /** 现在存的比例（0..1，相对可移动范围） */
    private static final String KEY_FX = "frame_bubble_fx";
    private static final String KEY_FY = "frame_bubble_fy";
    /** 没存过位置时的默认点：右侧、纵向 28%，别正好压在游戏血条上 */
    private static final float DEFAULT_FX = 1f;
    private static final float DEFAULT_FY = 0.28f;
    /** 转屏后隔这么多毫秒再补一次落位：个别 ROM 的 DisplayInfo 晚一拍才更新 */
    private static final long REPOSITION_RETRY_MS = 350;

    public interface Callback {
        /** 点按：切换记录状态 */
        void onTap();
        /** 长按：收掉悬浮球（记录中的先保存） */
        void onDismiss();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BallView view;
    private Callback callback;
    private boolean showing;

    /** 转屏落位任务：立即来一次，稍后再补一次（个别 ROM 的尺寸晚一拍才更新） */
    private final Runnable repositionTask = new Runnable() {
        @Override
        public void run() {
            reposition();
        }
    };

    /**
     * 屏幕变了就落位。转屏、折叠屏展开合上都会走这里——悬浮窗是我们自己
     * addView 的，系统不会替我们把坐标收回屏幕内。
     */
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    main.post(repositionTask);
                    main.postDelayed(repositionTask, REPOSITION_RETRY_MS);
                }
            };

    /** 兜底通道：App 进程收到配置变更时也落一次位 */
    private final ComponentCallbacks configCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            main.postDelayed(repositionTask, 120);
        }

        @Override
        public void onLowMemory() {
        }
    };
    private boolean listenersOn;

    public FrameBubble(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    // ======================= 权限 =======================

    /** 悬浮窗权限有没有就位 */
    public static boolean canShow(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * 尽力把悬浮窗权限弄到位。
     *
     * 本模块的核心能力就是 root，所以先试 `appops set` 静默授权（大多数 ROM 通）；
     * 不通才让调用方把用户领到设置页手动开。返回 true 表示现在就能显示。
     */
    public static boolean ensurePermission(Context context) {
        if (Settings.canDrawOverlays(context)) {
            return true;
        }
        try {
            RootShell.get().exec("appops set " + context.getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow");
        } catch (Throwable t) {
            LogUtils.w(TAG + " appops grant failed", t);
        }
        return Settings.canDrawOverlays(context);
    }

    // ======================= 显隐 =======================

    /** 显示悬浮球。已经显示就只换回调 */
    public void show(Callback callback) {
        this.callback = callback;
        if (showing) {
            return;
        }
        try {
            view = new BallView(context);
            WindowManager.LayoutParams lp = buildParams();
            windowManager.addView(view, lp);
            showing = true;
            registerListeners();
        } catch (Throwable t) {
            // 权限被 ROM 收回、或者窗口 token 失效都会走到这儿；
            // 悬浮球失败不该拖垮记录本身，只把状态记下来
            LogUtils.w(TAG + " show failed", t);
            view = null;
            showing = false;
        }
    }

    public void hide() {
        main.removeCallbacksAndMessages(null);
        unregisterListeners();
        if (!showing || view == null) {
            showing = false;
            view = null;
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
        showing = false;
        view = null;
    }

    public boolean isShowing() {
        return showing;
    }

    /** 采样回调进来刷新球面：记录中的显示实时帧率 */
    public void update(final boolean recording, final float fps) {
        if (view == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (view != null) {
                    view.setState(recording, fps);
                }
            }
        });
    }

    private WindowManager.LayoutParams buildParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bubbleW(),
                bubbleH(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] pos = positionOnScreen();
        lp.x = pos[0];
        lp.y = pos[1];
        return lp;
    }

    /** 显示期间才挂监听：球都没了就不用管屏幕怎么转 */
    private void registerListeners() {
        if (listenersOn) {
            return;
        }
        listenersOn = true;
        try {
            DisplayManager dm =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                dm.registerDisplayListener(displayListener, main);
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " register display listener failed", t);
        }
        try {
            context.registerComponentCallbacks(configCallbacks);
        } catch (Throwable t) {
            LogUtils.w(TAG + " register config callbacks failed", t);
        }
    }

    private void unregisterListeners() {
        if (!listenersOn) {
            return;
        }
        listenersOn = false;
        try {
            DisplayManager dm =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                dm.unregisterDisplayListener(displayListener);
            }
        } catch (Throwable ignored) {
        }
        try {
            context.unregisterComponentCallbacks(configCallbacks);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当前屏幕尺寸。
     *
     * 不能用 `context.getResources().getDisplayMetrics()`：那是 App 的配置，
     * 转屏后要等系统把配置推过来才更新，回调里读到的还是旧值。悬浮窗的坐标系
     * 就是整块屏幕（含状态栏与导航栏），直接问 Display 最准。
     */
    private int[] screenSize() {
        try {
            DisplayManager dm =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                Point p = new Point();
                d.getRealSize(p);
                if (p.x > 0 && p.y > 0) {
                    return new int[]{p.x, p.y};
                }
            }
        } catch (Throwable t) {
            LogUtils.w(TAG + " screenSize failed", t);
        }
        android.util.DisplayMetrics m = context.getResources().getDisplayMetrics();
        return new int[]{m.widthPixels, m.heightPixels};
    }

    /**
     * 读上次停留的位置，返回「比例」（相对可移动范围）。
     *
     * 老版本存的是绝对像素：这里按当前屏幕换算一次，越界的一律贴边——
     * 升级后第一次打开，横屏时留在屏幕外的球会自动回到可见范围。
     */
    private float[] loadFractions(int maxX, int maxY) {
        SharedPreferences p = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
        if (p.contains(KEY_FX) && p.contains(KEY_FY)) {
            return new float[]{
                    clamp01(p.getFloat(KEY_FX, DEFAULT_FX)),
                    clamp01(p.getFloat(KEY_FY, DEFAULT_FY))};
        }
        int oldX = p.getInt(KEY_X, Integer.MIN_VALUE);
        int oldY = p.getInt(KEY_Y, Integer.MIN_VALUE);
        if (oldX != Integer.MIN_VALUE && oldY != Integer.MIN_VALUE) {
            return new float[]{
                    clamp01(oldX / (float) Math.max(1, maxX)),
                    clamp01(oldY / (float) Math.max(1, maxY))};
        }
        // 没存过：贴右边但留出足够边距，纵向 28%。
        //
        // 原来留 12dp，横屏时球会顶到屏幕最右边（甚至被状态栏图标压住）；
        // 球本身又加宽了，边距得跟着放大。40dp 在竖屏和横屏下都留得出余量
        float fx = maxX > 0
                ? Math.max(0f, (maxX - dp(40f)) / (float) maxX)
                : DEFAULT_FX;
        return new float[]{fx, DEFAULT_FY};
    }

    /** 把比例换算成当前屏幕下的像素坐标，并夹进可见范围 */
    private int[] positionOnScreen() {
        int[] screen = screenSize();
        int maxX = Math.max(0, screen[0] - bubbleW());
        int maxY = Math.max(0, screen[1] - bubbleH());
        float[] f = loadFractions(maxX, maxY);
        int x = Math.round(f[0] * maxX);
        int y = Math.round(f[1] * maxY);
        return new int[]{
                Math.max(0, Math.min(maxX, x)),
                Math.max(0, Math.min(maxY, y))};
    }

    /**
     * 屏幕尺寸变了：按比例把球搬回可见范围。
     *
     * 横屏拖到最右边存的是 fx=1.0，竖屏回来落到竖屏的右边沿——不用再把手机
     * 横过来捞球（这一版修的就是这个）。
     */
    private void reposition() {
        if (!showing || view == null) {
            return;
        }
        try {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) view.getLayoutParams();
            if (lp == null) {
                return;
            }
            int[] pos = positionOnScreen();
            if (lp.x == pos[0] && lp.y == pos[1]) {
                return;
            }
            lp.x = pos[0];
            lp.y = pos[1];
            windowManager.updateViewLayout(view, lp);
        } catch (Throwable t) {
            LogUtils.w(TAG + " reposition failed", t);
        }
    }

    private void savePosition(int x, int y) {
        int[] screen = screenSize();
        int maxX = Math.max(1, screen[0] - bubbleW());
        int maxY = Math.max(1, screen[1] - bubbleH());
        // 比例是主数据；绝对像素只留给旧版本读，向后兼容
        context.getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_FX, clamp01(x / (float) maxX))
                .putFloat(KEY_FY, clamp01(y / (float) maxY))
                .putInt(KEY_X, x)
                .putInt(KEY_Y, y)
                .apply();
    }

    private int bubbleW() {
        return dp(Constants.Frame.BUBBLE_WIDTH_DP);
    }

    private int bubbleH() {
        return dp(Constants.Frame.BUBBLE_HEIGHT_DP);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    // ======================= 球本体 =======================

    /**
     * 自己画的胶囊悬浮球：不用布局文件也不用图标，一个 Canvas 全搞定。
     * 四十几 dp 的小东西引一套 Compose/布局 inflater 纯属浪费。
     */
    private final class BallView extends View {

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean recording;
        private float fps;

        /** 手势状态 */
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;
        private boolean longPressFired;


        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                if (callback != null) {
                    callback.onDismiss();
                }
            }
        };

        BallView(Context c) {
            super(c);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        void setState(boolean recording, float fps) {
            this.recording = recording;
            this.fps = fps;
            // 闪烁已经去掉，这里不再安排脉冲任务
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            // 胶囊：圆角取半高，宽 44dp / 高 22dp 就是圆角 11dp 的长方形
            float corner = h / 2f;
            // 背景往里收一点，别让填充色贴到窗口边缘被裁
            float bgInset = dp(2);

            bg.setStyle(Paint.Style.FILL);
            bg.setColor(recording
                    ? Constants.Frame.BUBBLE_RECORDING_COLOR
                    : Constants.Frame.BUBBLE_IDLE_COLOR);
            canvas.drawRoundRect(
                    bgInset, bgInset, w - bgInset, h - bgInset,
                    corner - bgInset, corner - bgInset, bg);

            // 记录中不再画闪烁描边：原来那圈一明一暗，远看像球在缩放，盯久了累。
            // 现在状态只靠底色区分（黄色未记录 / 红色记录中），更干净

            // 球上统一显示当前帧率，保留一位小数。未记录时那次读到的帧率就是
            // 当前屏幕的实时值，直接显示比写 "fps" 有用
            String label = fps >= 0f
                    ? String.format(java.util.Locale.US, "%.1f", fps)
                    : "--";
            // 字号自适应，绝不超出胶囊：
            // 先按一个基准字号量出文本宽度，等比缩到可用宽度以内；
            // 再用可用高度封顶，免得字号大到顶穿上下边。
            // 可用区域是球宽/高各扣掉左右各 3dp 的内边距
            float availW = w - dp(6);
            float availH = h - dp(6);
            text.setTextSize(dp(15));
            float measured = text.measureText(label);
            float fitByWidth = measured > 0f ? text.getTextSize() * availW / measured : dp(15);
            float fitByHeight = availH * 0.72f;
            text.setTextSize(Math.min(fitByWidth, fitByHeight));

            float cx = w / 2f;
            float cy = h / 2f;
            float textY = cy - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(label, cx, textY, text);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = getWindowParams().x;
                    startY = getWindowParams().y;
                    dragging = false;
                    longPressFired = false;
                    main.postDelayed(longPress, Constants.Frame.BUBBLE_LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy)
                            > Constants.Frame.BUBBLE_TOUCH_SLOP_PX) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        WindowManager.LayoutParams lp = getWindowParams();
                        if (lp != null) {
                            lp.x = clampX(startX + (int) dx);
                            lp.y = clampY(startY + (int) dy);
                            try {
                                windowManager.updateViewLayout(BallView.this, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (!dragging && !longPressFired
                            && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (callback != null) {
                            callback.onTap();
                        }
                        return true;
                    }
                    if (dragging) {
                        WindowManager.LayoutParams lp = getWindowParams();
                        if (lp != null) {
                            savePosition(lp.x, lp.y);
                        }
                    }
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        private WindowManager.LayoutParams getWindowParams() {
            try {
                return (WindowManager.LayoutParams) getLayoutParams();
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 转屏时系统会把新配置推给窗口里的 View，顺手把球拉回可见范围。
         * 真正的兜底是 DisplayManager 的监听，这里只是多一层保险。
         */
        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            main.postDelayed(repositionTask, 80);
        }

        private int ballW() {
            return getWidth() > 0 ? getWidth() : bubbleW();
        }

        private int ballH() {
            return getHeight() > 0 ? getHeight() : bubbleH();
        }

        /** 横向别拖出屏幕：左右各留半个球的余量 */
        private int clampX(int x) {
            int max = Math.max(0, screenSize()[0] - ballW());
            return Math.max(0, Math.min(max, x));
        }

        /** 纵向同理。原来只挡了上边，往下拖出屏幕就同样捞不回来了 */
        private int clampY(int y) {
            int max = Math.max(0, screenSize()[1] - ballH());
            return Math.max(0, Math.min(max, y));
        }
    }
}
