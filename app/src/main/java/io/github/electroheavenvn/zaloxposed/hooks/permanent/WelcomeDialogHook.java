package io.github.electroheavenvn.zaloxposed.hooks.permanent;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Mandatory iOS 27 Liquid Glass welcome gate:
 * 1) Requires user to tap Follow TikTok (@digitalxiaoiu5) and return to app.
 * 2) Then requires user to tap Join Telegram (https://t.me/DigitalXiao) and return to app.
 * 3) Once both steps are verified, opens a second iOS 27 Liquid Glass sheet with a Switch
 *    ("Không hiển thị lại nữa"). If switched ON, persists in Config so it won't appear next launch;
 *    if left OFF, it will show again on the next launch.
 */
public class WelcomeDialogHook extends BaseHook
{
    private static final String TELEGRAM_URL = "https://t.me/DigitalXiao";
    private static final String TIKTOK_URL = "https://www.tiktok.com/@digitalxiaoiu5";

    private static volatile boolean flowStartedThisSession = false;
    private static volatile boolean tiktokDone = false;
    private static volatile boolean telegramDone = false;
    private static volatile int pendingReturnStep = 0; // 1 = waiting return from TikTok, 2 = waiting return from Telegram

    private static WeakReference<Activity> hostActivityRef = new WeakReference<>(null);
    private static Dialog gateDialog = null;
    private static TextView tiktokBtnView = null;
    private static TextView telegramBtnView = null;
    private static TextView continueBtnView = null;
    private static TextView statusHintView = null;

