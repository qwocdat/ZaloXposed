package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.MorpheConstants;
import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.S;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Custom App Icon feature with interactive pan & pinch-to-zoom Crop Dialog:
 * - Lets user pick any image from gallery, crop/align inside an app-icon frame, and save.
 * - On app launch/restart: applies custom icon to Splash Screen & Android Recents TaskDescription.
 * - Also provides 1-tap "Pin Icon to Home Screen" via Android ShortcutManager so users can place
 *   their custom cropped app icon directly onto the launcher home screen without root.
 */
public class CustomAppIconHook extends BaseHook
{
    private static final int REQUEST_CODE_PICK_APP_ICON = 6789;
    private static final String ICON_FILENAME = "custom_app_icon.png";
    private static final int OUTPUT_ICON_SIZE = 512;

    @Override
    public void installHooks() throws Throwable
    {
        Method onActivityResult = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
        doHook(onActivityResult, 10, chain ->
        {
            int requestCode = (int) chain.getArg(0);
            if (requestCode != REQUEST_CODE_PICK_APP_ICON)
                return chain.proceed();
            int resultCode = (int) chain.getArg(1);
            Intent data = (Intent) chain.getArg(2);
            Activity activity = (Activity) chain.getThisObject();
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                openCropDialog(activity, data.getData());
            return null;
        });

        Class<?> launcherCls = Class.forName("com.zing.zalo.ui.ZaloLauncherActivity", false, classLoader);
        Method onCreate = launcherCls.getDeclaredMethod("onCreate", Bundle.class);
        doHook(onCreate, 5, chain ->
        {
            Object result = chain.proceed();
            if (Config.getEnableCustomAppIcon() && chain.getThisObject() instanceof Activity activity)
                applyTaskDescriptionIcon(activity);
            return result;
        });
    }

    public static File getCustomIconFile()
    {
        String base = Utils.GetZaloXposedDir();
        if (base == null || base.isEmpty())
            return null;
        return new File(base, ICON_FILENAME);
    }

