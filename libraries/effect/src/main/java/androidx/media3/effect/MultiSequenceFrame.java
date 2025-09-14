
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
package androidx.media3.effect;
import static com.google.common.base.Preconditions.checkArgument;
import androidx.media3.common.C;
import androidx.media3.effect.Frame.Metadata;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
/** A collection of frames from the same point of time across multiple sequences. */
public class MultiSequenceFrame<T extends Frame> implements Frame {
  public static class Builder<T extends Frame> {
    private int numSequences;
    public Map<Integer, T> frames;
    public Builder(int numSequences) {
      this.numSequences = numSequences;
      this.frames = new HashMap<>();
    }

    @CanIgnoreReturnValue
    public Builder<T> addFrame(T frame, int sequenceIndex) {
      checkArgument(sequenceIndex < numSequences);
      frames.put(sequenceIndex, frame);
      return this;
    }

    public boolean isReady() {
      return frames.size() == numSequences;
    }
    public MultiSequenceFrame<T> build() {
      return new MultiSequenceFrame<>(frames, new Metadata(numSequences));
    }
  }

  @Override
  public MultiSequenceFrame.Metadata getMetadata() {
    return metadata;
  }

  @Override
  public void release() {
    for (T frame : frames.values()) {
      frame.release();
    }
  }

  /** Metadata associated with a {@link MultiSequenceFrame}. */
  public static final class Metadata implements Frame.Metadata {
    public final int numSequences;
    public final long releaseTimeNs;

    public Metadata(int numSequences) {
      this.numSequences = numSequences;
      this.releaseTimeNs = C.TIME_UNSET;
    }

    public Metadata(int numSequences, long releaseTimeNs) {
          this.numSequences = numSequences;
      this.releaseTimeNs = releaseTimeNs;
    }
  }

  private final Map<Integer, T> frames;
  private final Metadata metadata;

  private MultiSequenceFrame(Map<Integer, T> frames, Metadata metadata) {
    this.frames = frames;
    this.metadata = metadata;
  }

  @Nullable
  public T getFrame(int sequenceIndex) {
        checkArgument(sequenceIndex < metadata.numSequences);
    return frames.get(sequenceIndex);
  }

  public MultiSequenceFrame<T> copyWith(long releaseTimeNs) {
        Metadata m = new Metadata(metadata.numSequences, releaseTimeNs);
    return new MultiSequenceFrame<>(frames, m);
  }

  @Override
  public String toString() {
    return "frames:" + frames + ", metadata:" + metadata;
  }
}
