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
package androidx.media3.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import androidx.annotation.RestrictTo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Util
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.media3.test.utils.doBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlTextureFrameRendererTest {

    private val width = 20
    private val height = 10
    private lateinit var context: Context
    private lateinit var executorService: ListeningExecutorService
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var glObjectsProvider: GlObjectsProvider
    private lateinit var consumer: GlTextureFrameRenderer
    private lateinit var imageReader: ImageReader

    private val errorConsumer = Consumer<VideoFrameProcessingException> { e -> throw e }

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            executorService = MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("Test-GL"))
            dispatcher = executorService.asCoroutineDispatcher()
            withContext(dispatcher) {
                glObjectsProvider = SingleContextGlObjectsProvider()
                val eglDisplay = GlUtil.getDefaultEglDisplay()
                val eglContext =
                    glObjectsProvider.createEglContext(
                        GlUtil.getDefaultEglDisplay(),
                        2,
                        GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
                    )
                glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
            }
        }
    }

    @After
    fun tearDown() = runBlocking {
        // withContext(dispatcher) { glObjectsProvider.release(GlUtil.getDefaultEglDisplay()) }
        if (::consumer.isInitialized) {
            consumer.release()
        }
        if (::imageReader.isInitialized) {
            imageReader.close()
        }
    }

    @Test
    fun queuePacket_singleFrame_rendersToSurfaceAndReleasesFrame() = doBlocking {
        val renderCount = AtomicInteger()
        val deferredBitmap1 = CompletableDeferred<Bitmap>()
        val releasedTextures = mutableListOf<GlTextureInfo>()
        setupOutputSurface { bitmap ->
            val renderCount = renderCount.incrementAndGet()
            if (renderCount == 1) {
                deferredBitmap1.complete(bitmap)
            } else {
                throw AssertionError("Unexpected output bitmap")
            }
        }
        val outputSurfaceInfo = SurfaceInfo(imageReader.surface, width, height)
        consumer =
            GlTextureFrameRenderer.create(context, executorService, glObjectsProvider, errorConsumer)
        consumer.setOutputSurfaceInfo(outputSurfaceInfo)
        val inputFrameWithBitmap1 =
            createFrameWithBitmap(Color.RED, width, height) { t -> releasedTextures.add(t) }
        launch { consumer.run() }

        consumer.queuePacket(Packet.of(inputFrameWithBitmap1.frame))
        val outputBitmap1 = deferredBitmap1.await()
        yield()

        val pixelDiff1 =
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                outputBitmap1,
                inputFrameWithBitmap1.bitmap,
                null,
            )
        assertThat(pixelDiff1).isEqualTo(0f)
        assertThat(releasedTextures).containsExactly(inputFrameWithBitmap1.frame.glTextureInfo)

        consumer.release()
    }

    @Test
    fun queuePacket_multipleFrames_rendersToSurfaceAndReleasesFrame() = doBlocking {
        val renderCount = AtomicInteger()
        val deferredBitmap1 = CompletableDeferred<Bitmap>()
        val deferredBitmap2 = CompletableDeferred<Bitmap>()
        val deferredBitmap3 = CompletableDeferred<Bitmap>()
        val releasedTextures = mutableListOf<GlTextureInfo>()
        setupOutputSurface { bitmap ->
            val renderCount = renderCount.incrementAndGet()
            when (renderCount) {
                1 -> {
                    deferredBitmap1.complete(bitmap)
                }
                2 -> {
                    deferredBitmap2.complete(bitmap)
                }
                3 -> {
                    deferredBitmap3.complete(bitmap)
                }
                else -> {
                    throw AssertionError("Unexpected output bitmap")
                }
            }
        }
        val outputSurfaceInfo = SurfaceInfo(imageReader.surface, width, height)
        consumer =
            GlTextureFrameRenderer.create(context, executorService, glObjectsProvider, errorConsumer)
        consumer.setOutputSurfaceInfo(outputSurfaceInfo)
        val inputFrameWithBitmap1 =
            createFrameWithBitmap(Color.RED, width, height) { t -> releasedTextures.add(t) }
        val inputFrameWithBitmap2 =
            createFrameWithBitmap(Color.BLUE, width, height) { t -> releasedTextures.add(t) }
        val inputFrameWithBitmap3 =
            createFrameWithBitmap(Color.GREEN, width, height) { t -> releasedTextures.add(t) }
        launch { consumer.run() }

        consumer.queuePacket(Packet.of(inputFrameWithBitmap1.frame))
        consumer.queuePacket(Packet.of(inputFrameWithBitmap2.frame))
        consumer.queuePacket(Packet.of(inputFrameWithBitmap3.frame))

        val outputBitmap1 = deferredBitmap1.await()
        val outputBitmap2 = deferredBitmap2.await()
        val outputBitmap3 = deferredBitmap3.await()
        yield()

        val pixelDiff1 =
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                outputBitmap1,
                inputFrameWithBitmap1.bitmap,
                null,
            )
        assertThat(pixelDiff1).isEqualTo(0f)
        val pixelDiff2 =
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                outputBitmap2,
                inputFrameWithBitmap2.bitmap,
                null,
            )
        assertThat(pixelDiff2).isEqualTo(0f)
        val pixelDiff3 =
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                outputBitmap3,
                inputFrameWithBitmap3.bitmap,
                null,
            )
        assertThat(pixelDiff3).isEqualTo(0f)
        assertThat(releasedTextures)
            .containsExactly(
                inputFrameWithBitmap1.frame.glTextureInfo,
                inputFrameWithBitmap2.frame.glTextureInfo,
                inputFrameWithBitmap3.frame.glTextureInfo,
            )
            .inOrder()

        consumer.release()
    }

    @Test
    fun queuePacket_noOutputSurfaceSet_dropsFrames() = doBlocking {
        val deferredReleasedTexture1 = CompletableDeferred<GlTextureInfo>()
        val deferredReleasedTexture2 = CompletableDeferred<GlTextureInfo>()
        consumer =
            GlTextureFrameRenderer.create(context, executorService, glObjectsProvider, errorConsumer)
        val inputFrameWithBitmap1 =
            createFrameWithBitmap(Color.RED, width, height) { t -> deferredReleasedTexture1.complete(t) }
        val inputFrameWithBitmap2 =
            createFrameWithBitmap(Color.BLUE, width, height) { t -> deferredReleasedTexture2.complete(t) }
        launch { consumer.run() }

        consumer.queuePacket(Packet.of(inputFrameWithBitmap1.frame))
        consumer.queuePacket(Packet.of(inputFrameWithBitmap2.frame))

        val releasedTexture1 = withTimeout(TEST_TIMEOUT_MS) { deferredReleasedTexture1.await() }
        val releasedTexture2 = withTimeout(TEST_TIMEOUT_MS) { deferredReleasedTexture2.await() }
        assertThat(releasedTexture1).isEqualTo(inputFrameWithBitmap1.frame.glTextureInfo)
        assertThat(releasedTexture2).isEqualTo(inputFrameWithBitmap2.frame.glTextureInfo)

        consumer.release()
    }

    private fun setupOutputSurface(onFrameAvailable: (Bitmap) -> Unit) {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, /* maxImages= */ 1)
        imageReader.setOnImageAvailableListener(
            { reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    onFrameAvailable(imageToBitmap(image))
                    image.close()
                }
            },
            Util.createHandlerForCurrentOrMainLooper(),
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap =
            Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
        bitmap.copyPixelsFromBuffer(buffer)
        // The bitmap may have extra padding. Crop it to the actual image size.
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private suspend fun createFrameWithBitmap(
        color: Int,
        width: Int,
        height: Int,
        onRelease: (GlTextureInfo) -> Unit,
    ): GlTextureFrameWithBitmap =
        withContext(dispatcher) {
            val textureId =
                GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents= */ false)
            val fboId = GlUtil.createFboForTexture(textureId)
            GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
            GlUtil.clearFocusedBuffers()
            GLES20.glClearColor(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
                1f,
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val textureInfo = GlTextureInfo(textureId, fboId, /* rboId= */ -1, width, height)
            val frame =
                GlTextureFrame.Builder(textureInfo, MoreExecutors.directExecutor(), onRelease).build()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)

            return@withContext GlTextureFrameWithBitmap(frame, bitmap)
        }

    private data class GlTextureFrameWithBitmap(val frame: GlTextureFrame, val bitmap: Bitmap)

    /**
     * A [GlObjectsProvider] that reuses a single [EGLContext] across [createEglContext] calls.
     *
     * @param delegate The underlying provider to create the context and other objects.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    class SingleContextGlObjectsProvider(
        private val delegate: GlObjectsProvider = DefaultGlObjectsProvider()
    ) : GlObjectsProvider by delegate {

        private var singleEglContext: EGLContext? = null

        override fun createEglContext(
            eglDisplay: EGLDisplay,
            openGlVersion: Int,
            configAttributes: IntArray,
        ): EGLContext =
            singleEglContext
                ?: delegate.createEglContext(eglDisplay, openGlVersion, configAttributes).also {
                    singleEglContext = it
                }

        override fun release(eglDisplay: EGLDisplay) {
            if (singleEglContext != null) {
                delegate.release(eglDisplay)
                singleEglContext = null
            }
        }
    }

    private companion object {
        private const val TEST_TIMEOUT_MS = 1000L
    }
}