    public static Bitmap loadCustomIconBitmap()
    {
        if (!Config.getEnableCustomAppIcon())
            return null;
        try
        {
            File f = getCustomIconFile();
            if (f != null && f.exists() && f.isFile())
                return BitmapFactory.decodeFile(f.getAbsolutePath());
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static void applyTaskDescriptionIcon(Activity activity)
    {
        Bitmap bmp = loadCustomIconBitmap();
        if (bmp == null)
            return;
        try
        {
            ActivityManager.TaskDescription td = new ActivityManager.TaskDescription(
                MorpheConstants.getModuleName(), bmp);
            activity.setTaskDescription(td);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    public static void PickAndCropIcon(Context context)
    {
        try
        {
            Activity activity = findActivity(context);
            if (activity == null)
            {
                toast(context, S.tr("openFilePickerFailed"));
                return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_APP_ICON);
        }
        catch (Throwable t)
        {
            Logger.e(t);
            toast(context, S.tr("openFilePickerFailed"));
        }
    }

    public static void PinCustomShortcut(Context context)
    {
        Bitmap bmp = loadCustomIconBitmap();
        if (bmp == null)
        {
            toast(context, S.tr("customAppIconMissing"));
            return;
        }
        try
        {
            ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported())
            {
                toast(context, S.tr("customAppIconPinUnsupported"));
                return;
            }
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent == null)
                return;
            launchIntent.setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(context, "dx_custom_zalo_icon_" + System.currentTimeMillis())
                .setShortLabel("Zalo")
                .setLongLabel(MorpheConstants.getModuleName())
                .setIcon(Icon.createWithAdaptiveBitmap(bmp))
                .setIntent(launchIntent)
                .build();
            sm.requestPinShortcut(info, null);
        }
        catch (Throwable t)
        {
            Logger.e(t);
            toast(context, S.tr("customAppIconPinUnsupported"));
        }
    }

    public static void ResetCustomIcon(Context context)
    {
        try
        {
            File f = getCustomIconFile();
            if (f != null && f.exists())
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            Config.setEnableCustomAppIcon(false);
            toast(context, S.tr("customAppIconResetDone"));
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @SuppressLint("SetTextI18n")
    private static void openCropDialog(Activity activity, Uri uri)
    {
        Bitmap decoded = decodeSafeBitmap(activity, uri);
        if (decoded == null)
        {
            toast(activity, S.tr("bgImagesFailed"));
            return;
        }

        new Handler(Looper.getMainLooper()).post(() ->
        {
            try
            {
                float d = activity.getResources().getDisplayMetrics().density;
                Dialog dialog = new Dialog(activity);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                dialog.setCancelable(true);
                if (dialog.getWindow() != null)
                {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    dialog.getWindow().setDimAmount(0.55f);
                }

                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                int pad = Math.round(18 * d);
                root.setPadding(pad, Math.round(18 * d), pad, Math.round(18 * d));
                GradientDrawable cardBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0xF5FBFCFF, 0xEDF0F4FA }
                );
                cardBg.setCornerRadius(26 * d);
                cardBg.setStroke(Math.max(1, Math.round(1.2f * d)), 0xFFFFFFFF);
                root.setBackground(cardBg);

                TextView title = new TextView(activity);
                title.setText(S.tr("customAppIconCropTitle"));
                title.setTextSize(17.5f);
                title.setTextColor(0xFF0F172A);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                title.setGravity(Gravity.CENTER);
                root.addView(title);

                TextView hint = new TextView(activity);
                hint.setText(S.tr("customAppIconCropHint"));
                hint.setTextSize(12.5f);
                hint.setTextColor(0xFF64748B);
                hint.setGravity(Gravity.CENTER);
                hint.setPadding(0, Math.round(4 * d), 0, Math.round(12 * d));
                root.addView(hint);

                IconCropView cropView = new IconCropView(activity, decoded);
                LinearLayout.LayoutParams cropLp = new LinearLayout.LayoutParams(
                    Math.round(268 * d), Math.round(268 * d));
                cropLp.gravity = Gravity.CENTER_HORIZONTAL;
                cropLp.bottomMargin = Math.round(14 * d);
                root.addView(cropView, cropLp);

                LinearLayout btnRow = new LinearLayout(activity);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.CENTER);

                TextView cancelBtn = makePillButton(activity, d, S.tr("cancel"), 0xFF64748B);
                cancelBtn.setOnClickListener(v -> dialog.dismiss());
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp1.rightMargin = Math.round(8 * d);
                btnRow.addView(cancelBtn, lp1);

                TextView saveBtn = makePillButton(activity, d, S.tr("customAppIconSaveBtn"), 0xFF007AFF);
                saveBtn.setOnClickListener(v ->
                {
                    Bitmap cropped = cropView.renderCroppedBitmap(OUTPUT_ICON_SIZE);
                    if (cropped != null && saveBitmap(cropped))
                    {
                        Config.setEnableCustomAppIcon(true);
                        applyTaskDescriptionIcon(activity);
                        toast(activity, S.tr("customAppIconSaved"));
                        dialog.dismiss();
                    }
                    else
                    {
                        toast(activity, S.tr("bgImagesFailed"));
                    }
                });
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                btnRow.addView(saveBtn, lp2);

                root.addView(btnRow);
                dialog.setContentView(root);
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
        });
    }

    private static TextView makePillButton(Context ctx, float d, String text, int color)
    {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(14f);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(Math.round(12 * d), Math.round(11 * d), Math.round(12 * d), Math.round(11 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999 * d);
        bg.setColor(color);
        bg.setStroke(Math.max(1, Math.round(1f * d)), 0xAAFFFFFF);
        btn.setBackground(bg);
        return btn;
    }

    private static Bitmap decodeSafeBitmap(Context ctx, Uri uri)
    {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri))
        {
            if (is == null)
                return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw = BitmapFactory.decodeStream(is, null, opts);
            if (raw == null)
                return null;
            int maxDim = Math.max(raw.getWidth(), raw.getHeight());
            if (maxDim > 1440)
            {
                float s = 1440f / maxDim;
                Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.round(raw.getWidth() * s), Math.round(raw.getHeight() * s), true);
                if (scaled != raw)
                    raw.recycle();
                return scaled;
            }
            return raw;
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return null;
        }
    }

