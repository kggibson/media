
/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;
import static com.google.common.base.Preconditions.checkNotNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.FrameConsumer;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.MultiSequenceFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
interface PlaybackPositionListener {
  /** Called when the playback position should be rendered. */
  void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException;
}
// TODO: Make this generic, turn Metadata into a list
public class FrameReleaseTimer
    implements PlaybackPositionListener, FrameConsumer<MultiSequenceFrame<GlTextureFrame>> {
  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo =
      new VideoFrameReleaseControl.FrameReleaseInfo();
  private final Queue<MultiSequenceFrame<GlTextureFrame>> frameQueue =
      new ConcurrentLinkedQueue<>();
  @Nullable private FrameConsumer<MultiSequenceFrame<GlTextureFrame>> downstreamConsumer;
  private long lastPresentationTimeUs = C.TIME_UNSET;
  public FrameReleaseTimer(VideoFrameReleaseControl videoFrameReleaseControl) {
    this.videoFrameReleaseControl = videoFrameReleaseControl;
  }
  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
//    Log.i(
//        "KGG",
//        this
//            + " render "
//            + Thread.currentThread().getName()
//            + " | positionUs: "
//            + positionUs
//            + ", elapsedRealtimeUs: "
//            + elapsedRealtimeUs);
    if (downstreamConsumer == null) {
      return;
    }
    @Nullable MultiSequenceFrame<GlTextureFrame> nextFrame = frameQueue.peek();
    while (nextFrame != null) {
      long bufferPts = checkNotNull(nextFrame.getFrame(0)).getMetadata().getPresentationTimeUs();
      if (bufferPts < lastPresentationTimeUs) {
        StringBuilder s = new StringBuilder();
        Log.i("KGG", "PTS: " + bufferPts + " < " + lastPresentationTimeUs);
      }
      videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
      @VideoFrameReleaseControl.FrameReleaseAction
      int frameReleaseAction =
          videoFrameReleaseControl.getFrameReleaseAction(
              /* presentationTimeUs= */ bufferPts,
              positionUs,
              elapsedRealtimeUs,
              /* outputStreamStartPositionUs= */ 0,
              /* isDecodeOnlyFrame= */ false,
              /* isLastFrame= */ false,
              videoFrameReleaseInfo);
      if (maybeQueueFrameDownstream(frameReleaseAction, nextFrame)) {
        lastPresentationTimeUs = nextFrame.getFrame(0).getMetadata().getPresentationTimeUs();
        frameQueue.poll();
      } else {
        break;
      }
      nextFrame = frameQueue.peek();
    }
  }
  private long lastQueuedTime = 0;
  @Override
  public boolean queueFrame(MultiSequenceFrame<GlTextureFrame> frame) {
    if (frame.getFrame(0).getMetadata().getPresentationTimeUs() < lastQueuedTime) {
      Log.i(
          "KGGGGG",
          this
              + " back in time "
              + frame.getFrame(0).getMetadata().getPresentationTimeUs()
              + ", last: "
              + lastQueuedTime);
      flush();
    }
    lastQueuedTime = frame.getFrame(0).getMetadata().getPresentationTimeUs();
//    Log.i("KGG", this + " queueFrame | frame: " + frame);
    frameQueue.add(frame);
    return true;
  }
  public void setOutput(FrameConsumer<MultiSequenceFrame<GlTextureFrame>> nextOutputConsumer) {
    if (this.downstreamConsumer == nextOutputConsumer) {
      return;
    }
    this.downstreamConsumer = nextOutputConsumer;
  }
  public void flush() {
    lastPresentationTimeUs = 0;
    @Nullable MultiSequenceFrame<androidx.media3.effect.GlTextureFrame> nextFrame;
    while ((nextFrame = frameQueue.poll()) != null) {
      nextFrame.release();
    }
    Log.i("KGG", this + " flushed ");
  }
  @Override
  public void setOnCapacityAvailableCallback(
      Executor executor, Runnable onCapacityAvailableCallback) {
    // TODO("Not yet implemented");
  }
  @Override
  public void clearOnCapacityAvailableCallback() {
    // TODO("Not yet implemented");
  }
  /** Return true if the frame was queued downstream. */
  private boolean maybeQueueFrameDownstream(
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction,
      MultiSequenceFrame<GlTextureFrame> frame) {
//    Log.i(
//        "KGGGG",
//        "Queued to output: "
//            + frame.getFrame(0).getMetadata().getPresentationTimeUs()
//            + ", action: "
//            + frameReleaseAction
//            + ", r: "
//            + videoFrameReleaseInfo.getReleaseTimeNs()
//            + ", t: "
//            + Thread.currentThread().getName());
    switch (frameReleaseAction) {
      case VideoFrameReleaseControl.FRAME_RELEASE_DROP:
        frame.release();
        return true;
      case VideoFrameReleaseControl.FRAME_RELEASE_SKIP:
        // ??
        return false;
      case VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY:
        return downstreamConsumer.queueFrame(frame.copyWith(SystemClock.DEFAULT.nanoTime()));
      case VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER:
      case VideoFrameReleaseControl.FRAME_RELEASE_IGNORE:
        return false;
      case VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED:
        return downstreamConsumer.queueFrame(
            frame.copyWith(videoFrameReleaseInfo.getReleaseTimeNs()));
      default:
        throw new IllegalStateException(String.valueOf(frameReleaseAction));
    }
  }
}
