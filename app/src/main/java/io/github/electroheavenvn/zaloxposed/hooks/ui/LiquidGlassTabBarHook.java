package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;
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
 * Single-Pass GPU Liquid Glass Bottom TabBar (v10):
 * - Matches the exact optical refraction & meniscus geometry of the reference Liquid Clock screenshot:
 *   - Refracted top/bottom capsule borders stay straight horizontally across the middle and curve
 *     smoothly at both rounded ends to connect 1-to-1 with the outer capsule border.
 *   - Objects touching the glass rim are smoothly pulled/warped inward into the curved bevel with
 *     authentic 3-channel Cauchy RGB chromatic dispersion, while the center remains 100% flat & sharp.
 *   - Pure white glow strictly clipped inside the TabBar capsule background when touched/held.
 * - Supports 2 selectable themes in Settings -> Giao diện -> Thanh dưới:
 *   0 = Sáng (Light): bright frosted white glass + #111111 inactive + #007AFF active blue.
 *   1 = Tối (Dark): charcoal translucent glass (matching reference image 2) + #FFFFFF inactive + #0A84FF active blue.
 */
public class LiquidGlassTabBarHook extends BaseHook
{
    private static final String CUSTOM_TABBAR_TAG = "dx_liquid_glass_tabbar_host";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<FrameLayout, TabBarReplacement> ATTACHED = new WeakHashMap<>();
    private static WeakReference<FrameLayout> lastMainRoot = new WeakReference<>(null);

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
                        for (TabBarReplacement rep : new ArrayList<>(ATTACHED.values()))
                        {
                            if (rep != null)
                                rep.detach();
                        }
                        ATTACHED.clear();
                    }
                });
            }
            else if (Config.KEY_LIQUID_GLASS_THEME.equals(key))
            {
                MAIN_HANDLER.post(() ->
                {
                    for (TabBarReplacement rep : ATTACHED.values())
                    {
                        if (rep != null)
                            rep.refreshTheme();
                    }
                });
            }
        });

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

    private static void scheduleAttach(FrameLayout root)
    {
        Runnable task = new Runnable()
        {
            int attempts = 0;

            @Override
            public void run()
            {
                if (!Config.getEnableLiquidGlassTabBar())
                    return;
                if ((!root.isAttachedToWindow() || root.getWidth() <= 0 || root.getHeight() <= 0) && attempts < 20)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 120);
                    return;
                }
                if (ATTACHED.containsKey(root))
                    return;

                ViewGroup origTabBar = findBottomTabBar(root);
                if (origTabBar != null)
                {
                    TabBarReplacement rep = new TabBarReplacement(root, origTabBar);
                    if (rep.attach())
                    {
                        ATTACHED.put(root, rep);
                        return;
                    }
                }
                if (attempts < 25)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 150);
                }
            }
        };
        root.post(task);
    }

    private static ViewGroup findBottomTabBar(ViewGroup root)
    {
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0)
            return null;

        float density = root.getResources().getDisplayMetrics().density;
        int minH = Math.round(38f * density);
        int maxH = Math.round(112f * density);

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
        if (CUSTOM_TABBAR_TAG.equals(node.getTag()))
            return;

        if (node != root && isLikelyTabBar(node, root, rootW, rootH, minH, maxH))
            out.add(node);

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
        if (relTop < rootH * 0.74f)
            return false;

        List<View> visibleChildren = getVisibleChildren(vg);
        if (visibleChildren.size() < 2 || visibleChildren.size() > 7)
            return false;

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
        List<View> tabs = getVisibleChildren(vg);
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

    private static List<View> getVisibleChildren(ViewGroup vg)
    {
        List<View> list = new ArrayList<>();
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++)
        {
            View c = vg.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getWidth() > 0)
                list.add(c);
        }
        return list;
    }

    private enum TabKind
    {
        MESSAGES,
        CONTACTS,
        DISCOVERY,
        TIMELINE,
        PROFILE,
        GROUPS,
        MORE
    }

    private static final class TabItemInfo
    {
        final View originalView;
        final String label;
        final TabKind kind;
        String badgeText = "";

        TabItemInfo(View originalView, String label, TabKind kind)
        {
            this.originalView = originalView;
            this.label = label;
            this.kind = kind;
        }
    }

    private static final class TabBarReplacement
    {
        private final FrameLayout root;
        private final ViewGroup origTabBar;
        private final List<TabItemInfo> tabs = new ArrayList<>();

        private int origTabBarVisibility;
        private View hiddenDivider;
        private ViewGroup origWrapperBar;
        private int origWrapperVisibility = View.VISIBLE;
        private LiquidGlassTabBarView customBar;
        private boolean attached = false;
        private boolean isDarkTheme = false;
        private long lastUserSelectMs = 0L;

        private final Runnable syncPoller = new Runnable()
        {
            @Override
            public void run()
            {
                if (!attached || customBar == null)
                    return;
                syncStateFromOriginal();
                MAIN_HANDLER.postDelayed(this, 260);
            }
        };

        TabBarReplacement(FrameLayout root, ViewGroup origTabBar)
        {
            this.root = root;
            this.origTabBar = origTabBar;
        }

        boolean attach()
        {
            if (attached)
                return true;

            List<View> rawTabs = getVisibleChildren(origTabBar);
            if (rawTabs.size() < 2)
                return false;

            isDarkTheme = (Config.getLiquidGlassTheme() == 1);

            tabs.clear();
            for (int i = 0; i < rawTabs.size(); i++)
            {
                View child = rawTabs.get(i);
                String extracted = extractLabel(child);
                TabKind kind = inferTabKind(extracted, i, rawTabs.size());
                String label = (extracted != null && !extracted.isEmpty()) ? extracted : defaultLabelForKind(kind);
                tabs.add(new TabItemInfo(child, label, kind));
            }

            origTabBarVisibility = origTabBar.getVisibility();
            hideOriginalBar();

            View existing = root.findViewWithTag(CUSTOM_TABBAR_TAG);
            if (existing != null && existing.getParent() instanceof ViewGroup p)
                p.removeView(existing);

            customBar = new LiquidGlassTabBarView(root.getContext(), this);
            customBar.setTag(CUSTOM_TABBAR_TAG);

            float density = root.getResources().getDisplayMetrics().density;
            int sideMargin = Math.round(14f * density);
            int barHeight = Math.round(62f * density);
            int overflow = Math.round(24f * density);
            int bottomInset = getNavigationBarBottomInset(root);
            int bottomMargin = Math.max(bottomInset + Math.round(8f * density), Math.round(14f * density));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight + overflow * 2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.leftMargin = Math.max(0, sideMargin - overflow);
            lp.rightMargin = Math.max(0, sideMargin - overflow);
            lp.bottomMargin = Math.max(0, bottomMargin - overflow);

            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.addView(customBar, lp);
            customBar.bringToFront();

            attached = true;
            syncStateFromOriginal();
            customBar.snapImmediately();
            MAIN_HANDLER.postDelayed(syncPoller, 300);
            return true;
        }

        void refreshTheme()
        {
            isDarkTheme = (Config.getLiquidGlassTheme() == 1);
            if (customBar != null)
                customBar.invalidate();
        }

        void detach()
        {
            if (!attached)
                return;
            attached = false;
            MAIN_HANDLER.removeCallbacks(syncPoller);

            if (customBar != null && customBar.getParent() instanceof ViewGroup p)
                p.removeView(customBar);
            customBar = null;

            origTabBar.setVisibility(origTabBarVisibility);
            if (origWrapperBar != null)
            {
                origWrapperBar.setVisibility(origWrapperVisibility);
                origWrapperBar = null;
            }
            if (hiddenDivider != null)
            {
                hiddenDivider.setVisibility(View.VISIBLE);
                hiddenDivider = null;
            }
        }

        private void hideOriginalBar()
        {
            origTabBar.setVisibility(View.GONE);
            ViewParent parent = origTabBar.getParent();
            if (parent instanceof ViewGroup parentVg && parentVg != root)
            {
                float density = root.getResources().getDisplayMetrics().density;
                int idx = parentVg.indexOfChild(origTabBar);
                if (idx > 0)
                {
                    View prev = parentVg.getChildAt(idx - 1);
                    if (prev != null && prev.getHeight() > 0 && prev.getHeight() <= Math.round(3f * density))
                    {
                        hiddenDivider = prev;
                        prev.setVisibility(View.GONE);
                    }
                }
                if (parentVg.getHeight() > 0 && parentVg.getHeight() <= Math.round(115f * density))
                {
                    origWrapperBar = parentVg;
                    origWrapperVisibility = parentVg.getVisibility();
                    parentVg.setVisibility(View.GONE);
                }
            }
        }

        void selectTab(int index)
        {
            if (index < 0 || index >= tabs.size())
                return;
            lastUserSelectMs = SystemClock.uptimeMillis();
            View target = tabs.get(index).originalView;
            View clickable = findClickableTarget(target);
            View clickTarget = (clickable != null) ? clickable : target;
            try
            {
                clickTarget.callOnClick();
            }
            catch (Throwable ignored)
            {
                clickTarget.performClick();
            }
        }

        private View findClickableTarget(View v)
        {
            if (v.isClickable() && v.hasOnClickListeners())
                return v;
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    View found = findClickableTarget(vg.getChildAt(i));
                    if (found != null)
                        return found;
                }
            }
            return v.isClickable() ? v : null;
        }

        private void syncStateFromOriginal()
        {
            if (customBar == null)
                return;
            for (TabItemInfo info : tabs)
                updateBadgeInfo(info, info.originalView);

            long elapsed = SystemClock.uptimeMillis() - lastUserSelectMs;
            if (elapsed >= 700L)
            {
                int selectedIdx = -1;
                int singleLabelIdx = -1;
                int visibleLabelCount = 0;

                for (int i = 0; i < tabs.size(); i++)
                {
                    View v = tabs.get(i).originalView;
                    if (selectedIdx < 0 && isSubtreeSelected(v))
                        selectedIdx = i;
                    if (hasNonBadgeText(v))
                    {
                        singleLabelIdx = i;
                        visibleLabelCount++;
                    }
                }

                if (selectedIdx < 0 && visibleLabelCount == 1)
                    selectedIdx = singleLabelIdx;

                if (selectedIdx >= 0)
                    customBar.onExternalTabSelected(selectedIdx);
            }
            else
            {
                customBar.invalidate();
            }

            if (origTabBar.getVisibility() == View.VISIBLE)
                origTabBar.setVisibility(View.GONE);
            if (origWrapperBar != null && origWrapperBar.getVisibility() == View.VISIBLE)
                origWrapperBar.setVisibility(View.GONE);
        }

        private static TextView findAnyTextView(View v)
        {
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0)
            {
                String s = tv.getText().toString().trim();
                if (!s.matches("^[0-9+]+$") && !s.equals("N"))
                    return tv;
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    TextView found = findAnyTextView(vg.getChildAt(i));
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static String extractLabel(View v)
        {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0)
                return cd.toString().trim();
            TextView tv = findAnyTextView(v);
            if (tv != null && tv.getText() != null)
                return tv.getText().toString().trim();
            return "";
        }

        private static TabKind inferTabKind(String label, int index, int total)
        {
            String low = label != null ? label.toLowerCase() : "";
            if (low.contains("tin nh") || low.contains("message") || low.contains("chat"))
                return TabKind.MESSAGES;
            if (low.contains("danh b") || low.contains("contact"))
                return TabKind.CONTACTS;
            if (low.contains("khám ph") || low.contains("discover"))
                return TabKind.DISCOVERY;
            if (low.contains("nhật k") || low.contains("tường") || low.contains("timeline") || low.contains("diary"))
                return TabKind.TIMELINE;
            if (low.contains("cá nhân") || low.contains("me") || low.contains("profile"))
                return TabKind.PROFILE;
            if (low.contains("nhóm") || low.contains("group"))
                return TabKind.GROUPS;
            if (low.contains("thêm") || low.contains("more"))
                return TabKind.MORE;

            if (total == 5)
            {
                return switch (index)
                {
                    case 0 -> TabKind.MESSAGES;
                    case 1 -> TabKind.CONTACTS;
                    case 2 -> TabKind.DISCOVERY;
                    case 3 -> TabKind.TIMELINE;
                    default -> TabKind.PROFILE;
                };
            }
            if (total == 4)
            {
                return switch (index)
                {
                    case 0 -> TabKind.MESSAGES;
                    case 1 -> TabKind.CONTACTS;
                    case 2 -> TabKind.TIMELINE;
                    default -> TabKind.PROFILE;
                };
            }
            if (index == 0)
                return TabKind.MESSAGES;
            if (index == total - 1)
                return TabKind.PROFILE;
            return switch (index)
            {
                case 1 -> TabKind.CONTACTS;
                case 2 -> TabKind.DISCOVERY;
                case 3 -> TabKind.TIMELINE;
                default -> TabKind.MORE;
            };
        }

        private static String defaultLabelForKind(TabKind kind)
        {
            return switch (kind)
            {
                case MESSAGES -> "Tin nhắn";
                case CONTACTS -> "Danh bạ";
                case DISCOVERY -> "Khám phá";
                case TIMELINE -> "Nhật ký";
                case PROFILE -> "Cá nhân";
                case GROUPS -> "Nhóm";
                case MORE -> "Thêm";
            };
        }

        private static boolean isSubtreeSelected(View v)
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

        private static boolean hasNonBadgeText(View v)
        {
            if (v.getVisibility() != View.VISIBLE)
                return false;
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0 && tv.getAlpha() > 0.2f)
            {
                String s = tv.getText().toString().trim();
                if (!s.matches("^[0-9+]+$") && !s.equals("N") && !s.equals("!"))
                    return true;
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    if (hasNonBadgeText(vg.getChildAt(i)))
                        return true;
                }
            }
            return false;
        }

        private static void updateBadgeInfo(TabItemInfo info, View v)
        {
            info.badgeText = "";
            scanBadge(info, v);
        }

        private static void scanBadge(TabItemInfo info, View v)
        {
            if (v.getVisibility() != View.VISIBLE)
                return;
            if (v instanceof TextView tv && tv.getText() != null)
            {
                String s = tv.getText().toString().trim();
                if (!s.isEmpty() && (s.matches("^[0-9+]+$") || s.equals("N") || s.equals("!")))
                {
                    info.badgeText = s;
                    return;
                }
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                    scanBadge(info, vg.getChildAt(i));
            }
        }

        private static int getNavigationBarBottomInset(View view)
        {
            try
            {
                WindowInsets insets = view.getRootWindowInsets();
                if (insets != null)
                {
                    if (Build.VERSION.SDK_INT >= 30)
                        return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    else
                        return insets.getSystemWindowInsetBottom();
                }
            }
            catch (Throwable ignored) { }
            return 0;
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class LiquidGlassTabBarView extends View
    {
        // EXACT LIQUID CLOCK REFRACTION & DISPERSION SHADER (v10):
        // 1. At outer edge (u = 0, d = 0): displacement is MAXIMUM (grad * maxRefract), then decays
        //    smoothly as (1 - u)^2.1 to 0 at u = 1 (flat center plateau).
        //    -> Because grad points outward, sampling `fragCoord + grad * disp` pulls EXTERIOR pixels
        //       into the top/bottom bevel band, creating the EXACT straight-middle, corner-curved-up
        //       recessed lens band of Screenshot_2026-11-22-20-46-50-849_com.example.liquidclock-edit.jpg!
        //    -> And any icon/text straddling the side edge is smoothly pulled/warped INWARD into the rim
        //       with true 3-channel Cauchy RGB dispersion (0.76 / 1.00 / 1.28) and zero split/double ghosting!
        // 2. Center plateau (u = 1): disp = 0 -> 100% flat, crisp, stationary.
        private static final String LIQUID_CLOCK_AGSL =
            "uniform shader content;" +
            "uniform float4 uRect;" +
            "uniform float uRadius;" +
            "uniform float uInflate;" +
            "uniform float uDensity;" +
            "uniform float uDark;" +
            "float sdRoundBox(float2 p, float2 b, float r) {" +
            "  float2 q = abs(p) - b + r;" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;" +
            "}" +
            "float2 sdGrad(float2 p, float2 b, float r) {" +
            "  float e = 0.85;" +
            "  float gx = sdRoundBox(p + float2(e, 0.0), b, r) - sdRoundBox(p - float2(e, 0.0), b, r);" +
            "  float gy = sdRoundBox(p + float2(0.0, e), b, r) - sdRoundBox(p - float2(0.0, e), b, r);" +
            "  return normalize(float2(gx, gy) + float2(0.00001));" +
            "}" +
            "float3 sampleGlassColor(float2 coord, float isDarkMode) {" +
            "  half4 px = content.eval(coord);" +
            "  float a = clamp(float(px.a), 0.0, 1.0);" +
            "  float3 straight = (a > 0.004) ? (float3(px.rgb) / max(a, 0.004)) : float3(0.0);" +
            "  float mx = max(straight.r, max(straight.g, straight.b));" +
            "  float mn = min(straight.r, min(straight.g, straight.b));" +
            "  float sat = (mx - mn) / max(mx, 0.001);" +
            "  float luma = dot(straight, float3(0.299, 0.587, 0.114));" +
            "  float inkLight = smoothstep(0.42, 0.14, luma) * (1.0 - smoothstep(0.18, 0.35, sat));" +
            "  float inkDark  = smoothstep(0.60, 0.88, luma) * (1.0 - smoothstep(0.18, 0.35, sat));" +
            "  float inkMask = mix(inkLight, inkDark, isDarkMode);" +
            "  float3 activeBlue = mix(float3(0.0, 0.478, 1.0), float3(0.04, 0.52, 1.0), isDarkMode);" +
            "  float3 tinted = mix(straight, activeBlue, inkMask);" +
            "  float3 envCol = mix(float3(0.76, 0.80, 0.86), float3(0.01, 0.01, 0.015), isDarkMode);" +
            "  return mix(envCol, tinted, a);" +
            "}" +
            "half4 tintRestInk(half4 px, float isDarkMode) {" +
            "  float a = float(px.a);" +
            "  if (a < 0.05) return px;" +
            "  float3 straight = float3(px.rgb) / max(a, 0.001);" +
            "  float mx = max(straight.r, max(straight.g, straight.b));" +
            "  float mn = min(straight.r, min(straight.g, straight.b));" +
            "  float sat = (mx - mn) / max(mx, 0.001);" +
            "  float luma = dot(straight, float3(0.299, 0.587, 0.114));" +
            "  float inkLight = smoothstep(0.42, 0.14, luma) * (1.0 - smoothstep(0.18, 0.35, sat));" +
            "  float inkDark  = smoothstep(0.60, 0.88, luma) * (1.0 - smoothstep(0.18, 0.35, sat));" +
            "  float inkMask = mix(inkLight, inkDark, isDarkMode);" +
            "  float3 activeBlue = mix(float3(0.0, 0.478, 1.0), float3(0.04, 0.52, 1.0), isDarkMode);" +
            "  return half4(half3(mix(straight, activeBlue, inkMask) * a), px.a);" +
            "}" +
            "half4 main(float2 fragCoord) {" +
            "  float2 center = (uRect.xy + uRect.zw) * 0.5;" +
            "  float2 halfSize = (uRect.zw - uRect.xy) * 0.5;" +
            "  float2 p = fragCoord - center;" +
            "  float d = sdRoundBox(p, halfSize, uRadius);" +
            "  if (d > 0.8) return content.eval(fragCoord);" +
            "  float mask = 1.0 - smoothstep(-0.7, 0.7, d);" +
            "  half4 outsidePx = content.eval(fragCoord);" +
            "  if (uInflate <= 0.015) {" +
            "    return mix(outsidePx, tintRestInk(outsidePx, uDark), half(mask));" +
            "  }" +
            "  float bevelW = 17.5 * uDensity;" +
            "  float u = clamp(-d / bevelW, 0.0, 1.0);" +
            "  float lensCurve = pow(1.0 - u, 3.8);" +
            "  float2 grad = sdGrad(p, halfSize, uRadius);" +
            "  float refractMag = lensCurve * (16.5 * uDensity) * uInflate;" +
                        // Convex magnifying refraction (-grad) with 3-channel Cauchy RGB dispersion
            "  float3 cR = sampleGlassColor(fragCoord - grad * (refractMag * 0.65), uDark);" +
            "  float3 cG = sampleGlassColor(fragCoord - grad * (refractMag * 1.00), uDark);" +
            "  float3 cB = sampleGlassColor(fragCoord - grad * (refractMag * 1.42), uDark);" +
            "  float3 rgb = float3(cR.r, cG.g, cB.b);" +
                        // Ultra-thin physical Fresnel rim highlight (zero milky plastic fill)
            "  float fresnel = pow(1.0 - u, 4.0) * uInflate;" +
            "  float outerRim = (1.0 - smoothstep(0.0, 1.05 * uDensity, -d)) * uInflate;" +
            "  float ny = p.y / max(halfSize.y, 1.0);" +
            "  float topSheen = pow(max(0.0, -ny), 2.8) * fresnel;" +
            "  float botSheen = pow(max(0.0,  ny), 2.8) * fresnel;" +
            "  float spec = outerRim * mix(0.52, 0.34, uDark) + (topSheen * 0.20 + botSheen * 0.16);" +
            "  float3 glassRgb = clamp(rgb + float3(spec), 0.0, 1.0);" +
            "  float lensAlpha = clamp(mix(float(outsidePx.a), 0.96, uInflate), 0.0, 1.0);" +
            "  half4 lensPx = half4(half3(glassRgb * lensAlpha), half(lensAlpha));" +
            "  return mix(outsidePx, lensPx, half(mask));" +
            "}";

        private final TabBarReplacement owner;
        private final float density;
        private final float scaledDensity;
        private final int touchSlop;
        private final int overflowPad;

        private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint capsuleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint restPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerWhiteGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF capsuleRect = new RectF();
        private final RectF dropletRect = new RectF();
        private final Path capsuleClipPath = new Path();
        private final Path clipPath = new Path();
        private final Path iconPath = new Path();

        private Object unifiedRenderNodeObj;
        private Object runtimeShaderObj;

        // Physics state
        private int activeIndex = 0;
        private int hoveredIndex = 0;
        private float currentX = -1f;
        private float targetX = -1f;
        private float velocityX = 0f;
        private float currentW = 0f;
        private float targetW = 0f;
        private float inflateProgress = 0f;
        private float targetInflate = 0f;
        private float stretchX = 0f;
        private long lastFrameMs = 0L;
        private boolean animating = false;

        // Touch drag state
        private boolean pointerDown = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float lastTouchX = 0f;
        private long lastTouchMs = 0L;

        LiquidGlassTabBarView(Context context, TabBarReplacement owner)
        {
            super(context);
            this.owner = owner;
            this.density = context.getResources().getDisplayMetrics().density;
            this.scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            this.overflowPad = dp(24f);

            capsuleBorderPaint.setStyle(Paint.Style.STROKE);
            fallbackRimPaint.setStyle(Paint.Style.STROKE);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(10.5f * scaledDensity);

            badgePaint.setColor(0xFFFF3B30);
            badgeTextPaint.setColor(Color.WHITE);
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
            badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            badgeTextPaint.setTextSize(9f * scaledDensity);

            capsuleFillPaint.setShadowLayer(dpf(12f), 0f, dpf(4f), 0x1A001433);

            if (Build.VERSION.SDK_INT >= 33)
            {
                try
                {
                    unifiedRenderNodeObj = new RenderNode("DXLiquidClockGlassBar");
                    runtimeShaderObj = new RuntimeShader(LIQUID_CLOCK_AGSL);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }

        void snapImmediately()
        {
            if (getWidth() > 0 && !owner.tabs.isEmpty())
            {
                float tabW = capsuleRect.width() / owner.tabs.size();
                currentX = capsuleRect.left + tabW * (activeIndex + 0.5f);
                targetX = currentX;
                currentW = tabW;
                targetW = tabW;
                velocityX = 0f;
                inflateProgress = 0f;
                targetInflate = 0f;
                stretchX = 0f;
            }
            invalidate();
        }

        void onExternalTabSelected(int idx)
        {
            if (isDragging || pointerDown || animating)
                return;
            if (idx < 0 || idx >= owner.tabs.size())
                return;
            if (idx != activeIndex)
            {
                activeIndex = idx;
                hoveredIndex = idx;
                updateTargetForIndex(idx);
                startAnimationLoop();
            }
            else
            {
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh)
        {
            super.onSizeChanged(w, h, oldw, oldh);
            capsuleRect.set(overflowPad, overflowPad, w - overflowPad, h - overflowPad);
            capsuleClipPath.reset();
            float capR = capsuleRect.height() * 0.5f;
            capsuleClipPath.addRoundRect(capsuleRect, capR, capR, Path.Direction.CW);
            if (Build.VERSION.SDK_INT >= 29 && unifiedRenderNodeObj instanceof RenderNode node)
                node.setPosition(0, 0, w, h);
            snapImmediately();
        }

        private void drawUnifiedBarContent(Canvas canvas, boolean dark)
        {
            float capRadius = capsuleRect.height() * 0.5f;

            // 1. Translucent Glass Capsule Background (matching Liquid Clock charcoal / frosted white)
            int topGlass = dark ? 0xEE36383C : 0xECF8FAFE;
            int botGlass = dark ? 0xEA24262A : 0xE2EDF2FA;
            capsuleFillPaint.setShadowLayer(dpf(12f), 0f, dpf(4f), dark ? 0x66000000 : 0x1A001433);
            capsuleFillPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom, topGlass, botGlass, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsuleRect, capRadius, capRadius, capsuleFillPaint);
            capsuleFillPaint.setShader(null);

            // 2. Soft refractive edge rim (2.2dp soft + 1.0dp crisp) -> disperses into smooth rainbow inside lens
            float softSw = dpf(2.2f);
            capsuleBorderPaint.setShader(null);
            capsuleBorderPaint.setStrokeWidth(softSw);
            capsuleBorderPaint.setColor(dark ? 0x38FFFFFF : 0x50A0ACC0);
            RectF softInset = new RectF(
                capsuleRect.left + softSw * 0.5f,
                capsuleRect.top + softSw * 0.5f,
                capsuleRect.right - softSw * 0.5f,
                capsuleRect.bottom - softSw * 0.5f);
            canvas.drawRoundRect(softInset, capRadius, capRadius, capsuleBorderPaint);

            float sw = dpf(1.0f);
            capsuleBorderPaint.setStrokeWidth(sw);
            capsuleBorderPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom,
                dark ? new int[] { 0x78FFFFFF, 0x30FFFFFF, 0x55FFFFFF }
                     : new int[] { 0xE6FFFFFF, 0x30A0B0C8, 0xF2FFFFFF },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
            RectF borderInset = new RectF(
                capsuleRect.left + sw * 0.5f,
                capsuleRect.top + sw * 0.5f,
                capsuleRect.right - sw * 0.5f,
                capsuleRect.bottom - sw * 0.5f);
            canvas.drawRoundRect(borderInset, capRadius, capRadius, capsuleBorderPaint);

            // 4. Resting matte pill: vanishes cleanly on touch/hold
            float restAlpha = clamp(1f - inflateProgress * 4.5f, 0f, 1f);
            if (!dropletRect.isEmpty() && restAlpha > 0.01f)
            {
                float dropR = dropletRect.height() * 0.5f;
                restPillPaint.setColor(dark
                    ? Color.argb((int) (175 * restAlpha), 74, 78, 86)
                    : Color.argb((int) (205 * restAlpha), 214, 219, 228));
                canvas.drawRoundRect(dropletRect, dropR, dropR, restPillPaint);
            }

            // 5. All tab icons & labels drawn in neutral ink (tinted active blue inside GPU shader)
            int inkColor = dark ? 0xFFFFFFFF : 0xFF111111;
            drawAllTabItems(canvas, inkColor, false);
        }

        private void drawAllTabItems(Canvas canvas, int color, boolean activeBold)
        {
            int count = owner.tabs.size();
            if (count == 0)
                return;
            float tabW = capsuleRect.width() / count;
            for (int i = 0; i < count; i++)
            {
                TabItemInfo info = owner.tabs.get(i);
                float cx = capsuleRect.left + tabW * (i + 0.5f);
                float cy = capsuleRect.centerY();
                drawSingleTabItem(canvas, info, cx, cy, color, activeBold || i == activeIndex);
            }
        }

        private void drawSingleTabItem(Canvas canvas, TabItemInfo info, float cx, float cy, int color, boolean bold)
        {
            float iconSize = dpf(22f);
            float iconCy = cy - dpf(7.5f);
            drawVectorTabIcon(canvas, info.kind, cx, iconCy, iconSize, color);

            textPaint.setColor(color);
            textPaint.setFakeBoldText(bold);
            float textY = cy + dpf(16.5f);
            canvas.drawText(info.label, cx, textY, textPaint);

            if (info.badgeText != null && !info.badgeText.isEmpty())
            {
                float bx = cx + dpf(12f);
                float by = iconCy - dpf(8f);
                float bw = Math.max(dpf(15f), badgeTextPaint.measureText(info.badgeText) + dpf(7f));
                float bh = dpf(14f);
                RectF br = new RectF(bx - bw * 0.5f, by - bh * 0.5f, bx + bw * 0.5f, by + bh * 0.5f);
                canvas.drawRoundRect(br, bh * 0.5f, bh * 0.5f, badgePaint);
                canvas.drawText(info.badgeText, bx, by + dpf(3.2f), badgeTextPaint);
            }
        }

        private void drawVectorTabIcon(Canvas canvas, TabKind kind, float cx, float cy, float size, int color)
        {
            float r = size * 0.5f;
            iconPaint.setColor(color);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            switch (kind)
            {
                case MESSAGES:
                    iconPaint.setStyle(Paint.Style.FILL);
                    RectF bubble = new RectF(cx - r * 0.92f, cy - r * 0.80f, cx + r * 0.92f, cy + r * 0.68f);
                    canvas.drawRoundRect(bubble, r * 0.65f, r * 0.65f, iconPaint);
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.42f, cy + r * 0.56f);
                    iconPath.lineTo(cx - r * 0.68f, cy + r * 0.92f);
                    iconPath.lineTo(cx - r * 0.08f, cy + r * 0.66f);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.8f));
                    canvas.drawLine(cx - r * 0.38f, cy - r * 0.16f, cx + r * 0.38f, cy - r * 0.16f, iconPaint);
                    canvas.drawLine(cx - r * 0.38f, cy + r * 0.14f, cx + r * 0.14f, cy + r * 0.14f, iconPaint);
                    break;

                case CONTACTS:
                    iconPaint.setStyle(Paint.Style.FILL);
                    RectF book = new RectF(cx - r * 0.78f, cy - r * 0.86f, cx + r * 0.78f, cy + r * 0.86f);
                    canvas.drawRoundRect(book, r * 0.25f, r * 0.25f, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.7f));
                    canvas.drawCircle(cx, cy - r * 0.18f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.42f, cy + r * 0.16f, cx + r * 0.42f, cy + r * 0.85f, 195f, 150f, false, iconPaint);
                    break;

                case DISCOVERY:
                    iconPaint.setStyle(Paint.Style.FILL);
                    float gap = r * 0.14f;
                    float sq = r * 0.66f;
                    float cr = r * 0.14f;
                    canvas.drawRoundRect(cx - sq - gap, cy - sq - gap, cx - gap, cy - gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx - sq - gap, cy + gap, cx - gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx + gap, cy + gap, cx + sq + gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.save();
                    canvas.rotate(45f, cx + gap + sq * 0.5f, cy - gap - sq * 0.5f);
                    canvas.drawRoundRect(cx + gap + r * 0.06f, cy - sq - gap + r * 0.06f, cx + sq + gap - r * 0.06f, cy - gap - r * 0.06f, cr * 0.8f, cr * 0.8f, iconPaint);
                    canvas.restore();
                    break;

                case TIMELINE:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, r * 0.84f, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    canvas.drawLine(cx, cy - r * 0.44f, cx, cy + r * 0.04f, iconPaint);
                    canvas.drawLine(cx, cy + r * 0.04f, cx + r * 0.36f, cy + r * 0.24f, iconPaint);
                    break;

                case PROFILE:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy - r * 0.36f, r * 0.38f, iconPaint);
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.78f, cy + r * 0.84f);
                    iconPath.quadTo(cx - r * 0.78f, cy + r * 0.20f, cx, cy + r * 0.20f);
                    iconPath.quadTo(cx + r * 0.78f, cy + r * 0.20f, cx + r * 0.78f, cy + r * 0.84f);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    break;

                case GROUPS:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - r * 0.28f, cy - r * 0.30f, r * 0.30f, iconPaint);
                    canvas.drawCircle(cx + r * 0.34f, cy - r * 0.22f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.88f, cy + r * 0.12f, cx + r * 0.32f, cy + r * 1.10f, 180f, 180f, true, iconPaint);
                    canvas.drawArc(cx - r * 0.10f, cy + r * 0.20f, cx + r * 0.88f, cy + r * 1.05f, 180f, 180f, true, iconPaint);
                    break;

                case MORE:
                default:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - r * 0.56f, cy, r * 0.18f, iconPaint);
                    canvas.drawCircle(cx, cy, r * 0.18f, iconPaint);
                    canvas.drawCircle(cx + r * 0.56f, cy, r * 0.18f, iconPaint);
                    break;
            }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            super.onDraw(canvas);
            if (capsuleRect.isEmpty() || owner.tabs.isEmpty())
                return;

            boolean dark = owner.isDarkTheme;
            computeDropletRect(dropletRect);
            float dropRadius = dropletRect.height() * 0.5f;

            if (Build.VERSION.SDK_INT >= 33
                && canvas.isHardwareAccelerated()
                && unifiedRenderNodeObj instanceof RenderNode node
                && runtimeShaderObj instanceof RuntimeShader shader
                && !dropletRect.isEmpty())
            {
                node.setPosition(0, 0, getWidth(), getHeight());
                RecordingCanvas rc = node.beginRecording();
                drawUnifiedBarContent(rc, dark);
                node.endRecording();

                shader.setFloatUniform("uRect", dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom);
                shader.setFloatUniform("uRadius", dropRadius);
                shader.setFloatUniform("uInflate", inflateProgress);
                shader.setFloatUniform("uDensity", density);
                shader.setFloatUniform("uDark", dark ? 1f : 0f);
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));

                canvas.drawRenderNode(node);
            }
            else
            {
                drawUnifiedBarContent(canvas, dark);
                if (!dropletRect.isEmpty())
                {
                    int activeBlue = dark ? 0xFF0A84FF : 0xFF007AFF;
                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(dropletRect, dropRadius, dropRadius, Path.Direction.CW);
                    canvas.clipPath(clipPath);
                    drawAllTabItems(canvas, activeBlue, true);
                    canvas.restore();

                    if (inflateProgress > 0.02f)
                    {
                        fallbackRimPaint.setStrokeWidth(dpf(1.0f));
                        fallbackRimPaint.setColor(Color.argb((int) (220 * inflateProgress), 255, 255, 255));
                        canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, fallbackRimPaint);
                    }
                }
            }
        }

        private void computeDropletRect(RectF out)
        {
            if (capsuleRect.isEmpty() || currentW <= 0f)
            {
                out.setEmpty();
                return;
            }
            float restH = Math.max(capsuleRect.height() - dpf(10f), dpf(44f));
            float restW = Math.max(currentW - dpf(4f), restH * 1.28f);

            float inflatedH = capsuleRect.height() + dpf(18.0f);
            float inflatedW = Math.max(currentW * 1.72f, inflatedH * 1.56f);

            float absStretch = Math.abs(stretchX);
            float w = (restW + (inflatedW - restW) * inflateProgress) * (1f + absStretch * 0.36f);
            float h = (restH + (inflatedH - restH) * inflateProgress) * (1f - absStretch * 0.14f);

            float cx = currentX;
            float cy = capsuleRect.centerY();
            out.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event)
        {
            int count = owner.tabs.size();
            if (count == 0 || capsuleRect.isEmpty())
                return false;

            float x = event.getX();
            float y = event.getY();
            int action = event.getActionMasked();

            switch (action)
            {
                case MotionEvent.ACTION_DOWN:
                    if (x < capsuleRect.left - dpf(6f) || x > capsuleRect.right + dpf(6f)
                        || y < capsuleRect.top - dpf(8f) || y > capsuleRect.bottom + dpf(8f))
                        return false;

                    pointerDown = true;
                    isDragging = false;
                    downX = x;
                    lastTouchX = x;
                    lastTouchMs = SystemClock.uptimeMillis();
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(true);

                    int hit = indexForX(x, count);
                    hoveredIndex = hit;
                    targetInflate = 1.0f;
                    updateTargetForIndex(hit);
                    startAnimationLoop();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!pointerDown)
                        return false;
                    if (!isDragging && Math.abs(x - downX) > touchSlop * 0.4f)
                        isDragging = true;

                    long now = SystemClock.uptimeMillis();
                    float dt = Math.max(1f, now - lastTouchMs);
                    float instVel = (x - lastTouchX) / dt * 16f;
                    lastTouchX = x;
                    lastTouchMs = now;

                    float tabW = capsuleRect.width() / count;
                    float minCx = capsuleRect.left + tabW * 0.44f;
                    float maxCx = capsuleRect.right - tabW * 0.44f;
                    float clampedX = clamp(x, minCx, maxCx);

                    targetX = clampedX;
                    targetW = tabW;
                    targetInflate = 1.0f;
                    stretchX = clamp(instVel * 0.018f, -0.22f, 0.22f);

                    int newHover = indexForX(clampedX, count);
                    if (newHover != hoveredIndex)
                    {
                        hoveredIndex = newHover;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    startAnimationLoop();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!pointerDown)
                        return false;
                    pointerDown = false;
                    boolean didDrag = isDragging;
                    isDragging = false;
                    targetInflate = 0f;

                    int finalIdx = didDrag ? clampInt(hoveredIndex, 0, count - 1) : indexForX(x, count);
                    activeIndex = finalIdx;
                    hoveredIndex = finalIdx;
                    updateTargetForIndex(finalIdx);
                    startAnimationLoop();

                    if (action == MotionEvent.ACTION_UP)
                        owner.selectTab(finalIdx);
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private int indexForX(float x, int count)
        {
            float rel = (x - capsuleRect.left) / Math.max(capsuleRect.width(), 1f);
            return clampInt((int) Math.floor(rel * count), 0, count - 1);
        }

        private void updateTargetForIndex(int idx)
        {
            int count = owner.tabs.size();
            if (count == 0 || capsuleRect.isEmpty())
                return;
            float tabW = capsuleRect.width() / count;
            targetX = capsuleRect.left + tabW * (idx + 0.5f);
            targetW = tabW;
            if (currentX < 0f)
            {
                currentX = targetX;
                currentW = targetW;
            }
        }

        private void startAnimationLoop()
        {
            if (animating)
                return;
            animating = true;
            lastFrameMs = SystemClock.uptimeMillis();
            postOnAnimation(frameRunnable);
        }

        private final Runnable frameRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                long now = SystemClock.uptimeMillis();
                float dt = clamp((now - lastFrameMs) / 1000f, 0.004f, 0.032f);
                lastFrameMs = now;

                float stiffness = isDragging ? 460f : 270f;
                float damping = isDragging ? 28f : 21f;
                float force = (targetX - currentX) * stiffness;
                velocityX += (force - velocityX * damping) * dt;
                currentX += velocityX * dt;

                currentW += (targetW - currentW) * Math.min(1f, dt * 16f);
                inflateProgress += (targetInflate - inflateProgress) * Math.min(1f, dt * 16f);

                float velStretchTarget = clamp(velocityX * 0.00018f, -0.22f, 0.22f);
                stretchX += (velStretchTarget - stretchX) * Math.min(1f, dt * 18f);

                invalidate();

                boolean settled = !isDragging
                    && !pointerDown
                    && Math.abs(targetX - currentX) < 0.35f
                    && Math.abs(velocityX) < 1.5f
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
                    invalidate();
                }
                else
                {
                    postOnAnimation(this);
                }
            }
        };

        private int dp(float v)
        {
            return Math.round(v * density);
        }

        private float dpf(float v)
        {
            return v * density;
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
