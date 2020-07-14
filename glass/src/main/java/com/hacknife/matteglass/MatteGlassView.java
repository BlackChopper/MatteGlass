package com.hacknife.matteglass;


import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;


public class MatteGlassView extends View {


    private boolean dirty;

    private int overlayColor;
    private Paint overlayPaint = new Paint();

    private Rect rectSrc = new Rect();
    private Rect rectDst = new Rect();
    private Path bitmapPath = new Path();
    private final int cornerLeftTopRadius;
    private final int cornerLeftBottomRadius;
    private final int cornerRightTopRadius;
    private final int cornerRightBottomRadius;

    private Bitmap bitmapToBlur;
    private Bitmap blurredBitmap;
    private float blurRadius;
    private float downSample;

    private Canvas blurringCanvas;
    private RenderScript renderScript;
    private ScriptIntrinsicBlur blurScript;
    private Allocation blurInput;
    private Allocation blurOutput;
    private boolean isRendering;

    private View decorView;
    private boolean differentRoot;
    private static int RENDERING_COUNT;

    public MatteGlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MatteGlassView);
        blurRadius = a.getDimension(R.styleable.MatteGlassView_matteRadius, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, context.getResources().getDisplayMetrics()));
        downSample = a.getFloat(R.styleable.MatteGlassView_matteDownSample, 4);
        overlayColor = a.getColor(R.styleable.MatteGlassView_matteLayColor, 0xAAFFFFFF);
        int cornerRadius = a.getDimensionPixelSize(R.styleable.MatteGlassView_matteCornerRadius, 0);
        cornerLeftTopRadius = a.getDimensionPixelSize(R.styleable.MatteGlassView_matteCornerLeftTopRadius, cornerRadius);
        cornerLeftBottomRadius = a.getDimensionPixelSize(R.styleable.MatteGlassView_matteCornerLeftBottomRadius, cornerRadius);
        cornerRightTopRadius = a.getDimensionPixelSize(R.styleable.MatteGlassView_matteCornerRightTopRadius, cornerRadius);
        cornerRightBottomRadius = a.getDimensionPixelSize(R.styleable.MatteGlassView_matteCornerRightBottomRadius, cornerRadius);
        init();
        a.recycle();
    }

    private void init() {
        overlayPaint.setAntiAlias(true);
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(overlayColor);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rectDst.right = w;
        rectDst.bottom = h;
        initPath();
    }

    private void initPath() {
        bitmapPath.reset();
        bitmapPath.moveTo(rectDst.left + cornerLeftTopRadius, rectDst.top);
        bitmapPath.lineTo(rectDst.right - cornerRightTopRadius, rectDst.top);
        bitmapPath.arcTo(
                new RectF(
                        rectDst.right - cornerRightTopRadius,
                        rectDst.top,
                        rectDst.right,
                        rectDst.top + cornerRightTopRadius
                ), 270f, 90f
        );

        bitmapPath.lineTo(rectDst.right, rectDst.bottom - cornerRightBottomRadius);

        bitmapPath.arcTo(
                new RectF(
                        rectDst.right - cornerRightBottomRadius,
                        rectDst.bottom - cornerRightBottomRadius,
                        rectDst.right,
                        rectDst.bottom
                ), 0f, 90f
        );

        bitmapPath.lineTo(rectDst.left + cornerLeftBottomRadius, rectDst.bottom);


        bitmapPath.arcTo(
                new RectF(
                        rectDst.left,
                        rectDst.bottom - cornerLeftBottomRadius,
                        rectDst.left + cornerLeftBottomRadius,
                        rectDst.bottom
                ), 90f, 90f
        );

        bitmapPath.lineTo(rectDst.left, rectDst.top + cornerLeftTopRadius);

        bitmapPath.arcTo(
                new RectF(
                        rectDst.left,
                        rectDst.top,
                        rectDst.left + cornerLeftTopRadius,
                        rectDst.top + cornerLeftTopRadius
                ), 180f, 90f
        );
        bitmapPath.close();
    }

    public void setBlurRadius(float radius) {
        if (blurRadius != radius) {
            blurRadius = radius;
            dirty = true;
            invalidate();
        }
    }

    public void setDownSample(float factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("downSample factor must be greater than 0.");
        }

        if (downSample != factor) {
            downSample = factor;
            dirty = true; // may also change blur radius
            releaseBitmap();
            invalidate();
        }
    }

    public void setOverlayColor(int color) {
        if (overlayColor != color) {
            overlayColor = color;
            overlayPaint.setColor(overlayColor);
            invalidate();
        }
    }

    private void releaseBitmap() {
        if (blurInput != null) {
            blurInput.destroy();
            blurInput = null;
        }
        if (blurOutput != null) {
            blurOutput.destroy();
            blurOutput = null;
        }
        if (bitmapToBlur != null) {
            bitmapToBlur.recycle();
            bitmapToBlur = null;
        }
        if (blurredBitmap != null) {
            blurredBitmap.recycle();
            blurredBitmap = null;
        }
    }

    private void releaseScript() {
        if (renderScript != null) {
            renderScript.destroy();
            renderScript = null;
        }
        if (blurScript != null) {
            blurScript.destroy();
            blurScript = null;
        }
    }

    protected void release() {
        releaseBitmap();
        releaseScript();
    }

    protected boolean prepare() {
        if (blurRadius == 0) {
            release();
            return false;
        }
        float downSampleFactor = downSample;
        float radius = blurRadius / downSampleFactor;
        if (radius > 25) {
            downSampleFactor = downSampleFactor * radius / 25;
            radius = 25;
        }

        if (dirty || renderScript == null) {
            if (renderScript == null) {
                try {
                    renderScript = RenderScript.create(getContext());
                    blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
                } catch (android.support.v8.renderscript.RSRuntimeException e) {
                    if (isDebug(getContext())) {
                        if (e.getMessage() != null && e.getMessage().startsWith("Error loading RS jni library: java.lang.UnsatisfiedLinkError:")) {
                            throw new RuntimeException("Error loading RS jni library, Upgrade buildToolsVersion=\"24.0.2\" or higher may solve this issue");
                        } else {
                            throw e;
                        }
                    } else {
                        releaseScript();
                        return false;
                    }
                }
            }

            blurScript.setRadius(radius);
            dirty = false;
        }

        final int width = getWidth();
        final int height = getHeight();

        int scaledWidth = Math.max(1, (int) (width / downSampleFactor));
        int scaledHeight = Math.max(1, (int) (height / downSampleFactor));

        if (blurringCanvas == null || blurredBitmap == null || blurredBitmap.getWidth() != scaledWidth || blurredBitmap.getHeight() != scaledHeight) {
            releaseBitmap();

            boolean r = false;
            try {
                bitmapToBlur = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                if (bitmapToBlur == null) {
                    return false;
                }
                blurringCanvas = new Canvas(bitmapToBlur);

                blurInput = Allocation.createFromBitmap(renderScript, bitmapToBlur, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
                blurOutput = Allocation.createTyped(renderScript, blurInput.getType());

                blurredBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                rectSrc.right = scaledWidth;
                rectSrc.bottom = scaledHeight;
                if (blurredBitmap == null) {
                    return false;
                }

                r = true;
            } catch (OutOfMemoryError e) {
                // Bitmap.createBitmap() may cause OOM error
                // Simply ignore and fallback
            } finally {
                if (!r) {
                    releaseBitmap();
                    return false;
                }
            }
        }
        return true;
    }

    protected void blur(Bitmap bitmapToBlur, Bitmap blurredBitmap) {
        blurInput.copyFrom(bitmapToBlur);
        blurScript.setInput(blurInput);
        blurScript.forEach(blurOutput);
        blurOutput.copyTo(blurredBitmap);
    }

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            final int[] locations = new int[2];
            Bitmap oldBmp = blurredBitmap;
            View decor = decorView;
            if (decor != null && isShown() && prepare()) {
                boolean redrawBitmap = blurredBitmap != oldBmp;
                oldBmp = null;
                decor.getLocationOnScreen(locations);
                int x = -locations[0];
                int y = -locations[1];

                getLocationOnScreen(locations);
                x += locations[0];
                y += locations[1];

                // just erase transparent
                bitmapToBlur.eraseColor(overlayColor & 0xffffff);

                int rc = blurringCanvas.save();
                isRendering = true;
                RENDERING_COUNT++;
                try {
                    blurringCanvas.scale(1.f * bitmapToBlur.getWidth() / getWidth(), 1.f * bitmapToBlur.getHeight() / getHeight());
                    blurringCanvas.translate(-x, -y);
                    if (decor.getBackground() != null) {
                        decor.getBackground().draw(blurringCanvas);
                    }
                    decor.draw(blurringCanvas);
                } catch (StopException e) {
                } finally {
                    isRendering = false;
                    RENDERING_COUNT--;
                    blurringCanvas.restoreToCount(rc);
                }

                blur(bitmapToBlur, blurredBitmap);

                if (redrawBitmap || differentRoot) {
                    invalidate();
                }
            }
            return true;
        }
    };

    protected View getActivityDecorView() {
        Context ctx = getContext();
        for (int i = 0; i < 4 && ctx != null && !(ctx instanceof Activity) && ctx instanceof ContextWrapper; i++) {
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        if (ctx instanceof Activity) {
            return ((Activity) ctx).getWindow().getDecorView();
        } else {
            return null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        decorView = getActivityDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            differentRoot = decorView.getRootView() != getRootView();
            if (differentRoot) {
                decorView.postInvalidate();
            }
        } else {
            differentRoot = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (decorView != null) {
            decorView.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        }
        release();
        super.onDetachedFromWindow();
    }

    @Override
    public void draw(Canvas canvas) {
        if (isRendering) {
            throw new StopException();
        } else if (RENDERING_COUNT > 0) {
        } else {
            super.draw(canvas);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawBlurredBitmap(canvas, blurredBitmap);
        drawOverlayColor(canvas);
    }

    private void drawOverlayColor(Canvas canvas) {
        canvas.drawPath(bitmapPath, overlayPaint);
    }

    protected void drawBlurredBitmap(Canvas canvas, Bitmap blurredBitmap) {
        if (blurredBitmap != null) {
            canvas.clipPath(bitmapPath);
            canvas.drawBitmap(blurredBitmap, rectSrc, rectDst, null);
        }
    }




    static Boolean DEBUG = null;

    static boolean isDebug(Context ctx) {
        if (DEBUG == null && ctx != null) {
            DEBUG = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
        return DEBUG == Boolean.TRUE;
    }
}