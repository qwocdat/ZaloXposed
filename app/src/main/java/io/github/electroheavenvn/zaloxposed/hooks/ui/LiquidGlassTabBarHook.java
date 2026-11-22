package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.DexKitCache;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Floating rounded Liquid Glass Bottom TabBar with interactive drag-to-slide crystal lens droplet,
 * spring squash-and-stretch physics, chromatic rim refraction (AGSL RuntimeShader on Android 13+),
 * and live tab hover switching.
 */
public class LiquidGlassTabBarHook extends BaseHook
{
    private static final WeakHashMap<ViewGroup, LiquidGlassController> CONTROLLERS = new WeakHashMap<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static WeakReference<FrameLayout> lastMainRoot = new WeakReference<>(null);
    private static boolean touchHookInstalled = false;

    @Override
    public void installHooks() throws Throwable
    {
        Config.addOnConfigChangedListener((key, oldValue, newValue) ->
        {
            if (Config.KEY_ENABLE_LIQUID_GLASS_TABBAR.equals(key))
            {
                MAIN_HANDLER.post(() ->
                {
                    boolean enabled = Config.getEnableLiquidGlassTabBar();
                    if (enabled)
                    {
                        FrameLayout root = lastMainRoot.get();
                        if (root != null)
                            scheduleAttach(root);
                    }
                    else
                    {
                        for (LiquidGlassController controller : new ArrayList<>(CONTROLLERS.values()))
                        {
                            if (controller != null)
                                controller.detach();
                        }
                        CONTROLLERS.clear();
                    }
                });
            }
        });

        installTouchInterceptor();

        Class<?> mainTabViewCls = Class.forName("com.zing.zalo.ui.maintab.MainTabView", false, classLoader);
        List<Method> methods = DexKitCache.GetMethods("LiquidGlassTabBar_MainTabView_OnCreateView", () -> bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass(mainTabViewCls)
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("android.view.View")
                .paramCount(3)
                .paramTypes("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle")
                .addUsingString("onCreateView", StringMatchType.Equals)
            )), classLoader);

        if (methods.isEmpty())
        {
            Logger.e("LiquidGlassTabBarHook: MainTabView.onCreateView not found");
            return;
        }

        for (Method method : methods)
        {
            doHook(method, chain ->
            {
                Object result = chain.proceed();
                if (result instanceof FrameLayout root)
                {
                    lastMainRoot = new WeakReference<>(root);
                    if (Config.getEnableLiquidGlassTabBar())
                        scheduleAttach(root);
                }
                return result;
            });
        }
    }

    private void installTouchInterceptor()
    {
        if (touchHookInstalled)
            return;
        touchHookInstalled = true;
        try
        {
            Method dispatchTouch = ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            doHook(dispatchTouch, 50, chain ->
            {
                if (CONTROLLERS.isEmpty() || !Config.getEnableLiquidGlassTabBar())
                    return chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof ViewGroup vg))
                    return chain.proceed();
                LiquidGlassController controller = CONTROLLERS.get(vg);
                if (controller == null)
                    return chain.proceed();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                if (event != null && controller.onDispatchTouchEvent(event))
                    return true;
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void scheduleAttach(FrameLayout root)
    {
        Runnable tryAttach = new Runnable()
        {
            int attempts = 0;

            @Override
            public void run()
            {
                if (!Config.getEnableLiquidGlassTabBar())
                    return;
                if (!root.isAttachedToWindow() && attempts < 15)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 120);
                    return;
                }
                ViewGroup tabBar = findBottomTabBar(root);
                if (tabBar != null)
                {
                    if (!CONTROLLERS.containsKey(tabBar))
                    {
                        LiquidGlassController controller = new LiquidGlassController(tabBar);
                        CONTROLLERS.put(tabBar, controller);
                        controller.attach();
                    }
                    return;
                }
                if (attempts < 20)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 150);
                }
            }
        };
        root.post(tryAttach);
    }

    private static ViewGroup findBottomTabBar(ViewGroup root)
    {
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0)
            return null;

        float density = root.getResources().getDisplayMetrics().density;
        int minH = Math.round(40f * density);
        int maxH = Math.round(104f * density);

        List<ViewGroup> candidates = new ArrayList<>();
        collectTabBarCandidates(root, root, rootW, rootH, minH, maxH, candidates);
        if (candidates.isEmpty())
            return null;

        ViewGroup best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ViewGroup candidate : candidates)
        {
            int score = scoreCandidate(candidate, root, rootW, rootH);
            if (score > bestScore)
            {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void collectTabBarCandidates(
        ViewGroup root, ViewGroup node, int rootW, int rootH, int minH, int maxH, List<ViewGroup> out)
    {
        if (node.getVisibility() != View.VISIBLE)
            return;

        if (node != root && isLikelyTabBar(node, root, rootW, rootH, minH, maxH))
        {
            out.add(node);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++)
        {
            View child = node.getChildAt(i);
            if (child instanceof ViewGroup childGroup)
                collectTabBarCandidates(root, childGroup, rootW, rootH, minH, maxH, out);
        }
    }

    private static boolean isLikelyTabBar(ViewGroup vg, ViewGroup root, int rootW, int rootH, int minH, int maxH)
    {
        int w = vg.getWidth();
        int h = vg.getHeight();
        if (w < rootW * 0.78f || h < minH || h > maxH)
            return false;

        int[] vgLoc = new int[2];
        int[] rootLoc = new int[2];
        vg.getLocationInWindow(vgLoc);
        root.getLocationInWindow(rootLoc);
        int relTop = vgLoc[1] - rootLoc[1];
        if (relTop < rootH * 0.75f)
            return false;

        List<View> visibleChildren = getVisibleTabChildren(vg);
        if (visibleChildren.size() < 2 || visibleChildren.size() > 7)
            return false;

        // Verify horizontal layout of tabs (children laid out side-by-side)
        int prevCenterX = Integer.MIN_VALUE;
        for (View child : visibleChildren)
        {
            if (child.getWidth() <= 0 || child.getHeight() <= 0)
                return false;
            int centerX = child.getLeft() + child.getWidth() / 2;
            if (centerX <= prevCenterX)
                return false;
            prevCenterX = centerX;
        }
        return true;
    }

    private static int scoreCandidate(ViewGroup vg, ViewGroup root, int rootW, int rootH)
    {
        int score = 0;
        String clsName = vg.getClass().getName().toLowerCase();
        if (clsName.contains("tab") || clsName.contains("bottom"))
            score += 40;
        if (vg.getId() != View.NO_ID)
        {
            try
            {
                String resName = vg.getResources().getResourceEntryName(vg.getId()).toLowerCase();
                if (resName.contains("tab") || resName.contains("bottom") || resName.contains("nav"))
                    score += 50;
            }
            catch (Throwable ignored) { }
        }
        List<View> tabs = getVisibleTabChildren(vg);
        if (tabs.size() >= 3 && tabs.size() <= 6)
            score += 30;
        for (View child : tabs)
        {
            if (child.isClickable())
                score += 6;
            if (child instanceof ViewGroup)
                score += 4;
        }
        int[] vgLoc = new int[2];
        int[] rootLoc = new int[2];
        vg.getLocationInWindow(vgLoc);
        root.getLocationInWindow(rootLoc);
        int relBottom = (vgLoc[1] - rootLoc[1]) + vg.getHeight();
        if (relBottom >= rootH - 180)
            score += 20;
        return score;
    }

    private static List<View> getVisibleTabChildren(ViewGroup vg)
    {
        List<View> tabs = new ArrayList<>();
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++)
        {
            View c = vg.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getWidth() > 0)
                tabs.add(c);
        }
        return tabs;
    }

    /**
     * Manages the floating pill transform, custom Liquid Glass capsule + interactive lens droplet,
     * spring physics, touch dragging, and automatic tab selection synchronization.
     */
    private static final class LiquidGlassController
    {
        private final ViewGroup tabBar;
        private final float density;
        private final int touchSlop;

        private Drawable originalBackground;
        private int originalLeftMargin, originalTopMargin, originalRightMargin, originalBottomMargin;
        private int originalPadLeft, originalPadTop, originalPadRight, originalPadBottom;
        private float originalElevation;
        private ViewOutlineProvider originalOutlineProvider;
        private boolean originalClipToOutline;
        private boolean originalClipChildren;
        private boolean originalClipToPadding;
        private View hiddenDividerView;

        private LiquidGlassDrawable glassDrawable;
        private DropletOverlayView dropletOverlay;
        private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

        private boolean attached = false;
        private boolean isDarkTheme = false;

        // Tab & physics state
        private int activeIndex = 0;
        private int hoveredIndex = 0;
        private float currentX = -1f;
        private float targetX = -1f;
        private float velocityX = 0f;
        private float currentW = 0f;
        private float targetW = 0f;

        // Droplet liquid morph state (0 = resting frosted pill, 1 = inflated crystal glass lens)
        private float inflateProgress = 0f;
        private float targetInflate = 0f;
        private float stretchX = 0f;
        private long lastFrameMs = 0L;
        private boolean animating = false;

        // Touch drag state
        private boolean pointerDown = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float lastTouchX = 0f;
        private long lastTouchTime = 0L;

        LiquidGlassController(ViewGroup tabBar)
        {
            this.tabBar = tabBar;
            this.density = tabBar.getResources().getDisplayMetrics().density;
            this.touchSlop = ViewConfiguration.get(tabBar.getContext()).getScaledTouchSlop();
        }

        void attach()
        {
            if (attached)
                return;
            attached = true;

            detectTheme();

            originalBackground = tabBar.getBackground();
            originalPadLeft = tabBar.getPaddingLeft();
            originalPadTop = tabBar.getPaddingTop();
            originalPadRight = tabBar.getPaddingRight();
            originalPadBottom = tabBar.getPaddingBottom();
            originalElevation = tabBar.getElevation();
            originalOutlineProvider = tabBar.getOutlineProvider();
            originalClipToOutline = tabBar.getClipToOutline();
            originalClipChildren = tabBar.getClipChildren();
            originalClipToPadding = tabBar.getClipToPadding();

            ViewGroup.LayoutParams lp = tabBar.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams mlp)
            {
                originalLeftMargin = mlp.leftMargin;
                originalTopMargin = mlp.topMargin;
                originalRightMargin = mlp.rightMargin;
                originalBottomMargin = mlp.bottomMargin;

                int sideMargin = dp(14f);
                int bottomMargin = Math.max(originalBottomMargin, dp(10f));
                mlp.leftMargin = sideMargin;
                mlp.rightMargin = sideMargin;
                mlp.bottomMargin = bottomMargin;
                tabBar.setLayoutParams(mlp);
            }

            // Allow droplet overflow & soft shadow around the floating capsule
            tabBar.setClipChildren(false);
            tabBar.setClipToPadding(false);
            ViewParent p = tabBar.getParent();
            int depth = 0;
            while (p instanceof ViewGroup parentVg && depth < 3)
            {
                parentVg.setClipChildren(false);
                parentVg.setClipToPadding(false);
                if (depth == 0)
                {
                    parentVg.setBackgroundColor(Color.TRANSPARENT);
                    hideAdjacentDivider(parentVg);
                }
                p = parentVg.getParent();
                depth++;
            }

            int padH = dp(4f);
            int padV = dp(3f);
            tabBar.setPadding(padH, padV, padH, padV);
            tabBar.setElevation(dp(10f));

            glassDrawable = new LiquidGlassDrawable(this);
            tabBar.setBackground(glassDrawable);
            tabBar.setOutlineProvider(new ViewOutlineProvider()
            {
                @Override
                public void getOutline(View view, Outline outline)
                {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0)
                        outline.setRoundRect(0, 0, w, h, h / 2f);
                }
            });
            // Keep clipToOutline false so inflated liquid droplet can bulge cleanly over the rim
            tabBar.setClipToOutline(false);

            // Attach luminous glass refraction overlay on top (non-interactive, purely visual meniscus/rim)
            installOverlay();

            // Sync initial active tab
            syncActiveTab(true);

            layoutListener = () ->
            {
                if (!attached)
                    return;
                detectTheme();
                syncActiveTab(false);
                syncOverlayBounds();
                if (glassDrawable != null)
                    glassDrawable.invalidateSelf();
            };
            tabBar.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        }

        void detach()
        {
            if (!attached)
                return;
            attached = false;
            animating = false;

            if (layoutListener != null && tabBar.getViewTreeObserver().isAlive())
                tabBar.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);

            if (dropletOverlay != null && dropletOverlay.getParent() instanceof ViewGroup parent)
                parent.removeView(dropletOverlay);
            dropletOverlay = null;

            tabBar.setBackground(originalBackground);
            tabBar.setPadding(originalPadLeft, originalPadTop, originalPadRight, originalPadBottom);
            tabBar.setElevation(originalElevation);
            tabBar.setOutlineProvider(originalOutlineProvider);
            tabBar.setClipToOutline(originalClipToOutline);
            tabBar.setClipChildren(originalClipChildren);
            tabBar.setClipToPadding(originalClipToPadding);

            ViewGroup.LayoutParams lp = tabBar.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams mlp)
            {
                mlp.leftMargin = originalLeftMargin;
                mlp.topMargin = originalTopMargin;
                mlp.rightMargin = originalRightMargin;
                mlp.bottomMargin = originalBottomMargin;
                tabBar.setLayoutParams(mlp);
            }

            if (hiddenDividerView != null)
            {
                hiddenDividerView.setVisibility(View.VISIBLE);
                hiddenDividerView = null;
            }

            for (View child : getVisibleTabChildren(tabBar))
            {
                child.setScaleX(1f);
                child.setScaleY(1f);
            }
        }

        private void installOverlay()
        {
            ViewParent parent = tabBar.getParent();
            if (!(parent instanceof ViewGroup parentVg))
                return;
            dropletOverlay = new DropletOverlayView(tabBar.getContext(), this);
            dropletOverlay.setClickable(false);
            dropletOverlay.setFocusable(false);
            dropletOverlay.setElevation(dp(12f));
            if (parentVg instanceof FrameLayout)
            {
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    Math.max(tabBar.getWidth(), 1), Math.max(tabBar.getHeight(), 1));
                parentVg.addView(dropletOverlay, flp);
            }
            else
            {
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    Math.max(tabBar.getWidth(), 1), Math.max(tabBar.getHeight(), 1));
                parentVg.addView(dropletOverlay, lp);
            }
            syncOverlayBounds();
        }

        private void syncOverlayBounds()
        {
            if (dropletOverlay == null || !attached)
                return;
            int w = tabBar.getWidth();
            int h = tabBar.getHeight();
            if (w <= 0 || h <= 0)
                return;
            int padOverflow = dp(14f);
            int targetW = w + padOverflow * 2;
            int targetH = h + padOverflow * 2;
            ViewGroup.LayoutParams lp = dropletOverlay.getLayoutParams();
            if (lp != null && (lp.width != targetW || lp.height != targetH))
            {
                lp.width = targetW;
                lp.height = targetH;
                dropletOverlay.setLayoutParams(lp);
            }
            dropletOverlay.setX(tabBar.getX() - padOverflow);
            dropletOverlay.setY(tabBar.getY() - padOverflow);
            dropletOverlay.setOverflowPadding(padOverflow);
        }

        private void hideAdjacentDivider(ViewGroup parent)
        {
            int idx = parent.indexOfChild(tabBar);
            if (idx > 0)
            {
                View prev = parent.getChildAt(idx - 1);
                if (prev != null && prev.getHeight() > 0 && prev.getHeight() <= dp(2f))
                {
                    hiddenDividerView = prev;
                    prev.setVisibility(View.GONE);
                }
            }
        }

        private void detectTheme()
        {
            int nightFlags = tabBar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean systemDark = (nightFlags == Configuration.UI_MODE_NIGHT_YES);
            if (originalBackground instanceof ColorDrawable cd)
            {
                int c = cd.getColor();
                if (Color.alpha(c) > 80)
                {
                    double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
                    isDarkTheme = lum < 0.45;
                    return;
                }
            }
            isDarkTheme = systemDark;
        }

        boolean onDispatchTouchEvent(MotionEvent event)
        {
            if (!attached)
                return false;
            List<View> tabs = getVisibleTabChildren(tabBar);
            if (tabs.isEmpty())
                return false;

            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            switch (action)
            {
                case MotionEvent.ACTION_DOWN:
                    pointerDown = true;
                    isDragging = false;
                    downX = x;
                    downY = y;
                    lastTouchX = x;
                    lastTouchTime = SystemClock.uptimeMillis();

                    int hitIdx = findTabIndexAtX(tabs, x);
                    if (hitIdx >= 0)
                    {
                        hoveredIndex = hitIdx;
                        targetInflate = 0.55f;
                        startAnimationLoop();
                    }
                    return false; // Let child also receive DOWN for normal tap

                case MotionEvent.ACTION_MOVE:
                    if (!pointerDown)
                        return false;
                    float dxFromDown = Math.abs(x - downX);
                    if (!isDragging && dxFromDown > touchSlop * 0.65f)
                    {
                        isDragging = true;
                        // Cancel pressed state on children so click won't misfire
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        for (View child : tabs)
                            child.dispatchTouchEvent(cancel);
                        cancel.recycle();

                        if (tabBar.getParent() != null)
                            tabBar.getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    if (isDragging)
                    {
                        long now = SystemClock.uptimeMillis();
                        float dt = Math.max(1f, now - lastTouchTime);
                        float instVel = (x - lastTouchX) / dt * 16f;
                        lastTouchX = x;
                        lastTouchTime = now;

                        float halfPill = Math.max(currentW * 0.5f, tabBar.getWidth() / (tabs.size() * 2f));
                        float minCenter = tabBar.getPaddingLeft() + halfPill;
                        float maxCenter = tabBar.getWidth() - tabBar.getPaddingRight() - halfPill;
                        float clampedX = clamp(x, minCenter, maxCenter);

                        targetX = clampedX;
                        targetInflate = 1.0f;
                        stretchX = clamp(instVel * 0.018f, -0.22f, 0.22f);

                        int newHover = findTabIndexAtX(tabs, clampedX);
                        if (newHover >= 0 && newHover != hoveredIndex)
                        {
                            hoveredIndex = newHover;
                            targetW = tabs.get(newHover).getWidth();
                            tabBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            updateTabHoverScales(tabs, hoveredIndex);
                        }
                        startAnimationLoop();
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasDragging = isDragging;
                    pointerDown = false;
                    isDragging = false;
                    targetInflate = 0f;
                    resetTabHoverScales(tabs);

                    if (wasDragging)
                    {
                        int finalIdx = clampInt(hoveredIndex, 0, tabs.size() - 1);
                        activeIndex = finalIdx;
                        snapTargetToTab(tabs, finalIdx, false);
                        startAnimationLoop();
                        if (action == MotionEvent.ACTION_UP)
                        {
                            View targetTab = tabs.get(finalIdx);
                            targetTab.performClick();
                        }
                        return true;
                    }
                    else
                    {
                        // Normal tap: fly droplet to tapped tab immediately
                        int tapIdx = findTabIndexAtX(tabs, x);
                        if (tapIdx >= 0 && tapIdx < tabs.size())
                        {
                            activeIndex = tapIdx;
                            hoveredIndex = tapIdx;
                            snapTargetToTab(tabs, tapIdx, false);
                            // Brief liquid pulse on tap
                            inflateProgress = Math.max(inflateProgress, 0.45f);
                            startAnimationLoop();
                        }
                        MAIN_HANDLER.postDelayed(() -> syncActiveTab(false), 60);
                        return false;
                    }
            }
            return false;
        }

        private void updateTabHoverScales(List<View> tabs, int hoverIdx)
        {
            for (int i = 0; i < tabs.size(); i++)
            {
                View child = tabs.get(i);
                float scale = (i == hoverIdx) ? 1.07f : 0.96f;
                child.animate().scaleX(scale).scaleY(scale).setDuration(110).start();
            }
        }

        private void resetTabHoverScales(List<View> tabs)
        {
            for (View child : tabs)
                child.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        }

        private int findTabIndexAtX(List<View> tabs, float x)
        {
            for (int i = 0; i < tabs.size(); i++)
            {
                View v = tabs.get(i);
                if (x >= v.getLeft() && x <= v.getRight())
                    return i;
            }
            if (x < tabs.get(0).getLeft())
                return 0;
            return tabs.size() - 1;
        }

        private void syncActiveTab(boolean immediate)
        {
            if (isDragging)
                return;
            List<View> tabs = getVisibleTabChildren(tabBar);
            if (tabs.isEmpty())
                return;

            int detected = detectSelectedTabIndex(tabs);
            if (detected >= 0 && detected < tabs.size())
                activeIndex = detected;
            else
                activeIndex = clampInt(activeIndex, 0, tabs.size() - 1);

            hoveredIndex = activeIndex;
            snapTargetToTab(tabs, activeIndex, immediate || currentX < 0f);
            if (!immediate)
                startAnimationLoop();
            else
                invalidateVisuals();
        }

        private void snapTargetToTab(List<View> tabs, int idx, boolean immediate)
        {
            if (idx < 0 || idx >= tabs.size())
                return;
            View tab = tabs.get(idx);
            float centerX = tab.getLeft() + tab.getWidth() * 0.5f;
            float pillW = tab.getWidth();
            targetX = centerX;
            targetW = pillW;
            if (immediate || currentX < 0f)
            {
                currentX = centerX;
                currentW = pillW;
                velocityX = 0f;
            }
        }

        private int detectSelectedTabIndex(List<View> tabs)
        {
            for (int i = 0; i < tabs.size(); i++)
            {
                if (isSubtreeSelected(tabs.get(i)))
                    return i;
            }
            // Fallback: check which tab has a visible TextView label (Zalo often shows label only on active tab)
            int visibleLabelIdx = -1;
            int visibleLabelCount = 0;
            for (int i = 0; i < tabs.size(); i++)
            {
                if (hasVisibleText(tabs.get(i)))
                {
                    visibleLabelIdx = i;
                    visibleLabelCount++;
                }
            }
            if (visibleLabelCount == 1)
                return visibleLabelIdx;
            return -1;
        }

        private boolean isSubtreeSelected(View v)
        {
            if (v.isSelected() || v.isActivated())
                return true;
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    if (isSubtreeSelected(vg.getChildAt(i)))
                        return true;
                }
            }
            return false;
        }

        private boolean hasVisibleText(View v)
        {
            if (v.getVisibility() != View.VISIBLE)
                return false;
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0 && tv.getAlpha() > 0.2f)
            {
                // Ignore small numeric unread badge (e.g. "5", "99+")
                String s = tv.getText().toString().trim();
                if (!s.matches("^[0-9+]+$") && !s.equals("N"))
                    return true;
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    if (hasVisibleText(vg.getChildAt(i)))
                        return true;
                }
            }
            return false;
        }

        private void startAnimationLoop()
        {
            if (animating || !attached)
                return;
            animating = true;
            lastFrameMs = SystemClock.uptimeMillis();
            tabBar.postOnAnimation(frameRunnable);
        }

        private final Runnable frameRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                if (!attached)
                {
                    animating = false;
                    return;
                }
                long now = SystemClock.uptimeMillis();
                float dt = clamp((now - lastFrameMs) / 1000f, 0.004f, 0.032f);
                lastFrameMs = now;

                // Damped spring physics for horizontal liquid glide
                float stiffness = isDragging ? 420f : 260f;
                float damping = isDragging ? 28f : 21f;
                float force = (targetX - currentX) * stiffness;
                velocityX += (force - velocityX * damping) * dt;
                currentX += velocityX * dt;

                // Smooth width & inflate interpolation
                currentW += (targetW - currentW) * Math.min(1f, dt * 16f);
                inflateProgress += (targetInflate - inflateProgress) * Math.min(1f, dt * 18f);

                // Velocity-driven squash & stretch
                float velStretchTarget = clamp(velocityX * 0.00018f, -0.22f, 0.22f);
                stretchX += (velStretchTarget - stretchX) * Math.min(1f, dt * 18f);

                invalidateVisuals();

                boolean settled = !isDragging
                    && !pointerDown
                    && Math.abs(targetX - currentX) < 0.35f
                    && Math.abs(velocityX) < 1.5f
                    && Math.abs(targetW - currentW) < 0.35f
                    && Math.abs(targetInflate - inflateProgress) < 0.008f
                    && Math.abs(stretchX) < 0.005f;

                if (settled)
                {
                    currentX = targetX;
                    currentW = targetW;
                    velocityX = 0f;
                    inflateProgress = targetInflate;
                    stretchX = 0f;
                    animating = false;
                    invalidateVisuals();
                }
                else
                {
                    tabBar.postOnAnimation(this);
                }
            }
        };

        private void invalidateVisuals()
        {
            if (glassDrawable != null)
                glassDrawable.invalidateSelf();
            if (dropletOverlay != null)
                dropletOverlay.invalidate();
        }

        void computeDropletRect(RectF outRect, float offsetX, float offsetY)
        {
            int barW = tabBar.getWidth();
            int barH = tabBar.getHeight();
            if (barW <= 0 || barH <= 0 || currentW <= 0f)
            {
                outRect.setEmpty();
                return;
            }
            float baseW = Math.max(currentW - dp(6f), dp(48f));
            float baseH = Math.max(barH - dp(8f), dp(36f));

            // Liquid droplet scales up & stretches dynamically during drag/slide
            float absStretch = Math.abs(stretchX);
            float scaleX = 1f + inflateProgress * 0.14f + absStretch * 0.65f;
            float scaleY = 1f + inflateProgress * 0.22f - absStretch * 0.28f;

            float w = baseW * scaleX;
            float h = baseH * scaleY;
            float cx = currentX + offsetX;
            float cy = barH * 0.5f + offsetY;

            outRect.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        }

        int dp(float v)
        {
            return Math.round(v * density);
        }

        float dpf(float v)
        {
            return v * density;
        }
    }

    /**
     * Draws the outer frosted Liquid Glass floating pill capsule + resting pill behind the tabs.
     */
    private static final class LiquidGlassDrawable extends Drawable
    {
        private static final String CAPSULE_AGSL =
            "uniform float2 uSize;" +
            "uniform float uRadius;" +
            "uniform float uDark;" +
            "float sdRoundBox(float2 p, float2 b, float r) {" +
            "  float2 q = abs(p) - b + r;" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;" +
            "}" +
            "half4 main(float2 fragCoord) {" +
            "  float2 halfSize = uSize * 0.5;" +
            "  float2 p = fragCoord - halfSize;" +
            "  float d = sdRoundBox(p, halfSize, uRadius);" +
            "  float alphaMask = 1.0 - smoothstep(-1.0, 1.0, d);" +
            "  if (alphaMask <= 0.001) return half4(0.0);" +
            "  float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);" +
            "  float edgeBand = smoothstep(-5.0, 0.0, d);" +
            "  float topRim = pow(max(0.0, 1.0 - yNorm * 2.2), 2.0) * edgeBand;" +
            "  float botRim = pow(max(0.0, (yNorm - 0.55) * 2.2), 2.0) * edgeBand;" +
            "  half3 baseDark = mix(half3(0.10, 0.11, 0.14), half3(0.06, 0.07, 0.09), half(yNorm));" +
            "  half3 baseLight = mix(half3(0.97, 0.98, 1.00), half3(0.91, 0.94, 0.98), half(yNorm));" +
            "  half3 col = mix(baseLight, baseDark, half(uDark));" +
            "  float baseAlpha = mix(0.82, 0.74, uDark);" +
            "  col += half3(0.28, 0.30, 0.34) * half(topRim) * mix(half(0.9), half(0.85), half(uDark));" +
            "  col += half3(0.14, 0.16, 0.22) * half(botRim) * half(0.6);" +
            "  return half4(col * half(baseAlpha * alphaMask), half(baseAlpha * alphaMask));" +
            "}";

        private final LiquidGlassController ctrl;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF capsuleRect = new RectF();
        private final RectF dropletRect = new RectF();
        private Object runtimeShaderObj;

        LiquidGlassDrawable(LiquidGlassController ctrl)
        {
            this.ctrl = ctrl;
            borderPaint.setStyle(Paint.Style.STROKE);
            pillStrokePaint.setStyle(Paint.Style.STROKE);
            if (Build.VERSION.SDK_INT >= 33)
            {
                try
                {
                    runtimeShaderObj = new RuntimeShader(CAPSULE_AGSL);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }

        @Override
        public void draw(Canvas canvas)
        {
            Rect b = getBounds();
            if (b.isEmpty())
                return;
            capsuleRect.set(b);
            float radius = capsuleRect.height() * 0.5f;
            boolean dark = ctrl.isDarkTheme;

            // 1. Frosted Liquid Glass capsule body
            if (Build.VERSION.SDK_INT >= 33 && runtimeShaderObj instanceof RuntimeShader shader)
            {
                shader.setFloatUniform("uSize", capsuleRect.width(), capsuleRect.height());
                shader.setFloatUniform("uRadius", radius);
                shader.setFloatUniform("uDark", dark ? 1f : 0f);
                fillPaint.setShader(shader);
                canvas.drawRoundRect(capsuleRect, radius, radius, fillPaint);
                fillPaint.setShader(null);
            }
            else
            {
                int topColor = dark ? 0xC81C1E24 : 0xDCFFFFFF;
                int botColor = dark ? 0xBD101216 : 0xD0EDF3FC;
                fillPaint.setShader(new LinearGradient(
                    0, capsuleRect.top, 0, capsuleRect.bottom, topColor, botColor, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(capsuleRect, radius, radius, fillPaint);
                fillPaint.setShader(null);
            }

            // 2. Crisp specular glass rim around the outer pill
            float strokeW = ctrl.dpf(1.1f);
            borderPaint.setStrokeWidth(strokeW);
            int rimTop = dark ? 0x48FFFFFF : 0xF0FFFFFF;
            int rimMid = dark ? 0x18FFFFFF : 0x60C8D8F0;
            int rimBot = dark ? 0x28FFFFFF : 0xA0FFFFFF;
            borderPaint.setShader(new LinearGradient(
                0, capsuleRect.top, 0, capsuleRect.bottom,
                new int[] { rimTop, rimMid, rimBot },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
            RectF inset = new RectF(
                capsuleRect.left + strokeW * 0.5f,
                capsuleRect.top + strokeW * 0.5f,
                capsuleRect.right - strokeW * 0.5f,
                capsuleRect.bottom - strokeW * 0.5f);
            canvas.drawRoundRect(inset, radius, radius, borderPaint);

            // 3. Base translucent active tab pill (behind the icons/text)
            ctrl.computeDropletRect(dropletRect, 0f, 0f);
            if (!dropletRect.isEmpty())
            {
                float pr = dropletRect.height() * 0.5f;
                float inf = ctrl.inflateProgress;
                int pTop = dark
                    ? Color.argb((int) (46 + inf * 34), 255, 255, 255)
                    : Color.argb((int) (40 + inf * 35), 0, 104, 255);
                int pBot = dark
                    ? Color.argb((int) (26 + inf * 22), 220, 232, 255)
                    : Color.argb((int) (24 + inf * 20), 0, 130, 255);

                pillFillPaint.setShader(new LinearGradient(
                    dropletRect.left, dropletRect.top, dropletRect.left, dropletRect.bottom,
                    pTop, pBot, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(dropletRect, pr, pr, pillFillPaint);
                pillFillPaint.setShader(null);

                pillStrokePaint.setStrokeWidth(ctrl.dpf(1.0f + inf * 0.4f));
                int sTop = dark ? 0x66FFFFFF : 0xCCFFFFFF;
                int sBot = dark ? 0x26FFFFFF : 0x550068FF;
                pillStrokePaint.setShader(new LinearGradient(
                    dropletRect.left, dropletRect.top, dropletRect.left, dropletRect.bottom,
                    sTop, sBot, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(dropletRect, pr, pr, pillStrokePaint);
            }
        }

        @Override
        public void setAlpha(int alpha)
        {
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter)
        {
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity()
        {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Foreground crystal-lens overlay drawn over the TabBar to render the luminous Liquid Glass
     * meniscus specular arcs and chromatic edge refraction during press/slide (matching the video).
     */
    @SuppressLint("ViewConstructor")
    private static final class DropletOverlayView extends View
    {
        private static final String LENS_AGSL =
            "uniform float4 uRect;" +
            "uniform float uRadius;" +
            "uniform float uInflate;" +
            "uniform float uDark;" +
            "float sdRoundBox(float2 p, float2 b, float r) {" +
            "  float2 q = abs(p) - b + r;" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;" +
            "}" +
            "half4 main(float2 fragCoord) {" +
            "  float2 center = (uRect.xy + uRect.zw) * 0.5;" +
            "  float2 halfSize = (uRect.zw - uRect.xy) * 0.5;" +
            "  float2 p = fragCoord - center;" +
            "  float d = sdRoundBox(p, halfSize, uRadius);" +
            "  float mask = 1.0 - smoothstep(-1.2, 1.0, d);" +
            "  if (mask <= 0.001) return half4(0.0);" +
            "  float2 uv = p / max(halfSize, float2(1.0));" +
            "  float rim = smoothstep(-7.0, -0.5, d) * mask;" +
            "  float crispRim = smoothstep(-2.4, -0.3, d) * mask;" +
            "  float topArc = pow(max(0.0, -uv.y), 2.4) * (1.0 - uv.x * uv.x * 0.55) * rim;" +
            "  float botArc = pow(max(0.0, uv.y), 2.6) * (1.0 - uv.x * uv.x * 0.55) * rim;" +
            "  float chromaLeft = smoothstep(0.35, 1.0, -uv.x) * crispRim * uInflate;" +
            "  float chromaRight = smoothstep(0.35, 1.0, uv.x) * crispRim * uInflate;" +
            "  float centerGlow = (1.0 - clamp(length(uv), 0.0, 1.0)) * 0.14 * uInflate;" +
            "  half3 col = half3(1.0, 1.0, 1.0) * half((topArc * 0.65 + botArc * 0.38 + centerGlow) * (0.28 + 0.72 * uInflate));" +
            "  col += half3(0.28, 0.80, 1.00) * half(chromaLeft * 0.52);" +
            "  col += half3(0.95, 0.55, 1.00) * half(chromaRight * 0.42);" +
            "  float a = clamp((topArc * 0.55 + botArc * 0.34 + crispRim * (0.25 + 0.55 * uInflate) + centerGlow) * mask, 0.0, 0.88);" +
            "  a *= mix(0.65, 1.0, uDark);" +
            "  return half4(col * half(a), half(a));" +
            "}";

        private final LiquidGlassController ctrl;
        private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int overflowPad = 0;
        private Object runtimeShaderObj;

        DropletOverlayView(Context context, LiquidGlassController ctrl)
        {
            super(context);
            this.ctrl = ctrl;
            arcPaint.setStyle(Paint.Style.STROKE);
            if (Build.VERSION.SDK_INT >= 33)
            {
                try
                {
                    runtimeShaderObj = new RuntimeShader(LENS_AGSL);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }

        void setOverflowPadding(int pad)
        {
            this.overflowPad = pad;
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            super.onDraw(canvas);
            ctrl.computeDropletRect(rect, overflowPad, overflowPad);
            if (rect.isEmpty())
                return;

            float radius = rect.height() * 0.5f;
            float inf = ctrl.inflateProgress;
            boolean dark = ctrl.isDarkTheme;

            if (Build.VERSION.SDK_INT >= 33 && runtimeShaderObj instanceof RuntimeShader shader)
            {
                shader.setFloatUniform("uRect", rect.left, rect.top, rect.right, rect.bottom);
                shader.setFloatUniform("uRadius", radius);
                shader.setFloatUniform("uInflate", inf);
                shader.setFloatUniform("uDark", dark ? 1f : 0f);
                shaderPaint.setShader(shader);
                canvas.drawRoundRect(rect, radius, radius, shaderPaint);
                shaderPaint.setShader(null);
            }
            else
            {
                // Fallback for Android 8–12: crisp luminous meniscus rim + radial lens sheen
                if (inf > 0.02f)
                {
                    glowPaint.setShader(new RadialGradient(
                        rect.centerX(), rect.centerY(), Math.max(rect.width() * 0.55f, 1f),
                        Color.argb((int) (42 * inf), 255, 255, 255),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(rect, radius, radius, glowPaint);
                    glowPaint.setShader(null);
                }

                arcPaint.setStrokeWidth(ctrl.dpf(1.2f + inf * 0.9f));
                int topHighlight = Color.argb((int) ((dark ? 110 : 150) + inf * 105), 255, 255, 255);
                int leftTint = Color.argb((int) (inf * 130), 110, 215, 255);
                int rightTint = Color.argb((int) (inf * 110), 235, 165, 255);
                arcPaint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[] { topHighlight, leftTint, rightTint, topHighlight },
                    new float[] { 0f, 0.35f, 0.72f, 1f },
                    Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, arcPaint);
            }
        }
    }

    private static float clamp(float v, float min, float max)
    {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max)
    {
        return Math.max(min, Math.min(max, v));
    }
}
