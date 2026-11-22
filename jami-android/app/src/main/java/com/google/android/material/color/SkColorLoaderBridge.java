package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

/**
 * 白い熊 fork: the door to Material's colour-resources loader.
 *
 * Material builds, for its dynamic colours, a resource table in memory that replaces the values of
 * chosen colour resources, and wraps it in a {@link ResourcesLoader}. That is what the fork's colour
 * roles need (see {@code cx.ring.sk.SkPalette}), but the class that does it is package-private.
 * This class lives in the same package for one reason: to call it.
 */
@Keep
public final class SkColorLoaderBridge {
    private SkColorLoaderBridge() {}

    /** A loader that gives every colour resource id in the map its new ARGB value, or null. */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.R)
    public static ResourcesLoader create(Context context, Map<Integer, Integer> colors) {
        return ColorResourcesLoaderCreator.create(context, colors);
    }
}
