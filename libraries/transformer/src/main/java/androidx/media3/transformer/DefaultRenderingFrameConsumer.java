
/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.view.SurfaceView;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.MultiSequenceFrame;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class DefaultRenderingFrameConsumer implements RenderingFrameConsumer<MultiSequenceFrame<GlTextureFrame>> {
  private final ExecutorService glExecutorService;
  private final Paint bitmapPaint;
  private @MonotonicNonNull SurfaceView surfaceView;

  public DefaultRenderingFrameConsumer(ExecutorService glExecutorService) {
    this.glExecutorService = glExecutorService;
    this.bitmapPaint = new Paint();
    this.bitmapPaint.setFilterBitmap(true);
  }

  @Override
  public void setVideoOutput(Object videoOutput) {
    checkArgument(videoOutput instanceof SurfaceView);
    this.surfaceView = (SurfaceView) videoOutput;
  }

  @Override
  public boolean queueFrame(MultiSequenceFrame<GlTextureFrame> frame) {
    checkState(surfaceView != null);
    glExecutorService.submit(
        () -> {
          render(frame);
          frame.release();
        });
    return true;
  }

  @Override
  public void setOnCapacityAvailableCallback(Executor executor,
      Runnable onCapacityAvailableCallback) {
  }

  @Override
  public void clearOnCapacityAvailableCallback() {}

  // Must be called on the GL thread.
  private void render(MultiSequenceFrame<GlTextureFrame> frame) {
    Canvas canvas = surfaceView.getHolder().lockCanvas();
    if (canvas == null) {
      // Surface is not ready or was destroyed
      frame.release();
      return;
    }

    int w = canvas.getWidth() / 2;
    int h = canvas.getHeight() / 2;

    Rect[] destRects =
        new Rect[] {
            new Rect(0, 0, w, h),
            new Rect(0, h, w, h * 2),
            new Rect(w, 0, w * 2, h),
            new Rect(w, h, w * 2, h * 2)
        };

    for (int i = 0; i < frame.getMetadata().numSequences; ++i) {
      GlTextureInfo outputTexture = frame.getFrame(i).getGlTextureInfo();
      int pixelBufferSize = outputTexture.width * outputTexture.height * 4;
      ByteBuffer byteBuffer = ByteBuffer.allocateDirect(pixelBufferSize);
      try {
        GlUtil.focusFramebufferUsingCurrentContext(
            outputTexture.fboId, outputTexture.width, outputTexture.height);
        GlUtil.checkGlError();
        GLES20.glReadPixels(
            /* x= */ 0,
            /* y= */ 0,
            outputTexture.width,
            outputTexture.height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer);
        GlUtil.checkGlError();
        Bitmap bitmap =
            Bitmap.createBitmap(
                outputTexture.width, outputTexture.height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(byteBuffer);
        Rect destRect = destRects[i];
        canvas.save();
        canvas.scale(1f, -1f, destRect.centerX(), destRect.centerY());
        canvas.drawBitmap(
            bitmap,
            /* src= */ null,
            destRect,
            bitmapPaint);
        canvas.restore();
        bitmap.recycle();
      } catch (GlUtil.GlException e) {
        throw new RuntimeException(e);
      }
    }
    surfaceView.getHolder().unlockCanvasAndPost(canvas);
  }
}