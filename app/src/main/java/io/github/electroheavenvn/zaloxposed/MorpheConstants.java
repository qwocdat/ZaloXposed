package io.github.electroheavenvn.zaloxposed;

import java.util.zip.ZipFile;

import io.github.electroheavenvn.zaloxposed.utilities.Config;

public final class MorpheConstants
{
    private static final String MODULE_NAME = "ZaloxPosed X DigitalXiao";
    private static final String MORPHE_MODULE_NAME = "ZaloxPosed X DigitalXiao";
    private static volatile boolean patchedByMorphe = true;

    private MorpheConstants() { }

    public static void detect(String sourceDir, String packageName)
    {
        if (packageName != null && !packageName.equals("com.zing.zalo"))
        {
            patchedByMorphe = true;
            return;
        }
        if (sourceDir != null && !sourceDir.isEmpty())
        {
            try (ZipFile zf = new ZipFile(sourceDir))
            {
                patchedByMorphe = zf.getEntry("assets/npatch/config.json") != null
                    || zf.getEntry("assets/lspatch/config.json") != null;
                return;
            }
            catch (Throwable ignored) { }
        }
        patchedByMorphe = false;
    }

    public static boolean isPatchedByMorphe()
    {
        return patchedByMorphe;
    }

    public static String getModuleName()
    {
        return isPatchedByMorphe() ? MORPHE_MODULE_NAME : MODULE_NAME;
    }

    /**
     * enable MicroG on root mode (non-root mode always use MicroG)
     */
    public static boolean isEnableMicroG()
    {
        if (isPatchedByMorphe())
            return true;
        return Config.getEnableMicroG();
    }
}
