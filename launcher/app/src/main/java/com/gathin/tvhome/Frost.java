package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Frosted glass without a live blur (Android 9 has none, and a 1 GB TV could not afford one): when the wallpaper is
 * decoded, a 128x72 copy is box-blurred once, and every glass surface paints the part of that copy that lies behind
 * it, magnified with filtering, under a light tint, a sheen and a hairline edge. Drawing it is one bitmap shader.
 */
final class Frost {
    private Frost() {}

    private static final int W = 128, H = 72;
    private static Bitmap blurred;
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Makes the blurred copy from a software bitmap of the wallpaper (any thread), then redraws the glass. */
    static void setFrom(Bitmap wallpaper) {
        Bitmap small = Bitmap.createScaledBitmap(wallpaper, W, H, true);
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        if (small != wallpaper) small.recycle();
        for (int pass = 0; pass < 3; pass++) px = boxBlur(px, 3);
        Bitmap out = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
        main.post(() -> {
            blurred = out;
            for (View v : hosts.toArray(new View[0])) if (v != null) v.invalidate();
        });
    }

    /** No wallpaper any more: the glass falls back to a plain tint. */
    static void clear() {
        main.post(() -> {
            blurred = null;
            for (View v : hosts.toArray(new View[0])) if (v != null) v.invalidate();
        });
    }

    /** One horizontal and one vertical box pass of the given radius; the edges repeat their last pixel. */
    private static int[] boxBlur(int[] in, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        pass(in, tmp, W, H, r, true);
        pass(tmp, out, W, H, r, false);
        return out;
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[index(line, clamp(k, len), w, horizontal)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int add = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /**
     * Where the view's top-left sits on screen once its animations have settled: its window's position plus the layout
     * offsets up the tree, without translations. A panel sliding in then carries its frost with it and lines up when
     * it lands, and no redraw is needed when the slide ends (property animations do not redraw the content).
     */
    static void settledOnScreen(View view, float[] out) {
        View root = view.getRootView();
        int[] window = new int[2];
        root.getLocationOnScreen(window);
        // The root's own translation is part of getLocationOnScreen; take it back out.
        float x = window[0] - root.getTranslationX(), y = window[1] - root.getTranslationY();
        View v = view;
        while (v != null && v != root) {
            x += v.getLeft() - v.getScrollX();
            y += v.getTop() - v.getScrollY();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        out[0] = x;
        out[1] = y;
    }

    /** Glass of this radius over rect, in the canvas of a view whose settled top-left is at (screenX, screenY). */
    static final class Painter {
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private Bitmap shaderFor;
        private float sheenTop = Float.NaN, sheenBottom = Float.NaN;
        private final int tintColor;

        /** @param tintColor the darkening over the frost; white text stays readable on a light sky with ~0x40. */
        Painter(float density, int tintColor) {
            this.tintColor = tintColor;
            tint.setColor(tintColor);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density));
            edge.setColor(0x40FFFFFF);
        }

        void draw(Canvas c, RectF rect, float radius, float screenX, float screenY, int screenW, int screenH) {
            Bitmap b = blurred;
            if (b != null) {
                if (shaderFor != b) {
                    frost.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = b;
                }
                // Blurred-copy pixels to screen pixels, then screen to this canvas.
                matrix.setScale(screenW / (float) W, screenH / (float) H);
                matrix.postTranslate(-screenX, -screenY);
                frost.getShader().setLocalMatrix(matrix);
                c.drawRoundRect(rect, radius, radius, frost);
                tint.setColor(tintColor);
            } else {
                tint.setColor(0x66000000 | (tintColor & 0x00FFFFFF));   // no picture: a plainer, darker glass
            }
            c.drawRoundRect(rect, radius, radius, tint);
            if (rect.top != sheenTop || rect.bottom != sheenBottom) {
                sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x05FFFFFF,
                        Shader.TileMode.CLAMP));
                sheenTop = rect.top;
                sheenBottom = rect.bottom;
            }
            c.drawRoundRect(rect, radius, radius, sheen);
            float inset = edge.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            c.drawRoundRect(rect, radius, radius, edge);
            rect.inset(-inset, -inset);
        }
    }

    /** A view background made of glass. Finds its view through the callback chain (a StateListDrawable in between). */
    static final class FrostDrawable extends Drawable {
        private final Painter painter;
        private final float radius;
        private final RectF rect = new RectF();
        private final float[] at = new float[2];

        FrostDrawable(float radius, float density, int tintColor) {
            this.radius = radius;
            this.painter = new Painter(density, tintColor);
        }

        private View host() {
            Callback cb = getCallback();
            while (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
            return cb instanceof View ? (View) cb : null;
        }

        @Override
        public void draw(Canvas canvas) {
            View view = host();
            Rect b = getBounds();
            rect.set(b);
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            if (view == null) {
                painter.draw(canvas, rect, r, 0, 0, 1, 1);
                return;
            }
            hosts.add(view);
            settledOnScreen(view, at);
            android.util.DisplayMetrics m = view.getResources().getDisplayMetrics();
            painter.draw(canvas, rect, r, at[0], at[1], m.widthPixels, m.heightPixels);
        }

        @Override
        public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), Math.min(radius, Math.min(getBounds().width(), getBounds().height()) / 2f));
            outline.setAlpha(0f);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Registers a view that draws glass itself (the grid's dock band) for a redraw when the picture changes. */
    static void watch(View view) {
        hosts.add(view);
    }
}
