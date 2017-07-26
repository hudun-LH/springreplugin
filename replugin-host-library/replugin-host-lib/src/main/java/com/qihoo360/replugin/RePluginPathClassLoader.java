/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo360.replugin;

import android.os.Build;

import com.qihoo360.replugin.utils.ReflectUtils;
import com.qihoo360.loader.utils.StringUtils;
import com.qihoo360.loader2.PluginMgrFacade;
import com.qihoo360.replugin.base.IPC;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Enumeration;

import dalvik.system.PathClassLoader;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * 关键点：替换classloader,偷梁换柱
 * 宿主的ClassLoader，插件框架的核心之一
 * <p>
 * 注意：为了兼容Android 7.0以上的LoadedApk.updateApplicationInfo中，对addDexPath方法的依赖，
 * 特将继承关系调整到PathClassLoader，以前是ClassLoader
 *
 * @author RePlugin Team
 */
public class RePluginPathClassLoader extends PathClassLoader {

    private static final String TAG = "RePluginPathClassLoader";

    private final ClassLoader mOrig;

    /**
     * 用load系列代替
     */
    //private Method findClassMethod;

    private Method findResourceMethod;

    private Method findResourcesMethod;

    private Method findLibraryMethod;

    private Method getPackageMethod;

    public RePluginPathClassLoader(ClassLoader parent, ClassLoader orig) {

        // 由于PathClassLoader在初始化时会做一些Dir的处理，所以这里必须要传一些内容进来
        // 但我们最终不用它，而是拷贝所有的Fields
        super("", "", parent);
        mOrig = orig;

        // 将原来宿主里的关键字段，拷贝到这个对象上，这样骗系统以为用的还是以前的东西（尤其是DexPathList）
        // 注意，这里用的是“浅拷贝”
        // Added by Jiongxuan Zhang
        copyKeyFieldFromOriginal(orig);

        initMethods(orig);
    }

    private void initMethods(ClassLoader cl) {
        Class<?> c = cl.getClass();
        findResourceMethod = ReflectUtils.getMethod(c, "findResource", String.class);
        findResourceMethod.setAccessible(true);
        findResourcesMethod = ReflectUtils.getMethod(c, "findResources", String.class);
        findResourcesMethod.setAccessible(true);
        findLibraryMethod = ReflectUtils.getMethod(c, "findLibrary", String.class);
        findLibraryMethod.setAccessible(true);
        getPackageMethod = ReflectUtils.getMethod(c, "getPackage", String.class);
        getPackageMethod.setAccessible(true);
    }

    private void copyKeyFieldFromOriginal(ClassLoader orig) {
        if (LOG && IPC.isPersistentProcess()) {
            LogDebug.d(TAG, "copyKeyFieldFromOriginal: Fields=" + StringUtils.toStringWithLines(ReflectUtils.getAllFieldsList(orig.getClass())));
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) {
            // Android 2.2 - 2.3.7，有一堆字段，需要逐一复制
            // 以下方法在较慢的手机上用时：8ms左右
            copyFieldValue("libPath", orig);
            copyFieldValue("libraryPathElements", orig);
            copyFieldValue("mDexs", orig);
            copyFieldValue("mFiles", orig);
            copyFieldValue("mPaths", orig);
            copyFieldValue("mZips", orig);
        } else {
            // Android 4.0以上只需要复制pathList即可
            // 以下方法在较慢的手机上用时：1ms
            copyFieldValue("pathList", orig);
        }
    }

    private void copyFieldValue(String field, ClassLoader orig) {
        try {
            Field fieldFunc = ReflectUtils.getField(orig.getClass(), field);
            if (fieldFunc == null) {
                if (LOGR) {
                    LogRelease.e(TAG, "ReflectUtils.getField()=null  field=" + field);
                }
                return;
            }

            // 删除final修饰符
            ReflectUtils.removeFieldFinalModifier(fieldFunc);

            // 复制Field中的值到this里
            Object o = ReflectUtils.readField(fieldFunc, orig);
            ReflectUtils.writeField(fieldFunc, this, o);

            if (LOG) {
                Object test = ReflectUtils.readField(fieldFunc, this);
                LogDebug.d(TAG, "copyFieldValue: Copied. f=" + field + "; actually=" + test + "; orig=" + o);
            }
        } catch (IllegalAccessException e) {
            if (LOGR) {
                LogDebug.d(TAG, "rpcl.cfv: fail! f=" + field);
            }
        }
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        //
        Class<?> c = null;
        c = PluginMgrFacade.loadClass(className, resolve);
        if (c != null) {
            return c;
        }
        //
        try {
            c = mOrig.loadClass(className);
            // 只有开启“详细日志”才会输出，防止“刷屏”现象
            if (LogDebug.LOG && RePlugin.getConfig().isPrintDetailLog()) {
                LogDebug.d(TAG, "loadClass: load other class, cn=" + className);
            }
            return c;
        } catch (Throwable e) {
            //
        }
        //
        return super.loadClass(className, resolve);
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        // INFO Never reach here since override loadClass , unless not found class
        if (LOGR) {
            LogRelease.w(PLUGIN_TAG, "NRH lcl.fc: c=" + className);
        }
        return super.findClass(className);
    }

    @Override
    protected URL findResource(String resName) {
        try {
            return (URL) findResourceMethod.invoke(mOrig, resName);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return super.findResource(resName);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Enumeration<URL> findResources(String resName) {
        try {
            return (Enumeration<URL>) findResourcesMethod.invoke(mOrig, resName);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return super.findResources(resName);
    }

    @Override
    public String findLibrary(String libName) {
        try {
            return (String) findLibraryMethod.invoke(mOrig, libName);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return super.findLibrary(libName);
    }

    @Override
    protected Package getPackage(String name) {
        // 金立手机的某些ROM(F103,F103L,F303,M3)代码ClassLoader.getPackage去掉了关键的保护和错误处理(2015.11~2015.12左右)，会返回null
        // 悬浮窗某些draw代码触发getPackage(...).getName()，getName出现空指针解引，导致悬浮窗进程出现了大量崩溃
        // 此处实现和AOSP一致确保不会返回null
        // SONGZHAOCHUN, 2016/02/29
        if (name != null && !name.isEmpty()) {
            Package pack = null;
            try {
                pack = (Package) getPackageMethod.invoke(mOrig, name);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
            if (pack == null) {
                if (LOGR) {
                    LogRelease.w(PLUGIN_TAG, "NRH lcl.gp.1: n=" + name);
                }
                pack = super.getPackage(name);
            }
            if (pack == null) {
                if (LOGR) {
                    LogRelease.w(PLUGIN_TAG, "NRH lcl.gp.2: n=" + name);
                }
                return definePackage(name, "Unknown", "0.0", "Unknown", "Unknown", "0.0", "Unknown", null);
            }
            return pack;
        }
        return null;
    }

    @Override
    public String toString() {
        return getClass().getName() + "[mBase=" + mOrig.toString() + "]";
    }
}