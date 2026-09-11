package com.jj.nexusfloat.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;

import com.jj.nexusfloat.constant.Constants;

import java.util.ArrayList;
import java.util.List;

/** CPU / GPU 的多核占用柱条，从下往上填 */
public class MultiCoreBarView extends View {

    private final Paint paint;
    private final List<Float> usages = new ArrayList<>();
    private int tintColor = Color.WHITE;

    public MultiCoreBarView(Context context) {
        super(context);
        paint = new Paint();
        paint.setAntiAlias(true);
    }

    public void setTintColor(int color) {
        if (tintColor != color) {
            tintColor = color;
            invalidate();
        }
    }

    public void updateUsages(List<Float> newUsages) {
        if (newUsages == null) {
            return;
        }
        usages.clear();
        usages.addAll(newUsages);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0 || usages.isEmpty()) {
            return;
        }

        int count = usages.size();
        float spacing = Constants.Ui.BAR_SPACING_PX;
        float totalSpacing = spacing * (count - 1);
        float barWidth = (width - totalSpacing) / count;
        if (barWidth <= 0) {
            barWidth = 1f;
            spacing = 0f;
        }

        // 底槽用 tintColor 的半透明版：白字的时候是浅灰槽，深字（自动反色、
        // 浅色背景）的时候是深灰槽，这两种情况下槽位都看得见
        int colorBg = Color.argb(0x50,
                Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor));

        float currentX = 0;
        for (int i = 0; i < count; i++) {
            float usage = usages.get(i);

            paint.setColor(colorBg);
            canvas.drawRect(currentX, 0, currentX + barWidth, height, paint);

            float fillHeight = (usage / 100f) * height;
            fillHeight = Math.max(0, Math.min(fillHeight, height));
            float topY = height - fillHeight;

            int colorNormal = Constants.Ui.COLOR_BAR_NORMAL;
            int colorHigh = Constants.Ui.COLOR_BAR_HIGH;
            paint.setColor(usage >= Constants.Ui.BAR_HIGH_USAGE_THRESHOLD ? colorHigh : colorNormal);
            canvas.drawRect(currentX, topY, currentX + barWidth, height, paint);

            currentX += barWidth + spacing;
        }
    }
}
