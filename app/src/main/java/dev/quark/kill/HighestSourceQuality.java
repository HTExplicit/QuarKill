package dev.quark.kill;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class HighestSourceQuality {
    private static final String PLAYER = "com.ucpro.feature.video.player.";
    private static final String ACTION_TAG = "dev.quark.kill.highestSourceQuality";
    private static final int WEB_SCENE = 100000;

    private record Source(Object resolution, String tier, int width, int height) {
        long pixelCount() { return (long) width * height; }
    }

    private HighestSourceQuality() {}

    static void install(ClassLoader loader, BooleanSupplier enabled) throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass(PLAYER
                + "manipulator.fullscreenmanipulator.extendlayer.IntelligentCinemaPanel");
        Class<?> state = loader.loadClass(PLAYER + "PlayerCallBackData");
        Class<?> resolution = loader.loadClass(PLAYER + "resolution.Resolution");
        Method bind = panel.getMethod("g", state);
        Method switchQuality = panel.getDeclaredMethod("d", panel, resolution, boolean.class);
        switchQuality.setAccessible(true);
        Field boundState = panel.getDeclaredField("D");
        boundState.setAccessible(true);

        Method scene = state.getMethod("t0");
        Method availableResolutions = state.getMethod("j1");
        Method currentResolution = state.getMethod("d0");
        Method tier = resolution.getMethod("m");
        Method access = resolution.getMethod("o");
        Method isPlayable = resolution.getMethod("v");
        Method streamUrl = resolution.getMethod("t");
        Method width = resolution.getMethod("u");
        Method height = resolution.getMethod("k");

        XposedBridge.hookMethod(bind, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                if (param.hasThrowable()) return;
                ViewGroup root = (ViewGroup) param.thisObject;
                View existing = root.findViewWithTag(ACTION_TAG);
                if (existing != null && existing.getParent() instanceof ViewGroup) {
                    ((ViewGroup) existing.getParent()).removeView(existing);
                }
                Object data = param.args[0];
                if (data == null || !enabled.getAsBoolean() || (int) scene.invoke(data) != WEB_SCENE) return;

                Source best = highest(data);
                Object current = currentResolution.invoke(data);
                if (best == null || current == null) return;

                Context context = root.getContext();
                int titleId = context.getResources().getIdentifier("tv_video_resolution_panel_title", "id", context.getPackageName());
                if (titleId == 0) return;
                View title = root.findViewById(titleId);
                if (!(title instanceof TextView) || !(title.getParent() instanceof LinearLayout)) return;
                LinearLayout group = (LinearLayout) title.getParent();
                if (group.getOrientation() != LinearLayout.VERTICAL) return;

                boolean alreadySelected = best.tier().equals(tier.invoke(current));
                Button action = new Button(context);
                action.setTag(ACTION_TAG);
                action.setAllCaps(false);
                action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                action.setTextColor(((TextView) title).getTextColors());
                action.setTextSize(14);
                action.setMinHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                        context.getResources().getDisplayMetrics()));
                TypedValue background = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                        && background.resourceId != 0) action.setBackgroundResource(background.resourceId);
                action.setText((alreadySelected ? "当前为片源最高  " : "切换到片源最高  ")
                        + best.width() + "×" + best.height());
                action.setEnabled(!alreadySelected);
                action.setOnClickListener(view -> {
                    try {
                        if (enabled.getAsBoolean() && boundState.get(root) == data && root.isShown()) {
                            Source latest = highest(data);
                            Object playing = currentResolution.invoke(data);
                            if (latest != null && playing != null && !latest.tier().equals(tier.invoke(playing))) {
                                switchQuality.invoke(null, root, latest.resolution(), true);
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        QuarkLiteHook.logDiagnostic("web video highest source selection failed: " + e.getClass().getSimpleName());
                    }
                });
                group.addView(action, group.indexOfChild(title) + 1,
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            private Source highest(Object data) throws ReflectiveOperationException {
                Object available = availableResolutions.invoke(data);
                if (!(available instanceof List)) return null;
                Source best = null;
                for (Object candidate : (List<?>) available) {
                    if (!resolution.isInstance(candidate) || !"normal".equals(access.invoke(candidate))
                            || !Boolean.TRUE.equals(isPlayable.invoke(candidate))) continue;
                    String label = (String) tier.invoke(candidate);
                    if (label == null || label.isEmpty() || "ai".equals(label) || "raw".equals(label)
                            || "unknown".equals(label)) continue;
                    String url = (String) streamUrl.invoke(candidate);
                    if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
                    int w = (int) width.invoke(candidate);
                    int h = (int) height.invoke(candidate);
                    if (w <= 0 || h <= 0) continue;

                    Source source = new Source(candidate, label, w, h);
                    if (best == null || source.pixelCount() > best.pixelCount()) best = source;
                }
                return best;
            }
        });
    }
}
