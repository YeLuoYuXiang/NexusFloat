package com.jj.nexusfloat.utils;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射，外加按资源名找 View。SystemUI 注入用得上：那边没有编译期的 R.id 可引用。
 */
public final class ReflectUtils {

    private ReflectUtils() {}

    public static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    public static Object getObjectField(Object obj, String fieldName) throws Exception {
        return getField(obj.getClass(), fieldName).get(obj);
    }

    public static int getIntField(Object obj, String fieldName) throws Exception {
        return getField(obj.getClass(), fieldName).getInt(obj);
    }

    /** 拿来调 Dependency.get(Class) 这类静态单参方法 */
    public static Object callStaticNoArgs(Class<?> clazz, String methodName, Object arg) throws Exception {
        Method method = clazz.getMethod(methodName, Class.class);
        return method.invoke(null, arg);
    }

    /**
     * 在 SystemUI 的包名下按字符串 id 名找子 View，比如 "status_bar"。
     */
    public static View findViewByIdName(View parent, String idName) {
        if (parent == null || idName == null) {
            return null;
        }
        int resId = parent.getResources().getIdentifier(
                idName, "id", parent.getContext().getPackageName());
        if (resId != 0) {
            return parent.findViewById(resId);
        }
        return null;
    }
}
