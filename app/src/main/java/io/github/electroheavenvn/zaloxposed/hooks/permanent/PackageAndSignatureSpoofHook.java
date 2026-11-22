package io.github.electroheavenvn.zaloxposed.hooks.permanent;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Process;
import android.util.ArraySet;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import io.github.electroheavenvn.zaloxposed.Common;
import io.github.electroheavenvn.zaloxposed.MorpheConstants;
import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Pure-Java replacement for libZaloXposedNative's package-name, installer, and VNG signature
 * spoofing on Morphe/re-signed builds, without any anti-rebranding exit timer.
 */
public class PackageAndSignatureSpoofHook extends BaseHook
{
    private static final String ORIG_PKG = "com.zing.zalo";

    // Official VNG/Zalo (CN=zingtalk) public X.509 signing certificate (417 bytes DER)
    private static final String VNG_CERT_B64 =
        "MIIBnTCCAQagAwIBAgIETxeJcTANBgkqhkiG9w0BAQUFADATMREwDwYDVQQDEwh6" +
        "aW5ndGFsazAeFw0xMjAxMTkwMzA5MzdaFw0zNzAxMTIwMzA5MzdaMBMxETAPBgNV" +
        "BAMTCHppbmd0YWxrMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDY3IburM2N" +
        "f+ciORo6GuA0CCskrwymMkTS/xLMn9pNapwb3/XFh8ZIrD6Z5UhSylLO4BIDy5n1" +
        "lFk6seAjvNimvpseBWw95zYxxW+F9e2FduhQ9n3bygALUzhIHfI4oNJ8KTueKLaa" +
        "ziTJySYwYyI4MglMhSAQAbe+fyEHpFKDXwIDAQABMA0GCSqGSIb3DQEBBQUAA4GB" +
        "AK69ivJ/wxeLYILR23pfZqrR21XIIxRcXdIf5yHiKfkLdwJzhlRDK1xehmf0mV6d" +
        "IGrbfSbD23DyyXFjjUS3YkFt9dUQoIUm7ZH90cHo5nUdiDLsMhVMEWgOZH5gXA6G" +
        "EnAscFJDJKYRQkxpxNPkOidWVR7sX05N6WYzEZTHRISh";

    private static byte[] vngCertBytes;
    private static byte[] vngCertSha256;
    private static Signature vngSignature;
    private static PublicKey vngPublicKey;

