package io.github.electroheavenvn.zaloxposed.hooks.permanent;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.VersionedPackage;
import android.os.Build;

import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.Common;
import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Pure-Java replacement for libZaloXposedNative's package-name & installer spoofing
 * on cloned Morphe builds (com.zing.zalo.morphe), without any anti-rebranding exit timer.
 */
public class PackageAndSignatureSpoofHook extends BaseHook
{
    private static final String ORIG_PKG = "com.zing.zalo";

    @Override
    public void installHooks() throws Throwable
    {
        String currentPkg = Common.getPackageName();
        if (currentPkg == null || currentPkg.equals(ORIG_PKG))
            return;

        try
        {
            Class<?> appPmCls = Class.forName("android.app.ApplicationPackageManager", false, classLoader);

            try
            {
                Method getPkgInfo = appPmCls.getDeclaredMethod("getPackageInfo", String.class, int.class);
                doHook(getPkgInfo, 10, chain ->
                {
                    String reqPkg = (String) chain.getArg(0);
                    if (ORIG_PKG.equals(reqPkg))
                    {
                        Object res = chain.proceed(new Object[] { currentPkg, chain.getArg(1) });
                        if (res instanceof PackageInfo pi)
                            pi.packageName = ORIG_PKG;
                        return res;
                    }
                    return chain.proceed();
                });
            }
            catch (Throwable t) { Logger.e(t); }

            if (Build.VERSION.SDK_INT >= 26)
            {
                try
                {
                    Method getVerPkgInfo = appPmCls.getDeclaredMethod("getPackageInfo", VersionedPackage.class, int.class);
                    doHook(getVerPkgInfo, 10, chain ->
                    {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof VersionedPackage vp && ORIG_PKG.equals(vp.getPackageName()))
                        {
                            VersionedPackage mapped = new VersionedPackage(currentPkg, vp.getLongVersionCode());
                            Object res = chain.proceed(new Object[] { mapped, chain.getArg(1) });
                            if (res instanceof PackageInfo pi)
                                pi.packageName = ORIG_PKG;
                            return res;
                        }
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }

            try
            {
                Method getAppInfo = appPmCls.getDeclaredMethod("getApplicationInfo", String.class, int.class);
                doHook(getAppInfo, 10, chain ->
                {
                    String reqPkg = (String) chain.getArg(0);
                    if (ORIG_PKG.equals(reqPkg))
                    {
                        Object res = chain.proceed(new Object[] { currentPkg, chain.getArg(1) });
                        if (res instanceof ApplicationInfo ai)
                            ai.packageName = ORIG_PKG;
                        return res;
                    }
                    return chain.proceed();
                });
            }
            catch (Throwable t) { Logger.e(t); }

            try
            {
                Method getInstaller = appPmCls.getDeclaredMethod("getInstallerPackageName", String.class);
                doHook(getInstaller, 10, chain ->
                {
                    String reqPkg = (String) chain.getArg(0);
                    if (ORIG_PKG.equals(reqPkg) || currentPkg.equals(reqPkg))
                        return "com.android.vending";
                    return chain.proceed();
                });
            }
            catch (Throwable t) { Logger.e(t); }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }
}
