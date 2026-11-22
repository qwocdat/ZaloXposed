package io.github.electroheavenvn.zaloxposed.hooks.permanent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Displays a welcome dialog on app launch inviting users to join the DigitalXiao Telegram group
 * and follow the TikTok channel.
 */
public class WelcomeDialogHook extends BaseHook
{
    private static final String TELEGRAM_URL = "https://t.me/DigitalXiao";
    private static final String TIKTOK_URL = "https://www.tiktok.com/@digitalxiaoiu5";
    private static volatile boolean shownThisSession = false;

    @Override
    public void installHooks() throws Throwable
    {
        Class<?> launcherCls = Class.forName("com.zing.zalo.ui.ZaloLauncherActivity", false, classLoader);
        Method onResume = null;
        try
        {
            onResume = launcherCls.getDeclaredMethod("onResume");
        }
        catch (NoSuchMethodException ignored)
        {
            onResume = Activity.class.getDeclaredMethod("onResume");
        }

        doHook(onResume, 5, chain ->
        {
            Object result = chain.proceed();
            if (shownThisSession)
                return result;
            Object self = chain.getThisObject();
            if (!(self instanceof Activity activity))
                return result;
            if (!launcherCls.isInstance(activity))
                return result;

            shownThisSession = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> showWelcomeDialog(activity), 1400L);
            return result;
        });
    }

    private static void showWelcomeDialog(Activity activity)
    {
        if (activity == null || activity.isFinishing() || activity.isDestroyed())
            return;
        try
        {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            builder.setTitle("ZaloxPosed X DigitalXiao");
            builder.setMessage(
                "Chào mừng bạn đến với ZaloxPosedXDigitalXiao !\n\n"
                + "Tham gia cộng đồng để nhận bản cập nhật mới nhất:\n"
                + "• Telegram: https://t.me/DigitalXiao\n"
                + "• TikTok: https://tiktok.com/@digitalxiaoiu5"
            );
            builder.setCancelable(true);
            builder.setPositiveButton("Tham gia Telegram", (dialog, which) -> openUrl(activity, TELEGRAM_URL));
            builder.setNeutralButton("Follow TikTok", (dialog, which) -> openUrl(activity, TIKTOK_URL));
            builder.setNegativeButton("Đóng", (dialog, which) -> dialog.dismiss());
            builder.show();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void openUrl(Activity activity, String url)
    {
        try
        {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }
}
