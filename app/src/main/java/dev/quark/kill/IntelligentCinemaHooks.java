package dev.quark.kill;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Applies Quark's existing on-device enhancement filters to the active web player. */
final class IntelligentCinemaHooks {
    private static final String VIDEO = "com.ucpro.feature.video.";
    private static final int WEB_SCENE = 100000;

    private static final class Activation {
        final Object core;
        int added;
        int enabled;
        int updated;
        boolean rejected;

        Activation(Object core) { this.core = core; }

        boolean accepted() { return added > 0 && enabled == added && !rejected; }
    }

    private IntelligentCinemaHooks() {}

    static void install(ClassLoader loader, BooleanSupplier enabled) throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass(VIDEO
                + "player.manipulator.fullscreenmanipulator.extendlayer.IntelligentCinemaPanel");
        Class<?> player = loader.loadClass(VIDEO + "player.MediaPlayer");
        Class<?> coreType = loader.loadClass(VIDEO + "j2");
        Class<?> manipulator = loader.loadClass(VIDEO + "player.Manipulator");
        Class<?> state = loader.loadClass(VIDEO + "player.PlayerCallBackData");
        Class<?> resolution = loader.loadClass(VIDEO + "player.resolution.Resolution");
        Class<?> effects = loader.loadClass("nn3.l");
        Field activePlayer = panel.getDeclaredField("C");
        activePlayer.setAccessible(true);
        Field boundState = panel.getDeclaredField("D");
        boundState.setAccessible(true);
        // The panel observes Manipulator, whose state provider is the actual MediaPlayer.
        Field stateProvider = loader.loadClass(VIDEO + "player.o").getDeclaredField("B");
        stateProvider.setAccessible(true);
        Method select = panel.getDeclaredMethod("d", panel, resolution, boolean.class);
        Method getState = player.getMethod("p0");
        Method getCore = player.getMethod("U");
        Method setResolution = player.getMethod("y5", resolution);
        Method currentResolution = state.getMethod("d0");
        Method getScene = state.getMethod("t0");
        Method prepared = state.getMethod("S2");
        Method tier = resolution.getMethod("m");
        Method getEffects = effects.getMethod("v");
        Method apply = effects.getMethod("n", coreType);
        Method disable = effects.getMethod("k", coreType);
        Method requestFrameRate = effects.getMethod("p", coreType);
        Method resetFrameRate = effects.getMethod("m", coreType);
        Method closePanel = loader.loadClass("zn3.b").getMethod("handleMessage", int.class,
                loader.loadClass("zn3.d"), loader.loadClass("zn3.d"));
        Method setOption = loader.loadClass("km3.a").getMethod("i", coreType, String.class, String.class);
        ThreadLocal<Activation> activating = new ThreadLocal<>();

        XC_MethodHook.Unhook optionHook = XposedBridge.hookMethod(setOption, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activation activation = activating.get();
                if (activation == null || param.args[0] != activation.core) return;
                String command = (String) param.args[1];
                boolean add = "rw.instance.add_filter".equals(command);
                boolean enable = "rw.instance.enable_filter".equals(command);
                boolean update = "rw.instance.update_filter_parameters".equals(command);
                boolean frameRate = "rw.instance.set_target_fps".equals(command);
                if (!add && !enable && !update && !frameRate) return;
                boolean accepted = !param.hasThrowable() && Boolean.TRUE.equals(param.getResult());
                if (!accepted && !frameRate) activation.rejected = true;
                else if (add) activation.added++;
                else if (enable) activation.enabled++;
                else if (update) activation.updated++;
                if (!update || !accepted) {
                    QuarkLiteHook.logDiagnostic("intelligent cinema " + command + " accepted=" + accepted);
                }
            }
        });

        try {
            XposedBridge.hookMethod(select, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                    if (!enabled.getAsBoolean() || !Boolean.TRUE.equals(param.args[2])
                            || param.args[1] == null || !"ai".equals(tier.invoke(param.args[1]))) return;
                    Object data = boundState.get(param.args[0]);
                    if (data == null || (int) getScene.invoke(data) != WEB_SCENE) return;
                    // Complete this local selection here so the native purchase callback is never the result.
                    param.setResult(null);
                    Context context = ((View) param.args[0]).getContext();
                    Object engine = null;
                    Object core = null;
                    Object instance = null;
                    Object previousResolution = null;
                    try {
                        previousResolution = currentResolution.invoke(data);
                        Object observer = activePlayer.get(param.args[0]);
                        instance = manipulator.isInstance(observer) ? stateProvider.get(observer) : observer;
                        if (!player.isInstance(instance) || getState.invoke(instance) != data) {
                            QuarkLiteHook.logDiagnostic("intelligent cinema active player unavailable");
                            Toast.makeText(context, "未找到当前视频播放器", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        core = getCore.invoke(instance);
                        if (core == null || !Boolean.TRUE.equals(prepared.invoke(data))) {
                            QuarkLiteHook.logDiagnostic("intelligent cinema player is not prepared");
                            Toast.makeText(context, "请在视频加载完成后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        engine = getEffects.invoke(null);
                        Activation activation = new Activation(core);
                        Activation previous = activating.get();
                        try {
                            activating.set(activation);
                            apply.invoke(engine, core);
                            // Quark decides whether this device and engine support its 60 fps path.
                            if (activation.accepted()) requestFrameRate.invoke(engine, core);
                        } finally {
                            if (previous == null) activating.remove();
                            else activating.set(previous);
                        }

                        if (activation.accepted()) {
                            setResolution.invoke(instance, param.args[1]);
                            closePanel.invoke(observer, 10028, null, null);
                            QuarkLiteHook.logDiagnostic("intelligent cinema active: added=" + activation.added
                                    + " enabled=" + activation.enabled + " updated=" + activation.updated);
                            Toast.makeText(context, "至臻影音已启用", Toast.LENGTH_SHORT).show();
                        } else {
                            disable.invoke(engine, core);
                            resetFrameRate.invoke(engine, core);
                            QuarkLiteHook.logDiagnostic("intelligent cinema unavailable: added=" + activation.added
                                    + " enabled=" + activation.enabled + " rejected=" + activation.rejected);
                            Toast.makeText(context, "当前内核未能启用至臻影音", Toast.LENGTH_SHORT).show();
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        if (engine != null && core != null) {
                            try { disable.invoke(engine, core); }
                            catch (ReflectiveOperationException | RuntimeException ignored) { }
                            try { resetFrameRate.invoke(engine, core); }
                            catch (ReflectiveOperationException | RuntimeException ignored) { }
                        }
                        if (player.isInstance(instance) && previousResolution != null) {
                            try { setResolution.invoke(instance, previousResolution); }
                            catch (ReflectiveOperationException | RuntimeException ignored) { }
                        }
                        QuarkLiteHook.logDiagnostic("intelligent cinema selection failed: " + e.getClass().getSimpleName());
                        Toast.makeText(context, "至臻影音启用失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (RuntimeException | LinkageError e) {
            optionHook.unhook();
            throw e;
        }
    }
}
