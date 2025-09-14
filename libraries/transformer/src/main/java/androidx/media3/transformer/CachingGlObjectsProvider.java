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
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.effect.DefaultGlObjectsProvider;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
/** TODO */
public class CachingGlObjectsProvider implements GlObjectsProvider {
  private DefaultGlObjectsProvider defaultGlObjectsProvider = new DefaultGlObjectsProvider();
  private @MonotonicNonNull EGLContext context;
  @Override
  public EGLContext createEglContext(EGLDisplay eglDisplay, int openGlVersion,
      int[] configAttributes) throws GlUtil.GlException {
    if (context == null) {
      context = defaultGlObjectsProvider.createEglContext(eglDisplay,
          openGlVersion,
          configAttributes);
      Log.w("DANCHO", "create egl context from " + this);
    }
    return context;
  }
  @Override
  public EGLSurface createEglSurface(EGLDisplay eglDisplay, Object surface,
      @C.ColorTransfer int colorTransfer, boolean isEncoderInputSurface) throws GlUtil.GlException {
    return defaultGlObjectsProvider.createEglSurface(eglDisplay, surface, colorTransfer, isEncoderInputSurface);
  }
  @Override
  public EGLSurface createFocusedPlaceholderEglSurface(EGLContext eglContext, EGLDisplay eglDisplay)
      throws GlUtil.GlException {
    return defaultGlObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }
  @Override
  public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
      throws GlUtil.GlException {
    return defaultGlObjectsProvider.createBuffersForTexture(texId, width, height);
  }
  @Override
  public void release(EGLDisplay eglDisplay) throws GlUtil.GlException {
    defaultGlObjectsProvider.release(eglDisplay);
  }
}
