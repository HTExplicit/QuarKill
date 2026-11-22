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
    private static final String INTELLIGENT_CINEMA_TIER = "ai";
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
        Method supportsIntelligentCinema = state.getMethod("e3");
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
                if (current == null) return;

                Context context = root.getContext();
                int titleId = context.getResources().getIdentifier("tv_video_resolution_panel_title", "id", context.getPackageName());
                if (titleId == 0) return;
                View title = root.findViewById(titleId);
                if (!(title instanceof TextView) || !(title.getParent() instanceof LinearLayout)) return;
                LinearLayout group = (LinearLayout) title.getParent();
                if (group.getOrientation() != LinearLayout.VERTICAL) return;

                LinearLayout actions = new LinearLayout(context);
                actions.setTag(ACTION_TAG);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                Object currentTier = tier.invoke(current);
                boolean cinemaSelected = INTELLIGENT_CINEMA_TIER.equals(currentTier);
                boolean cinemaAvailable = intelligentCinema(data) != null;
                String cinemaLabel = cinemaSelected ? "至臻影音已启用"
                        : cinemaAvailable ? "至臻影音" : "至臻影音不可用";
                addButton(actions, (TextView) title, cinemaLabel, cinemaAvailable && !cinemaSelected,
                        view -> select(root, data, true));
                if (best != null) {
                    boolean alreadySelected = best.tier().equals(currentTier);
                    String qualityLabel = (alreadySelected ? "当前为片源最高\n" : "切换到片源最高\n")
                            + best.width() + "×" + best.height();
                    addButton(actions, (TextView) title, qualityLabel, !alreadySelected,
                            view -> select(root, data, false));
                }
                group.addView(actions, group.indexOfChild(title) + 1,
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            private void select(ViewGroup panelView, Object data, boolean cinema) {
                try {
                    if (!enabled.getAsBoolean() || boundState.get(panelView) != data || !panelView.isShown()) return;
                    Object playing = currentResolution.invoke(data);
                    if (playing == null) return;
                    Source best = cinema ? null : highest(data);
                    Object target = cinema ? intelligentCinema(data) : best == null ? null : best.resolution();
                    if (target != null && !tier.invoke(target).equals(tier.invoke(playing))) {
                        // Both buttons use the native selection callback; the cinema hook applies local filters.
                        switchQuality.invoke(null, panelView, target, true);
                        QuarkLiteHook.logDiagnostic(cinema ? "web video requested native intelligent cinema"
                                : "web video requested highest source quality");
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    QuarkLiteHook.logDiagnostic("web video quality selection failed: " + e.getClass().getSimpleName());
                }
            }

            private Object intelligentCinema(Object data) throws ReflectiveOperationException {
                if (!Boolean.TRUE.equals(supportsIntelligentCinema.invoke(data))) return null;
                Object available = availableResolutions.invoke(data);
                if (!(available instanceof List)) return null;
                for (Object candidate : (List<?>) available) {
                    if (resolution.isInstance(candidate) && INTELLIGENT_CINEMA_TIER.equals(tier.invoke(candidate))) {
                        return candidate;
                    }
                }
                return null;
            }

            private Source highest(Object data) throws ReflectiveOperationException {
                Object available = availableResolutions.invoke(data);
                if (!(available instanceof List)) return null;
                Source best = null;
                for (Object candidate : (List<?>) available) {
                    if (!resolution.isInstance(candidate) || !"normal".equals(access.invoke(candidate))
                            || !Boolean.TRUE.equals(isPlayable.invoke(candidate))) continue;
                    String label = (String) tier.invoke(candidate);
                    if (label == null || label.isEmpty() || INTELLIGENT_CINEMA_TIER.equals(label)
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

    private static void addButton(LinearLayout row, TextView title, String label, boolean enabled,
            View.OnClickListener clickListener) {
        Context context = row.getContext();
        Button action = new Button(context);
        action.setAllCaps(false);
        action.setGravity(Gravity.CENTER);
        action.setTextColor(title.getTextColors());
        action.setTextSize(14);
        action.setMinHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                context.getResources().getDisplayMetrics()));
        TypedValue background = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                && background.resourceId != 0) action.setBackgroundResource(background.resourceId);
        action.setText(label);
        action.setEnabled(enabled);
        action.setOnClickListener(clickListener);
        row.addView(action, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
}
