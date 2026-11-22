package dev.quark.kill;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class WebVideoHooks {
    private static final String VERSION_NAME = "10.21.6.1191";
    private static final long VERSION_CODE = 1191;
    private static final String VIDEO = "com.ucpro.feature.video.";
    private static final String PREVIOUS_SCOPE = "dev.quark.kill.webSidebarScope";
    private static final int BROWSER_VIDEO_SCENE = 100000;

    private WebVideoHooks() {}

    static void installOnAttach(String targetPackage, ClassLoader loader, BooleanSupplier enabled) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context == null || !targetPackage.equals(context.getPackageName())) return;
                            try {
                                PackageInfo info = context.getPackageManager().getPackageInfo(targetPackage, 0);
                                long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                                if (version != VERSION_CODE || !VERSION_NAME.equals(info.versionName)) {
                                    QuarkLiteHook.logDiagnostic("web video skipped for Quark build " + version);
                                    return;
                                }
                            } catch (Exception | LinkageError e) {
                                QuarkLiteHook.logDiagnostic("web video version unavailable: " + e.getClass().getSimpleName());
                                return;
                            }
                            try {
                                installSourceQuality(loader, enabled);
                            } catch (Exception | LinkageError e) {
                                QuarkLiteHook.logDiagnostic("web video quality binding unavailable: " + e.getClass().getSimpleName());
                            }
                            try {
                                HighestSourceQuality.install(loader, enabled);
                            } catch (Exception | LinkageError e) {
                                QuarkLiteHook.logDiagnostic("web video highest source binding unavailable: " + e.getClass().getSimpleName());
                            }
                            try {
                                IntelligentCinemaHooks.install(loader, enabled);
                            } catch (Exception | LinkageError e) {
                                QuarkLiteHook.logDiagnostic("intelligent cinema binding unavailable: " + e.getClass().getSimpleName());
                            }
                            try {
                                installProjectionVisibility(loader, enabled);
                            } catch (Exception | LinkageError e) {
                                QuarkLiteHook.logDiagnostic("web video projection binding unavailable: " + e.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (RuntimeException | LinkageError e) {
            QuarkLiteHook.logDiagnostic("web video attach hook failed: " + e.getClass().getSimpleName());
        }
    }

    private static void installSourceQuality(ClassLoader loader, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> state = loader.loadClass(VIDEO + "player.PlayerCallBackData");
        Class<?> resolution = loader.loadClass(VIDEO + "player.resolution.Resolution");
        Class<?> helper = loader.loadClass("vo3.n");
        Method showQuality = helper.getMethod("o", state);
        Method hasQualitySelection = helper.getMethod("j", state);
        Method scene = state.getMethod("t0");
        Method current = state.getMethod("d0");
        Method resolutions = state.getMethod("j1");
        Method quality = resolution.getMethod("m");
        Method access = resolution.getMethod("o");
        Method url = resolution.getMethod("t");
        if (!Modifier.isStatic(showQuality.getModifiers()) || showQuality.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("Unexpected quality visibility signature");
        }

        XposedBridge.hookMethod(showQuality, new XC_MethodHook() {
            private boolean reported;

            @Override protected void afterHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                if (param.hasThrowable() || !Boolean.FALSE.equals(param.getResult()) || !enabled.getAsBoolean()) return;
                Object data = param.args[0];
                if (data == null || (int) scene.invoke(data) != BROWSER_VIDEO_SCENE
                        || current.invoke(data) == null || !(boolean) hasQualitySelection.invoke(null, data)) return;
                Object available = resolutions.invoke(data);
                if (!(available instanceof List)) return;

                // Only expose the existing picker for distinct source renditions with normal access.
                // The native picker and its click handlers still own all playback and access decisions.
                Set<String> tiers = new HashSet<>();
                Set<String> sources = new HashSet<>();
                for (Object option : (List<?>) available) {
                    if (!resolution.isInstance(option) || !"normal".equals(access.invoke(option))) continue;
                    String tier = (String) quality.invoke(option);
                    String stream = (String) url.invoke(option);
                    if (tier == null || tier.isEmpty() || "ai".equals(tier) || "unknown".equals(tier)
                            || stream == null || !(stream.startsWith("https://") || stream.startsWith("http://"))) continue;
                    tiers.add(tier);
                    sources.add(stream);
                    if (tiers.size() < 2 || sources.size() < 2) continue;
                    param.setResult(true);
                    if (!reported) {
                        reported = true;
                        QuarkLiteHook.logDiagnostic("web video restored native source quality picker");
                    }
                    return;
                }
            }
        });
    }

    private static void installProjectionVisibility(ClassLoader loader, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> presenter = loader.loadClass(VIDEO
                + "player.manipulator.fullscreenmanipulator.sidebar.FullSideBarPresenter");
        Class<?> webPlayer = loader.loadClass(VIDEO + "web.c");
        Field player = presenter.getSuperclass().getDeclaredField("B");
        Method bind = presenter.getDeclaredMethod("f0");
        Method hideProjection = loader.loadClass(VIDEO + "player.FunctionSwitch").getDeclaredMethod("r");
        if (!player.getType().isAssignableFrom(webPlayer) || bind.getReturnType() != void.class
                || !Modifier.isStatic(hideProjection.getModifiers()) || hideProjection.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("Unexpected web player binding signatures");
        }
        player.setAccessible(true);

        Class<?> buttonType = loader.loadClass(VIDEO + "player.view.q");
        Constructor<?> button = buttonType.getConstructor(int.class);
        Method buttonId = buttonType.getMethod("e");
        Method buttonIcon = buttonType.getMethod("r", String.class);
        Method buttonLabel = buttonType.getMethod("t", String.class);
        Method buttonDescription = buttonType.getMethod("o", int.class);
        Class<?> idType = loader.loadClass(VIDEO + "player.ViewId");
        Method getId = idType.getMethod("getId");
        int projectionId = (int) getId.invoke(idType.getField("FULL_PROJECTION_BTN").get(null));
        int downloadId = (int) getId.invoke(idType.getField("FULL_DOWNLOAD_BTN").get(null));
        Class<?> sidebar = loader.loadClass(VIDEO + "player.view.SideBar");
        Method setButtons = sidebar.getMethod("setDatas", List.class);
        Method getState = player.getType().getMethod("p0");
        Class<?> state = getState.getReturnType();
        Method isPrepared = state.getMethod("S2");
        Method hasNoDuration = state.getMethod("J2");
        Method source = state.getMethod("L1");
        Method restrictedSource = loader.loadClass("cq3.w").getMethod("J0", String.class);

        ThreadLocal<Object> webSidebarPlayer = new ThreadLocal<>();
        XC_MethodHook.Unhook visibilityHook = XposedBridge.hookMethod(hideProjection, new XC_MethodHook() {
            private boolean reportedRestored;
            private boolean reportedAlreadyEnabled;

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (webSidebarPlayer.get() == null || param.hasThrowable()) return;
                // The native binder still decides when the stream is ready and when to offer casting.
                if (Boolean.TRUE.equals(param.getResult())) {
                    param.setResult(false);
                    if (!reportedRestored) {
                        reportedRestored = true;
                        QuarkLiteHook.logDiagnostic("web video restored native projection entry");
                    }
                } else if (!reportedAlreadyEnabled) {
                    reportedAlreadyEnabled = true;
                    QuarkLiteHook.logDiagnostic("web video found native projection entry already enabled");
                }
            }
        });
        XC_MethodHook.Unhook buttonsHook = null;
        try {
            buttonsHook = XposedBridge.hookMethod(setButtons, new XC_MethodHook() {
                private boolean reported;

                @Override protected void beforeHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                    Object instance = webSidebarPlayer.get();
                    if (instance == null || !(param.args[0] instanceof List)) return;
                    List<?> items = (List<?>) param.args[0];
                    boolean unlockedWebControls = false;
                    for (Object item : items) {
                        if (!buttonType.isInstance(item)) return;
                        int id = (int) buttonId.invoke(item);
                        if (id == projectionId) return;
                        if (id == downloadId) unlockedWebControls = true;
                    }
                    if (!unlockedWebControls) return;
                    Object data = getState.invoke(instance);
                    // For on-demand streams use the same source and duration checks as the native More panel.
                    if (data == null || !(boolean) isPrepared.invoke(data) || (boolean) hasNoDuration.invoke(data)
                            || (boolean) restrictedSource.invoke(null, source.invoke(data))) return;
                    Context context = ((View) param.thisObject).getContext();
                    int label = context.getResources().getIdentifier("video_play_proj", "string", context.getPackageName());
                    int description = context.getResources().getIdentifier("access_projection", "string", context.getPackageName());
                    if (label == 0 || description == 0) return;

                    Object entry = button.newInstance(projectionId);
                    buttonIcon.invoke(entry, "video_proj.svg");
                    buttonLabel.invoke(entry, context.getString(label));
                    buttonDescription.invoke(entry, description);
                    // The presenter keeps and consults this same native list in its click handler.
                    @SuppressWarnings("unchecked") List<Object> nativeItems = (List<Object>) items;
                    nativeItems.add(entry);
                    if (!reported) {
                        reported = true;
                        QuarkLiteHook.logDiagnostic("web video added native on-demand projection entry to sidebar");
                    }
                }
            });
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws IllegalAccessException {
                    param.setObjectExtra(PREVIOUS_SCOPE, webSidebarPlayer.get());
                    webSidebarPlayer.remove();
                    if (!enabled.getAsBoolean()) return;
                    Object instance = player.get(param.thisObject);
                    if (webPlayer.isInstance(instance)) webSidebarPlayer.set(instance);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object previous = param.getObjectExtra(PREVIOUS_SCOPE);
                    if (previous == null) webSidebarPlayer.remove();
                    else webSidebarPlayer.set(previous);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            if (buttonsHook != null) buttonsHook.unhook();
            visibilityHook.unhook();
            throw e;
        }
    }
}
