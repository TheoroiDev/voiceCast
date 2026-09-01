package com.theo.voicecast.compat;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Loader-agnostic detection of other installed mods. The loader subclasses
 * (Fabric / Forge) plug in the actual lookup at construction time.
 */
public final class ModDetection {
    private static Predicate<String> CHECKER = id -> false;

    private ModDetection() {}

    public static void init(Predicate<String> checker) {
        CHECKER = checker == null ? id -> false : checker;
    }

    public static boolean isLoaded(String modId) {
        try {
            return CHECKER.test(modId.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasSimpleVoiceChat() { return isLoaded("voicechat"); }
    public static boolean hasShriek() { return isLoaded("shriek"); }
    public static boolean hasVoskLib() { return isLoaded("vosklib"); }
    public static boolean hasPlasmoVoice() { return isLoaded("plasmo_voice"); }
}
