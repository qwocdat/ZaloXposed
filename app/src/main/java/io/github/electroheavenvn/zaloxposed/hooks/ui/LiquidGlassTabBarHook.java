package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import android.view.ViewOutlineProvider;
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
 * Replaces Zalo's original bottom tab bar with a standalone floating rounded Liquid Glass TabBar
 * featuring real optical refraction (AGSL RuntimeShader lens magnification, edge bending,
 * chromatic dispersion, and partial active-blue tinting inside the sliding crystal droplet).
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
        boolean hasDot = false;

        TabItemInfo(View originalView, String label, TabKind kind)
        {
            this.originalView = originalView;
            this.label = label;
            this.kind = kind;
        }
    }

    /**
     * Hides Zalo's original bottom bar cleanly and attaches our standalone LiquidGlassTabBarView.
     */
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

        private final Runnable syncPoller = new Runnable()
        {
            @Override
            public void run()
            {
                if (!attached || customBar == null)
                    return;
                syncStateFromOriginal();
                MAIN_HANDLER.postDelayed(this, 220);
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

            isDarkTheme = detectZaloDarkTheme(origTabBar);

            tabs.clear();
            for (int i = 0; i < rawTabs.size(); i++)
            {
                View child = rawTabs.get(i);
                String extracted = extractLabel(child);
                TabKind kind = inferTabKind(extracted, i, rawTabs.size());
                String label = (extracted != null && !extracted.isEmpty()) ? extracted : defaultLabelForKind(kind);
                tabs.add(new TabItemInfo(child, label, kind));
            }

            // Hide Zalo's original tab bar & its thin divider/wrapper cleanly
            origTabBarVisibility = origTabBar.getVisibility();
            hideOriginalBar();

            // Remove any stale custom bar
            View existing = root.findViewWithTag(CUSTOM_TABBAR_TAG);
            if (existing != null && existing.getParent() instanceof ViewGroup p)
                p.removeView(existing);

            customBar = new LiquidGlassTabBarView(root.getContext(), this);
            customBar.setTag(CUSTOM_TABBAR_TAG);

            float density = root.getResources().getDisplayMetrics().density;
            int sideMargin = Math.round(14f * density);
            int barHeight = Math.round(62f * density);
            int overflow = Math.round(16f * density);
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
            MAIN_HANDLER.postDelayed(syncPoller, 250);
            return true;
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
                // Hide 1-2dp divider directly above original bar
                int idx = parentVg.indexOfChild(origTabBar);
                if (idx > 0)
                {
                    View prev = parentVg.getChildAt(idx - 1);
                    if (prev != null && prev.getHeight() > 0 && prev.getHeight() <= Math.round(3f * root.getResources().getDisplayMetrics().density))
                    {
                        hiddenDivider = prev;
                        prev.setVisibility(View.GONE);
                    }
                }
                // If parent wrapper is exclusively a bottom bar container (small height), hide it too
                float density = root.getResources().getDisplayMetrics().density;
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
            View target = tabs.get(index).originalView;
            View clickable = findClickableTarget(target);
            if (clickable != null)
                clickable.performClick();
            else
                target.performClick();
        }

        private View findClickableTarget(View v)
        {
            if (v.isClickable())
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
            return null;
        }

        private void syncStateFromOriginal()
        {
            if (customBar == null)
                return;
            int selectedIdx = -1;
            int singleLabelIdx = -1;
            int visibleLabelCount = 0;

            for (int i = 0; i < tabs.size(); i++)
            {
                TabItemInfo info = tabs.get(i);
                View v = info.originalView;
                if (selectedIdx < 0 && isSubtreeSelected(v))
                    selectedIdx = i;
                if (hasNonBadgeText(v))
                {
                    singleLabelIdx = i;
                    visibleLabelCount++;
                }
                updateBadgeInfo(info, v);
            }

            if (selectedIdx < 0 && visibleLabelCount == 1)
                selectedIdx = singleLabelIdx;

            if (selectedIdx >= 0)
                customBar.onExternalTabSelected(selectedIdx);

            // Re-ensure original bar stays hidden if Zalo tries to re-show it
            if (origTabBar.getVisibility() == View.VISIBLE)
                origTabBar.setVisibility(View.GONE);
            if (origWrapperBar != null && origWrapperBar.getVisibility() == View.VISIBLE)
                origWrapperBar.setVisibility(View.GONE);
        }

        private static boolean detectZaloDarkTheme(ViewGroup tabBar)
        {
            // 1. Check original tabBar or immediate parent background color
            View cur = tabBar;
            for (int i = 0; i < 3 && cur != null; i++)
            {
                Drawable bg = cur.getBackground();
                if (bg instanceof ColorDrawable cd)
                {
                    int color = cd.getColor();
                    if (Color.alpha(color) > 120)
                    {
                        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
                        return lum < 0.42;
                    }
                }
                ViewParent p = cur.getParent();
                cur = (p instanceof View pv) ? pv : null;
            }
            // 2. Check text color inside tabBar (light text => dark theme, dark text => light theme)
            TextView tv = findAnyTextView(tabBar);
            if (tv != null)
            {
                int tc = tv.getCurrentTextColor();
                double lum = (0.299 * Color.red(tc) + 0.587 * Color.green(tc) + 0.114 * Color.blue(tc)) / 255.0;
                if (lum > 0.72)
                    return true;
                if (lum < 0.40)
                    return false;
            }
            // Default to crisp bright Light Liquid Glass (matching the reference video)
            return false;
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
            info.hasDot = false;
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

    /**
     * Standalone Liquid Glass TabBar View with REAL Optical Refraction:
     * - Renders inactive dark tabs onto an offscreen Bitmap (normalLayer)
     * - Renders active blue tabs onto an offscreen Bitmap (activeBlueLayer)
     * - Runs an AGSL RuntimeShader (Android 13+) over the sliding liquid glass droplet that
     *   genuinely refracts, magnifies, edge-bends, and chromatically disperses the activeBlueLayer
     *   right through the crystal droplet — matching the 8-second reference video.
     */
    @SuppressLint("ViewConstructor")
    private static final class LiquidGlassTabBarView extends View
    {
        // Real optical glass lens refraction shader (Android 13+ / API 33+)
        private static final String REFRACTION_LENS_AGSL =
            "uniform shader uActiveTex;" +
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
            "  float rimZone = smoothstep(-16.0, 0.0, d);" +
            "  float crispRim = smoothstep(-3.0, -0.2, d);" +
            "  // Real convex lens magnification + curved glass rim bending (Snell refraction)\n" +
            "  float zoom = mix(1.02, 1.13, uInflate);" +
            "  float2 normDir = normalize(p + float2(0.0001));" +
            "  float bendStrength = mix(2.0, 14.0, uInflate) * rimZone * rimZone;" +
            "  float2 baseCoord = center + (p / zoom) - normDir * bendStrength;" +
            "  // Subtle chromatic dispersion (RGB split near glass meniscus)\n" +
            "  float chroma = mix(0.25, 2.6, uInflate) * rimZone;" +
            "  half4 sR = uActiveTex.eval(baseCoord - normDir * chroma);" +
            "  half4 sG = uActiveTex.eval(baseCoord);" +
            "  half4 sB = uActiveTex.eval(baseCoord + normDir * chroma);" +
            "  half3 contentRgb = half3(sR.r, sG.g, sB.b);" +
            "  half contentA = max(sG.a, max(sR.a, sB.a) * half(0.85));" +
            "  // Glass body fill: soft pill at rest, crystal clear glass during slide\n" +
            "  half3 restPillLight = half3(0.91, 0.94, 0.99);" +
            "  half3 dragGlassLight = half3(0.97, 0.99, 1.00);" +
            "  half3 restPillDark = half3(0.22, 0.24, 0.29);" +
            "  half3 dragGlassDark = half3(0.26, 0.29, 0.36);" +
            "  half3 glassLight = mix(restPillLight, dragGlassLight, half(uInflate));" +
            "  half3 glassDark = mix(restPillDark, dragGlassDark, half(uInflate));" +
            "  half3 glassBg = mix(glassLight, glassDark, half(uDark));" +
            "  float glassAlpha = mix(mix(0.88, 0.72, uDark), mix(0.64, 0.56, uDark), uInflate);" +
            "  // Composite refracted active-blue icon/text over crystal glass\n" +
            "  half3 finalRgb = mix(glassBg, contentRgb, contentA);" +
            "  // Curved top & bottom specular meniscus reflections\n" +
            "  float topArc = pow(max(0.0, -uv.y), 2.3) * (1.0 - uv.x * uv.x * 0.45) * rimZone;" +
            "  float botArc = pow(max(0.0, uv.y), 2.5) * (1.0 - uv.x * uv.x * 0.45) * rimZone;" +
            "  finalRgb += half3(1.0, 1.0, 1.0) * half((topArc * 0.55 + botArc * 0.30) * (0.35 + 0.65 * uInflate));" +
            "  // Crisp glass rim & subtle blue-white edge sheen\n" +
            "  half3 rimCol = mix(half3(1.0, 1.0, 1.0), half3(0.75, 0.88, 1.0), half(rimZone * uInflate));" +
            "  finalRgb = mix(finalRgb, rimCol, half(crispRim * (0.45 + 0.45 * uInflate)));" +
            "  float outA = clamp(max(glassAlpha, float(contentA)) * mask, 0.0, 1.0);" +
            "  return half4(finalRgb * half(outA), half(outA));" +
            "}";

        private final TabBarReplacement owner;
        private final float density;
        private final float scaledDensity;
        private final int touchSlop;
        private final int overflowPad;

        private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint capsuleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dropletShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF capsuleRect = new RectF();
        private final RectF dropletRect = new RectF();
        private final Path clipPath = new Path();
        private final Path iconPath = new Path();

        private Bitmap normalLayerBmp;
        private Canvas normalLayerCanvas;
        private Bitmap activeBlueLayerBmp;
        private Canvas activeBlueLayerCanvas;
        private BitmapShader activeBlueShader;
        private Object runtimeShaderObj;
        private boolean layersDirty = true;

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
            this.overflowPad = dp(16f);

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

            // Soft ambient shadow under the floating capsule
            setLayerType(LAYER_TYPE_HARDWARE, null);
            capsuleFillPaint.setShadowLayer(dpf(14f), 0f, dpf(5f), 0x22001433);
            dropletShadowPaint.setColor(Color.TRANSPARENT);
            dropletShadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f), 0x24002050);

            if (Build.VERSION.SDK_INT >= 33)
            {
                try
                {
                    runtimeShaderObj = new RuntimeShader(REFRACTION_LENS_AGSL);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }

        void snapImmediately()
        {
            layersDirty = true;
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
            if (isDragging || pointerDown)
                return;
            if (idx < 0 || idx >= owner.tabs.size())
                return;
            layersDirty = true;
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
            rebuildOffscreenBitmaps(w, h);
            snapImmediately();
        }

        private void rebuildOffscreenBitmaps(int w, int h)
        {
            if (w <= 0 || h <= 0)
                return;
            if (normalLayerBmp != null)
                normalLayerBmp.recycle();
            if (activeBlueLayerBmp != null)
                activeBlueLayerBmp.recycle();
            normalLayerBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            normalLayerCanvas = new Canvas(normalLayerBmp);
            activeBlueLayerBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            activeBlueLayerCanvas = new Canvas(activeBlueLayerBmp);
            activeBlueShader = new BitmapShader(activeBlueLayerBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            layersDirty = true;
        }

        private void renderTabLayersIfNeeded()
        {
            if (!layersDirty || normalLayerCanvas == null || activeBlueLayerCanvas == null)
                return;
            layersDirty = false;

            normalLayerBmp.eraseColor(Color.TRANSPARENT);
            activeBlueLayerBmp.eraseColor(Color.TRANSPARENT);

            int count = owner.tabs.size();
            if (count == 0)
                return;

            boolean dark = owner.isDarkTheme;
            int inactiveColor = dark ? 0xFFF3F4F6 : 0xFF111827;
            int activeBlue = dark ? 0xFF389BFF : 0xFF007AFF;

            float tabW = capsuleRect.width() / count;
            for (int i = 0; i < count; i++)
            {
                TabItemInfo info = owner.tabs.get(i);
                float cx = capsuleRect.left + tabW * (i + 0.5f);
                float cy = capsuleRect.centerY();
                drawSingleTabItem(normalLayerCanvas, info, cx, cy, inactiveColor, false);
                drawSingleTabItem(activeBlueLayerCanvas, info, cx, cy, activeBlue, true);
            }
        }

        private void drawSingleTabItem(Canvas canvas, TabItemInfo info, float cx, float cy, int color, boolean filledActive)
        {
            float iconSize = dpf(22f);
            float iconCy = cy - dpf(7.5f);
            drawVectorTabIcon(canvas, info.kind, cx, iconCy, iconSize, color, filledActive);

            textPaint.setColor(color);
            textPaint.setFakeBoldText(filledActive);
            float textY = cy + dpf(16.5f);
            canvas.drawText(info.label, cx, textY, textPaint);

            // Unread badge
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

        private void drawVectorTabIcon(Canvas canvas, TabKind kind, float cx, float cy, float size, int color, boolean filled)
        {
            float r = size * 0.5f;
            iconPaint.setColor(color);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            switch (kind)
            {
                case MESSAGES:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    RectF bubble = new RectF(cx - r * 0.92f, cy - r * 0.80f, cx + r * 0.92f, cy + r * 0.68f);
                    canvas.drawRoundRect(bubble, r * 0.65f, r * 0.65f, iconPaint);
                    // Speech tail
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.42f, cy + r * 0.56f);
                    iconPath.lineTo(cx - r * 0.68f, cy + r * 0.92f);
                    iconPath.lineTo(cx - r * 0.08f, cy + r * 0.66f);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    if (filled)
                    {
                        iconPaint.setColor(Color.WHITE);
                        iconPaint.setStyle(Paint.Style.STROKE);
                        iconPaint.setStrokeWidth(dpf(1.8f));
                        canvas.drawLine(cx - r * 0.38f, cy - r * 0.16f, cx + r * 0.38f, cy - r * 0.16f, iconPaint);
                        canvas.drawLine(cx - r * 0.38f, cy + r * 0.14f, cx + r * 0.14f, cy + r * 0.14f, iconPaint);
                    }
                    break;

                case CONTACTS:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    RectF book = new RectF(cx - r * 0.78f, cy - r * 0.86f, cx + r * 0.78f, cy + r * 0.86f);
                    canvas.drawRoundRect(book, r * 0.25f, r * 0.25f, iconPaint);
                    iconPaint.setColor(filled ? Color.WHITE : color);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.7f));
                    canvas.drawCircle(cx, cy - r * 0.18f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.42f, cy + r * 0.16f, cx + r * 0.42f, cy + r * 0.85f, 195f, 150f, false, iconPaint);
                    break;

                case DISCOVERY:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.8f));
                    float gap = r * 0.14f;
                    float sq = r * 0.66f;
                    float cr = r * 0.18f;
                    canvas.drawRoundRect(cx - sq - gap, cy - sq - gap, cx - gap, cy - gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx - sq - gap, cy + gap, cx - gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx + gap, cy + gap, cx + sq + gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.save();
                    canvas.rotate(45f, cx + gap + sq * 0.5f, cy - gap - sq * 0.5f);
                    canvas.drawRoundRect(cx + gap + r * 0.06f, cy - sq - gap + r * 0.06f, cx + sq + gap - r * 0.06f, cy - gap - r * 0.06f, cr * 0.8f, cr * 0.8f, iconPaint);
                    canvas.restore();
                    break;

                case TIMELINE:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    canvas.drawCircle(cx, cy, r * 0.84f, iconPaint);
                    iconPaint.setColor(filled ? Color.WHITE : color);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.85f));
                    canvas.drawLine(cx, cy - r * 0.44f, cx, cy + r * 0.04f, iconPaint);
                    canvas.drawLine(cx, cy + r * 0.04f, cx + r * 0.36f, cy + r * 0.24f, iconPaint);
                    break;

                case PROFILE:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    canvas.drawCircle(cx, cy - r * 0.36f, r * 0.38f, iconPaint);
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.78f, cy + r * 0.84f);
                    iconPath.quadTo(cx - r * 0.78f, cy + r * 0.22f, cx, cy + r * 0.22f);
                    iconPath.quadTo(cx + r * 0.78f, cy + r * 0.22f, cx + r * 0.78f, cy + r * 0.84f);
                    if (filled)
                        iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    break;

                case GROUPS:
                    iconPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.8f));
                    canvas.drawCircle(cx - r * 0.28f, cy - r * 0.30f, r * 0.30f, iconPaint);
                    canvas.drawCircle(cx + r * 0.34f, cy - r * 0.22f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.88f, cy + r * 0.12f, cx + r * 0.32f, cy + r * 1.10f, 180f, 180f, filled, iconPaint);
                    canvas.drawArc(cx - r * 0.10f, cy + r * 0.20f, cx + r * 0.88f, cy + r * 1.05f, 180f, 180f, filled, iconPaint);
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

            renderTabLayersIfNeeded();

            boolean dark = owner.isDarkTheme;
            float capRadius = capsuleRect.height() * 0.5f;

            // 1. Floating Frosted Liquid Glass Capsule + Ambient Shadow
            int bgTop = dark ? 0xDC1B1E24 : 0xECF9FBFF;
            int bgBot = dark ? 0xD4121419 : 0xE2EEF3FA;
            capsuleFillPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom, bgTop, bgBot, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsuleRect, capRadius, capRadius, capsuleFillPaint);
            capsuleFillPaint.setShader(null);

            // Crisp Glass Specular Border around outer capsule
            float sw = dpf(1.2f);
            capsuleBorderPaint.setStrokeWidth(sw);
            capsuleBorderPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom,
                dark ? new int[] { 0x4CFFFFFF, 0x16FFFFFF, 0x2AFFFFFF }
                     : new int[] { 0xFFFFFFFF, 0x80D4E0F2, 0xE8FFFFFF },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
            RectF borderInset = new RectF(
                capsuleRect.left + sw * 0.5f,
                capsuleRect.top + sw * 0.5f,
                capsuleRect.right - sw * 0.5f,
                capsuleRect.bottom - sw * 0.5f);
            canvas.drawRoundRect(borderInset, capRadius, capRadius, capsuleBorderPaint);

            // 2. Compute current Liquid Glass Droplet bounds
            computeDropletRect(dropletRect);
            float dropRadius = dropletRect.height() * 0.5f;

            // 3. Draw inactive dark tabs layer OUTSIDE the droplet (so there's zero ghosting inside lens!)
            if (normalLayerBmp != null)
            {
                canvas.save();
                if (!dropletRect.isEmpty())
                {
                    clipPath.reset();
                    clipPath.addRoundRect(dropletRect, dropRadius, dropRadius, Path.Direction.CW);
                    canvas.clipOutPath(clipPath);
                }
                canvas.drawBitmap(normalLayerBmp, 0f, 0f, null);
                canvas.restore();
            }

            // 4. Draw REAL Optical-Refraction Liquid Glass Droplet with refracted active-blue content!
            if (!dropletRect.isEmpty())
            {
                // Subtle floating shadow when droplet inflates during press/slide
                if (inflateProgress > 0.02f)
                {
                    dropletShadowPaint.setColor(Color.argb((int) (18 * inflateProgress), 255, 255, 255));
                    canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, dropletShadowPaint);
                }

                if (Build.VERSION.SDK_INT >= 33 && runtimeShaderObj instanceof RuntimeShader shader && activeBlueShader != null)
                {
                    shader.setInputShader("uActiveTex", activeBlueShader);
                    shader.setFloatUniform("uRect", dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom);
                    shader.setFloatUniform("uRadius", dropRadius);
                    shader.setFloatUniform("uInflate", inflateProgress);
                    shader.setFloatUniform("uDark", dark ? 1f : 0f);
                    lensPaint.setShader(shader);
                    canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, lensPaint);
                    lensPaint.setShader(null);
                }
                else
                {
                    // Clean fallback for Android 8–12: glass pill + magnified blue content clipped inside droplet
                    fallbackPillPaint.setColor(dark
                        ? Color.argb((int) (165 - inflateProgress * 30), 56, 62, 74)
                        : Color.argb((int) (220 - inflateProgress * 45), 232, 240, 254));
                    canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, fallbackPillPaint);

                    if (activeBlueLayerBmp != null)
                    {
                        canvas.save();
                        clipPath.reset();
                        clipPath.addRoundRect(dropletRect, dropRadius, dropRadius, Path.Direction.CW);
                        canvas.clipPath(clipPath);
                        float zoom = 1.02f + inflateProgress * 0.11f;
                        canvas.scale(zoom, zoom, dropletRect.centerX(), dropletRect.centerY());
                        canvas.drawBitmap(activeBlueLayerBmp, 0f, 0f, null);
                        canvas.restore();
                    }

                    fallbackRimPaint.setStrokeWidth(dpf(1.2f + inflateProgress * 0.8f));
                    fallbackRimPaint.setShader(new LinearGradient(
                        dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom,
                        dark ? 0x99FFFFFF : 0xFFFFFFFF,
                        dark ? 0x4488C4FF : 0x8899C2FF,
                        Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, fallbackRimPaint);
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
            float baseW = Math.max(currentW - dpf(8f), dpf(54f));
            float baseH = Math.max(capsuleRect.height() - dpf(8f), dpf(42f));

            float absStretch = Math.abs(stretchX);
            float scaleX = 1f + inflateProgress * 0.16f + absStretch * 0.62f;
            float scaleY = 1f + inflateProgress * 0.25f - absStretch * 0.26f;

            float w = baseW * scaleX;
            float h = baseH * scaleY;
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
                    // Ignore touches outside the floating capsule hit zone
                    if (x < capsuleRect.left - dpf(6f) || x > capsuleRect.right + dpf(6f)
                        || y < capsuleRect.top - dpf(6f) || y > capsuleRect.bottom + dpf(6f))
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
                    targetInflate = 0.85f;
                    updateTargetForIndex(hit);
                    startAnimationLoop();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!pointerDown)
                        return false;
                    if (!isDragging && Math.abs(x - downX) > touchSlop * 0.45f)
                        isDragging = true;

                    long now = SystemClock.uptimeMillis();
                    float dt = Math.max(1f, now - lastTouchMs);
                    float instVel = (x - lastTouchX) / dt * 16f;
                    lastTouchX = x;
                    lastTouchMs = now;

                    float tabW = capsuleRect.width() / count;
                    float minCx = capsuleRect.left + tabW * 0.46f;
                    float maxCx = capsuleRect.right - tabW * 0.46f;
                    float clampedX = clamp(x, minCx, maxCx);

                    targetX = clampedX;
                    targetW = tabW;
                    targetInflate = 1.0f;
                    stretchX = clamp(instVel * 0.019f, -0.24f, 0.24f);

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

                float stiffness = isDragging ? 460f : 280f;
                float damping = isDragging ? 29f : 22f;
                float force = (targetX - currentX) * stiffness;
                velocityX += (force - velocityX * damping) * dt;
                currentX += velocityX * dt;

                currentW += (targetW - currentW) * Math.min(1f, dt * 16f);
                inflateProgress += (targetInflate - inflateProgress) * Math.min(1f, dt * 18f);

                float velStretchTarget = clamp(velocityX * 0.00018f, -0.24f, 0.24f);
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