    private static void initCert()
    {
        if (vngSignature != null)
            return;
        try
        {
            vngCertBytes = Base64.decode(VNG_CERT_B64, Base64.DEFAULT);
            vngCertSha256 = MessageDigest.getInstance("SHA-256").digest(vngCertBytes);
            vngSignature = new Signature(vngCertBytes);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(vngCertBytes));
            vngPublicKey = cert.getPublicKey();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @Override
    public void installHooks() throws Throwable
    {
        if (!MorpheConstants.isPatchedByMorphe())
            return;

        initCert();
        final String currentPkg = Common.getPackageName();
        final boolean isCloned = currentPkg != null && !currentPkg.equals(ORIG_PKG);

        try
        {
            Class<?> appPmCls = Class.forName("android.app.ApplicationPackageManager", false, classLoader);

            try
            {
                Method getPkgInfo = appPmCls.getDeclaredMethod("getPackageInfo", String.class, int.class);
                doHook(getPkgInfo, 10, chain ->
                {
                    String reqPkg = (String) chain.getArg(0);
                    int flags = (int) chain.getArg(1);
                    boolean isTarget = ORIG_PKG.equals(reqPkg) || (currentPkg != null && currentPkg.equals(reqPkg));
                    if (isTarget)
                    {
                        Object res = (isCloned && ORIG_PKG.equals(reqPkg))
                            ? chain.proceed(new Object[] { currentPkg, flags })
                            : chain.proceed();
                        if (res instanceof PackageInfo pi)
                        {
                            if (isCloned && ORIG_PKG.equals(reqPkg))
                                pi.packageName = ORIG_PKG;
                            spoofPackageInfoSignatures(pi, flags);
                        }
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
                        int flags = (int) chain.getArg(1);
                        if (arg0 instanceof VersionedPackage vp)
                        {
                            String reqPkg = vp.getPackageName();
                            boolean isTarget = ORIG_PKG.equals(reqPkg) || (currentPkg != null && currentPkg.equals(reqPkg));
                            if (isTarget)
                            {
                                Object res = (isCloned && ORIG_PKG.equals(reqPkg))
                                    ? chain.proceed(new Object[] { new VersionedPackage(currentPkg, vp.getLongVersionCode()), flags })
                                    : chain.proceed();
                                if (res instanceof PackageInfo pi)
                                {
                                    if (isCloned && ORIG_PKG.equals(reqPkg))
                                        pi.packageName = ORIG_PKG;
                                    spoofPackageInfoSignatures(pi, flags);
                                }
                                return res;
                            }
                        }
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }

            if (Build.VERSION.SDK_INT >= 28)
            {
                try
                {
                    Method hasCertStr = appPmCls.getDeclaredMethod("hasSigningCertificate", String.class, byte[].class, int.class);
                    doHook(hasCertStr, 10, chain ->
                    {
                        String reqPkg = (String) chain.getArg(0);
                        byte[] cert = (byte[]) chain.getArg(1);
                        int type = (int) chain.getArg(2);
                        if ((ORIG_PKG.equals(reqPkg) || (currentPkg != null && currentPkg.equals(reqPkg)))
                            && matchesVngCert(cert, type))
                            return true;
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }

                try
                {
                    Method hasCertUid = appPmCls.getDeclaredMethod("hasSigningCertificate", int.class, byte[].class, int.class);
                    doHook(hasCertUid, 10, chain ->
                    {
                        int uid = (int) chain.getArg(0);
                        byte[] cert = (byte[]) chain.getArg(1);
                        int type = (int) chain.getArg(2);
                        if (uid == Process.myUid() && matchesVngCert(cert, type))
                            return true;
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }

            if (isCloned)
            {
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
            }

            try
            {
                Method getInstaller = appPmCls.getDeclaredMethod("getInstallerPackageName", String.class);
                doHook(getInstaller, 10, chain ->
                {
                    String reqPkg = (String) chain.getArg(0);
                    if (ORIG_PKG.equals(reqPkg) || (currentPkg != null && currentPkg.equals(reqPkg)))
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

    private static boolean matchesVngCert(byte[] cert, int type)
    {
        if (cert == null)
            return false;
        if (type == PackageManager.CERT_INPUT_RAW_X509 && vngCertBytes != null)
            return Arrays.equals(cert, vngCertBytes);
        if (type == PackageManager.CERT_INPUT_SHA256 && vngCertSha256 != null)
            return Arrays.equals(cert, vngCertSha256);
        return false;
    }

    @SuppressWarnings("deprecation")
    private static void spoofPackageInfoSignatures(PackageInfo pi, int flags)
    {
        if (pi == null || vngSignature == null)
            return;

        if ((flags & PackageManager.GET_SIGNATURES) != 0 || pi.signatures != null)
            pi.signatures = new Signature[] { vngSignature };

        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null)
            spoofSigningInfo(pi.signingInfo);
    }

    private static void spoofSigningInfo(SigningInfo signingInfo)
    {
        try
        {
            Field detailsField = SigningInfo.class.getDeclaredField("mSigningDetails");
            detailsField.setAccessible(true);
            Object details = detailsField.get(signingInfo);
            if (details == null)
                return;

            Class<?> detailsCls = details.getClass();
            Signature[] sigArr = new Signature[] { vngSignature };

            try
            {
                Field sigsField = detailsCls.getDeclaredField("mSignatures");
                sigsField.setAccessible(true);
                sigsField.set(details, sigArr);
            }
            catch (Throwable ignored) { }

            try
            {
                Field pastField = detailsCls.getDeclaredField("mPastSigningCertificates");
                pastField.setAccessible(true);
                pastField.set(details, sigArr);
            }
            catch (Throwable ignored) { }

            if (vngPublicKey != null)
            {
                try
                {
                    Field pubField = detailsCls.getDeclaredField("mPublicKeys");
                    pubField.setAccessible(true);
                    ArraySet<PublicKey> keys = new ArraySet<>();
                    keys.add(vngPublicKey);
                    pubField.set(details, keys);
                }
                catch (Throwable ignored) { }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }
}