    private static boolean saveBitmap(Bitmap bmp)
    {
        try
        {
            File f = getCustomIconFile();
            if (f == null)
                return false;
            File parent = f.getParentFile();
            if (parent != null && !parent.exists())
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f))
            {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            return true;
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return false;
        }
    }

    private static Activity findActivity(Context context)
    {
        Context c = context;
        while (c instanceof ContextWrapper wrapper)
        {
            if (c instanceof Activity a)
                return a;
            c = wrapper.getBaseContext();
        }
        return null;
    }

    private static void toast(Context ctx, String msg)
    {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    /**
     * Interactive touch pan + pinch-to-zoom crop widget with a rounded-square icon mask preview.
     */
    @SuppressLint("ViewConstructor")
    private static final class IconCropView extends View
    {
        private final Bitmap source;
        private final Matrix matrix = new Matrix();
        private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cropRect = new RectF();
        private final Path holePath = new Path();
        private final ScaleGestureDetector scaleDetector;

        private float currentScale = 1f;
        private float minScale = 0.5f;
        private float lastX = 0f;
        private float lastY = 0f;
        private boolean initialized = false;

        IconCropView(Context ctx, Bitmap source)
        {
            super(ctx);
            this.source = source;
            scrimPaint.setColor(0x99000000);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(ctx.getResources().getDisplayMetrics().density * 2f);
            framePaint.setColor(Color.WHITE);

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener()
            {
                @Override
                public boolean onScale(ScaleGestureDetector detector)
                {
                    float factor = detector.getScaleFactor();
                    float nextScale = currentScale * factor;
                    if (nextScale < minScale)
                        factor = minScale / currentScale;
                    else if (nextScale > minScale * 6f)
                        factor = (minScale * 6f) / currentScale;
                    currentScale *= factor;
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh)
        {
            super.onSizeChanged(w, h, oldw, oldh);
            float pad = Math.min(w, h) * 0.10f;
            cropRect.set(pad, pad, w - pad, h - pad);
            holePath.reset();
            float r = cropRect.width() * 0.22f;
            holePath.addRoundRect(cropRect, r, r, Path.Direction.CW);

            if (!initialized && w > 0 && h > 0)
            {
                initialized = true;
                float scaleX = cropRect.width() / source.getWidth();
                float scaleY = cropRect.height() / source.getHeight();
                minScale = Math.max(scaleX, scaleY) * 0.5f;
                currentScale = Math.max(scaleX, scaleY);
                float dx = cropRect.centerX() - (source.getWidth() * currentScale) * 0.5f;
                float dy = cropRect.centerY() - (source.getHeight() * currentScale) * 0.5f;
                matrix.reset();
                matrix.postScale(currentScale, currentScale);
                matrix.postTranslate(dx, dy);
            }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            super.onDraw(canvas);
            canvas.drawColor(0xFF111827);
            canvas.drawBitmap(source, matrix, bmpPaint);

            // Darken area outside rounded-square icon crop window
            canvas.save();
            canvas.clipOutPath(holePath);
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
            canvas.restore();

            canvas.drawPath(holePath, framePaint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event)
        {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1)
                    {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        invalidate();
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
            }
            return true;
        }

        Bitmap renderCroppedBitmap(int size)
        {
            if (cropRect.isEmpty())
                return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            float scaleToOut = size / cropRect.width();
            Matrix m = new Matrix(matrix);
            m.postTranslate(-cropRect.left, -cropRect.top);
            m.postScale(scaleToOut, scaleToOut);
            c.drawBitmap(source, m, bmpPaint);
            return out;
        }
    }
}
