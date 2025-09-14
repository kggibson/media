
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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.Math.abs;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.effect.FrameConsumer;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.MultiSequenceFrame;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
public class FrameAggregator {
  @MonotonicNonNull FrameConsumer<MultiSequenceFrame<GlTextureFrame>> downstreamConsumer;
  Queue<MultiSequenceFrame<GlTextureFrame>> outputFrames = new ConcurrentLinkedQueue<>();
  private int numSequences;
  private Map<Integer, Queue<GlTextureFrame>> inputFrames;
  public FrameAggregator() {
    inputFrames = new HashMap<>();
  }
  public void setNumSequences(int numSequences) {
    this.numSequences = numSequences;
    flush();
    for (int i = 0; i < numSequences; i++) {
      inputFrames.put(i, new ConcurrentLinkedQueue<>());
    }
  }
  public void flush() {
    for (Queue<GlTextureFrame> frameQueue : inputFrames.values()) {
      @Nullable GlTextureFrame nextFrame;
      while ((nextFrame = frameQueue.poll()) != null) {
        nextFrame.release();
      }
    }
    Log.i("KGGGGG", this + " flush");
  }
  public void queueFrame(GlTextureFrame frame, int sequenceIndex) {
    Log.i(
        "KGG",
        this
            + " received frame: "
            + frame.getMetadata().getPresentationTimeUs()
            + ", index: "
            + sequenceIndex);
    if (sequenceIndex == 0
        && frame.getMetadata().getPresentationTimeUs() < lastQueuedPresentationTimeUs) {
//      Log.i(
//          "KGGGGG",
//          this
//              + " back in time "
//              + frame.getMetadata().getPresentationTimeUs()
//              + ", last: "
//              + lastQueuedPresentationTimeUs);
      flush();
    }
    if (sequenceIndex == 0) {
      lastQueuedPresentationTimeUs = frame.getMetadata().getPresentationTimeUs();
    }
//    printQueue();
    Queue<GlTextureFrame> frameQueue = inputFrames.get(sequenceIndex);
    checkState(frameQueue != null);
    frameQueue.add(frame);
    maybeAggregate();
//    printQueue();
    maybeDrainOutputFrames();
//    Log.i("KGGGG", "After drain: ");
//    printQueue();
  }
  long lastQueuedPresentationTimeUs = 0;
  private void maybeAggregate() {
    @Nullable GlTextureFrame nextPrimaryFrame = inputFrames.get(0).peek();
    if (nextPrimaryFrame == null) {
      return;
    }
    MultiSequenceFrame.Builder<GlTextureFrame> frameBuilder =
        new MultiSequenceFrame.Builder<>(numSequences);
    frameBuilder.addFrame(nextPrimaryFrame, 0);
    // Wait until there is one frame from each sequence?
    for (int i = 1; i < inputFrames.size(); i++) {
      Queue<GlTextureFrame> nextInputFrameQueue = inputFrames.get(i);
      checkState(nextInputFrameQueue != null);
      @Nullable GlTextureFrame nextFrame = nextInputFrameQueue.peek();
      while (nextFrame != null) {
        if (nextFrame.getMetadata().getPresentationTimeUs()
            < nextPrimaryFrame.getMetadata().getPresentationTimeUs()) {
          nextFrame.release();
          nextInputFrameQueue.poll();
          nextFrame = nextInputFrameQueue.peek();
        } else {
          break;
        }
      }
      if (nextFrame == null) {
        return;
      }
      frameBuilder.addFrame(nextFrame, i);
    }
    if (frameBuilder.isReady()) {
      outputFrames.add(frameBuilder.build());
      // TODO: Allow reusing frames from secondary sequences to handle different frame rates
      for (int i = 0; i < numSequences; i++) {
        inputFrames.get(i).poll();
      }
    }
  }
  private void releaseSecondaryFrames(
      long targetPresentationTimeUs, Queue<GlTextureFrame> nextInputFrameQueue) {
    @Nullable GlTextureFrame nextFrame = nextInputFrameQueue.peek();
    while (nextFrame != null
        && nextFrame.getMetadata().getPresentationTimeUs() < targetPresentationTimeUs) {
      nextFrame.release();
      nextInputFrameQueue.poll();
      nextFrame = nextInputFrameQueue.peek();
    }
  }
  // Get closest secondary frame.
  private @Nullable GlTextureFrame getNextFrame(
      long targetPresentationTimeUs, Queue<GlTextureFrame> frameQueue) {
    long minTimeDiffFromPrimaryUs = Long.MAX_VALUE;
    @Nullable GlTextureFrame secondaryFrameToComposite = null;
    Iterator<GlTextureFrame> frameIterator = frameQueue.iterator();
    while (frameIterator.hasNext()) {
      GlTextureFrame candidateFrame = frameIterator.next();
      long candidateTimestampUs = candidateFrame.getMetadata().getPresentationTimeUs();
      long candidateAbsDistance = abs(candidateTimestampUs - targetPresentationTimeUs);
      if (candidateAbsDistance < minTimeDiffFromPrimaryUs) {
        minTimeDiffFromPrimaryUs = candidateAbsDistance;
        secondaryFrameToComposite = candidateFrame;
      }
      // TODO: Figure out end of input.
      if (candidateTimestampUs > targetPresentationTimeUs)
      // || (!frameIterator.hasNext() && secondaryInputSource.isInputEnded))
      {
        break;
      }
    }
    if (secondaryFrameToComposite != null) {
      @Nullable GlTextureFrame frameToRelease;
      while ((frameToRelease = frameQueue.peek()) != secondaryFrameToComposite
          && frameToRelease != null) {
        frameToRelease.release();
        frameQueue.poll();
      }
    }
    return secondaryFrameToComposite;
  }
  private void printQueue() {
    StringBuilder output = new StringBuilder();
    for (int i = 0; i < inputFrames.size(); i++) {
      output.append(i).append(": ");
      StringBuilder s = new StringBuilder();
      for (GlTextureFrame frame : inputFrames.get(i)) {
        s.append(frame.getMetadata().getPresentationTimeUs()).append(", ");
      }
      output.append(s.toString()).append("\n");
    }
    Log.i("KGGGG", output.toString());
  }
  public void maybeDrainOutputFrames() {
    if (downstreamConsumer == null) {
      return;
    }
    @Nullable MultiSequenceFrame<GlTextureFrame> nextFrame = outputFrames.peek();
    while (nextFrame != null && downstreamConsumer.queueFrame(nextFrame)) {
      // Log.i(
      //     "KGGGG",
      //     "Queued to output: " + nextFrame.getFrame(0).getMetadata().getPresentationTimeUs());
      outputFrames.poll();
      nextFrame = outputFrames.peek();
    }
  }
  public void setOutput(FrameConsumer<MultiSequenceFrame<GlTextureFrame>> nextOutputConsumer) {
    @Nullable
    FrameConsumer<MultiSequenceFrame<GlTextureFrame>> oldConsumer = this.downstreamConsumer;
    if (oldConsumer == nextOutputConsumer) {
      return;
    }
    if (oldConsumer != null) {
      oldConsumer.clearOnCapacityAvailableCallback();
    }
    this.downstreamConsumer = nextOutputConsumer;
    if (downstreamConsumer != null) {
      downstreamConsumer.setOnCapacityAvailableCallback(
          directExecutor(), this::maybeForwardProcessedFrame);
    }
  }
  private void maybeForwardProcessedFrame() {
    // TODO;
  }
}