    @Override
    public void installHooks() throws Throwable
    {
        Class<?> launcherCls = Class.forName("com.zing.zalo.ui.ZaloLauncherActivity", false, classLoader);
        Method onResume;
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
            Object self = chain.getThisObject();
            if (!(self instanceof Activity activity))
                return result;
            if (!launcherCls.isInstance(activity))
                return result;

            hostActivityRef = new WeakReference<>(activity);

            // Handle return from TikTok / Telegram
            if (pendingReturnStep == 1)
            {
                pendingReturnStep = 0;
                tiktokDone = true;
                new Handler(Looper.getMainLooper()).post(WelcomeDialogHook::refreshGateUi);
                return result;
            }
            if (pendingReturnStep == 2)
            {
                pendingReturnStep = 0;
                telegramDone = true;
                new Handler(Looper.getMainLooper()).post(WelcomeDialogHook::refreshGateUi);
                return result;
            }

            if (flowStartedThisSession || Config.getHideWelcomeDialog())
                return result;

            flowStartedThisSession = true;
            tiktokDone = false;
            telegramDone = false;
            new Handler(Looper.getMainLooper()).postDelayed(() -> showLiquidGlassGateDialog(activity), 1300L);
            return result;
        });
    }

    @SuppressLint("SetTextI18n")
    private static void showLiquidGlassGateDialog(Activity activity)
    {
        if (activity == null || activity.isFinishing() || activity.isDestroyed())
            return;
        try
        {
            if (gateDialog != null && gateDialog.isShowing())
                gateDialog.dismiss();

            float d = activity.getResources().getDisplayMetrics().density;
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            if (dialog.getWindow() != null)
            {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setDimAmount(0.45f);
            }

            LinearLayout card = buildLiquidGlassCard(activity, d);

            TextView badge = new TextView(activity);
            badge.setText("Hi Chào Cậu!");
            badge.setTextSize(10.5f);
            badge.setTextColor(0xFF007AFF);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setGravity(Gravity.CENTER);
            card.addView(badge);

            TextView title = new TextView(activity);
            title.setText("ZaloxPosed X DigitalXiao");
            title.setTextSize(20f);
            title.setTextColor(0xFF0F172A);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, Math.round(4 * d), 0, Math.round(4 * d));
            card.addView(title);

            TextView desc = new TextView(activity);
            desc.setText("Hoàn thành đủ 2 bước bên dưới để mở khoá sử dụng bản mod by DigitalXiao");
            desc.setTextSize(13.5f);
            desc.setTextColor(0xFF475569);
            desc.setGravity(Gravity.CENTER);
            desc.setPadding(0, 0, 0, Math.round(14 * d));
            card.addView(desc);

            // Step 1: TikTok Liquid Glass Button
            tiktokBtnView = buildLiquidGlassButton(activity, d, "1. Follow TikTok @digitalxiaoiu5", 0xFF111827, true);
            tiktokBtnView.setOnClickListener(v ->
            {
                pendingReturnStep = 1;
                openUrl(activity, TIKTOK_URL);
            });
            card.addView(tiktokBtnView, marginLp(d, 0, 0, 0, 10));

            // Step 2: Telegram Liquid Glass Button
            telegramBtnView = buildLiquidGlassButton(activity, d, "2. Tham gia Telegram DigitalXiao", 0xFF007AFF, false);
            telegramBtnView.setOnClickListener(v ->
            {
                if (!tiktokDone)
                {
                    Toast.makeText(activity, "Vui lòng hoàn thành Bước 1 (Follow TikTok) trước!", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingReturnStep = 2;
                openUrl(activity, TELEGRAM_URL);
            });
            card.addView(telegramBtnView, marginLp(d, 0, 0, 0, 12));

            statusHintView = new TextView(activity);
            statusHintView.setText("Bước 1/2: Nhấn Follow TikTok rồi quay lại ứng dụng");
            statusHintView.setTextSize(12f);
            statusHintView.setTextColor(0xFF64748B);
            statusHintView.setGravity(Gravity.CENTER);
            statusHintView.setPadding(0, 0, 0, Math.round(12 * d));
            card.addView(statusHintView);

            // Continue Liquid Glass Button (locked until both done)
            continueBtnView = buildLiquidGlassButton(activity, d, "Tiếp tục", 0xFF007AFF, false);
            continueBtnView.setOnClickListener(v ->
            {
                if (!tiktokDone || !telegramDone)
                {
                    Toast.makeText(activity, "Bạn cần Follow TikTok và Tham gia Telegram để tiếp tục!", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                showDontShowAgainDialog(activity);
            });
            card.addView(continueBtnView, marginLp(d, 0, 0, 0, 0));

            gateDialog = dialog;
            refreshGateUi();
            dialog.setContentView(card);
            if (dialog.getWindow() != null)
            {
                dialog.getWindow().setLayout(
                    Math.round(320 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
            }
            dialog.show();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @SuppressLint("SetTextI18n")
    private static void refreshGateUi()
    {
        Activity activity = hostActivityRef.get();
        if (activity == null || tiktokBtnView == null || telegramBtnView == null || continueBtnView == null)
            return;
        float d = activity.getResources().getDisplayMetrics().density;

        if (tiktokDone)
        {
            tiktokBtnView.setText("✓ Đã Follow TikTok @digitalxiaoiu5");
            styleLiquidGlassButton(tiktokBtnView, d, 0xFF16A34A, true);
        }
        else
        {
            tiktokBtnView.setText("1. Follow TikTok @digitalxiaoiu5");
            styleLiquidGlassButton(tiktokBtnView, d, 0xFF111827, true);
        }

        if (telegramDone)
        {
            telegramBtnView.setText("✓ Đã tham gia Telegram DigitalXiao");
            styleLiquidGlassButton(telegramBtnView, d, 0xFF16A34A, true);
        }
        else
        {
            telegramBtnView.setText("2. Tham gia Telegram DigitalXiao");
            styleLiquidGlassButton(telegramBtnView, d, 0xFF007AFF, tiktokDone);
        }

        boolean allDone = tiktokDone && telegramDone;
        styleLiquidGlassButton(continueBtnView, d, 0xFF007AFF, allDone);
        continueBtnView.setText(allDone ? "Tiếp tục →" : "Hoàn thành 2 bước để mở khoá");

        if (statusHintView != null)
        {
            if (!tiktokDone)
                statusHintView.setText("Bước 1/2: Nhấn Follow TikTok rồi quay lại ứng dụng");
            else if (!telegramDone)
                statusHintView.setText("Bước 2/2: Nhấn Tham gia nhóm Telegram rồi quay lại");
            else
                statusHintView.setText("Đã hoàn tất cả 2 bước! Nhấn Tiếp tục để vào Zalo.");
        }
    }

    @SuppressLint({"SetTextI18n", "UseSwitchCompatOrMaterialCode"})
    private static void showDontShowAgainDialog(Activity activity)
    {
        if (activity == null || activity.isFinishing() || activity.isDestroyed())
            return;
        try
        {
            float d = activity.getResources().getDisplayMetrics().density;
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            if (dialog.getWindow() != null)
            {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setDimAmount(0.42f);
            }

            LinearLayout card = buildLiquidGlassCard(activity, d);

            TextView title = new TextView(activity);
            title.setText("Xác nhận hoàn tất");
            title.setTextSize(19f);
            title.setTextColor(0xFF0F172A);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, Math.round(6 * d));
            card.addView(title);

            TextView desc = new TextView(activity);
            desc.setText("Cảm ơn bạn đã ủng hộ DigitalXiao/Quocdat!");
            desc.setTextSize(13.5f);
            desc.setTextColor(0xFF475569);
            desc.setGravity(Gravity.CENTER);
            desc.setPadding(0, 0, 0, Math.round(14 * d));
            card.addView(desc);

            // Switch row (strictly a Switch, NOT a CheckBox)
            LinearLayout switchRow = new LinearLayout(activity);
            switchRow.setOrientation(LinearLayout.HORIZONTAL);
            switchRow.setGravity(Gravity.CENTER_VERTICAL);
            int rowPadH = Math.round(14 * d);
            int rowPadV = Math.round(10 * d);
            switchRow.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setShape(GradientDrawable.RECTANGLE);
            rowBg.setCornerRadius(16 * d);
            rowBg.setColor(0xCCEEF2F8);
            rowBg.setStroke(Math.max(1, Math.round(1f * d)), 0x99FFFFFF);
            switchRow.setBackground(rowBg);

            TextView switchLabel = new TextView(activity);
            switchLabel.setText("Không muốn hiển thị lại nữa");
            switchLabel.setTextSize(14f);
            switchLabel.setTextColor(0xFF0F172A);
            switchLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            switchRow.addView(switchLabel, labelLp);

            Switch dontShowSwitch = new Switch(activity);
            dontShowSwitch.setChecked(false);
            switchRow.addView(dontShowSwitch);

            card.addView(switchRow, marginLp(d, 0, 0, 0, 16));

            TextView doneBtn = buildLiquidGlassButton(activity, d, "Vào Zalo", 0xFF007AFF, true);
            doneBtn.setOnClickListener(v ->
            {
                Config.setHideWelcomeDialog(dontShowSwitch.isChecked());
                dialog.dismiss();
            });
            card.addView(doneBtn, marginLp(d, 0, 0, 0, 0));

            dialog.setContentView(card);
            if (dialog.getWindow() != null)
            {
                dialog.getWindow().setLayout(
                    Math.round(316 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
            }
            dialog.show();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static LinearLayout buildLiquidGlassCard(Context ctx, float d)
    {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        card.setPadding(pad, Math.round(22 * d), pad, Math.round(20 * d));

        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { 0xF5FBFCFF, 0xEDF0F4FA }
        );
        bg.setCornerRadius(28 * d);
        bg.setStroke(Math.max(1, Math.round(1.2f * d)), 0xFFFFFFFF);
        card.setBackground(bg);
        card.setElevation(18 * d);
        return card;
    }

    private static TextView buildLiquidGlassButton(Context ctx, float d, String text, int accentColor, boolean active)
    {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(14.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(Math.round(16 * d), Math.round(13 * d), Math.round(16 * d), Math.round(13 * d));
        btn.setClickable(true);
        btn.setFocusable(true);
        styleLiquidGlassButton(btn, d, accentColor, active);
        return btn;
    }

    private static void styleLiquidGlassButton(TextView btn, float d, int accentColor, boolean active)
    {
        int r = Color.red(accentColor);
        int g = Color.green(accentColor);
        int b = Color.blue(accentColor);

        int topCol = active ? Color.argb(230, Math.min(255, r + 24), Math.min(255, g + 24), Math.min(255, b + 24))
                            : Color.argb(110, 148, 163, 184);
        int botCol = active ? Color.argb(245, r, g, b)
                            : Color.argb(90, 148, 163, 184);

        GradientDrawable pill = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { topCol, botCol }
        );
        pill.setCornerRadius(999 * d);
        pill.setStroke(Math.max(1, Math.round(1.2f * d)), active ? 0xCCFFFFFF : 0x66FFFFFF);
        btn.setBackground(pill);
        btn.setTextColor(active ? Color.WHITE : 0xCCFFFFFF);
        btn.setElevation(active ? 6 * d : 0f);
        btn.setAlpha(active ? 1f : 0.72f);
    }

    private static LinearLayout.LayoutParams marginLp(float d, int l, int t, int r, int b)
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(Math.round(l * d), Math.round(t * d), Math.round(r * d), Math.round(b * d));
        return lp;
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
