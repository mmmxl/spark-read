/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network.server;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.channel.Channel;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.client.TransportClient;

/**
 * StreamManager which allows registration of an Iterator&lt;ManagedBuffer&gt;, which are
 * individually fetched as chunks by the client. Each registered buffer is one chunk.
 * 一对一的流服务
 */
public class OneForOneStreamManager extends StreamManager {
  private static final Logger logger = LoggerFactory.getLogger(OneForOneStreamManager.class);

  private final AtomicLong nextStreamId; // 用于生成数据流的标识
  private final ConcurrentHashMap<Long, StreamState> streams; // 维护streamId与SteamState之间映射关系的缓存

  /** State of a single stream. */
  private static class StreamState {
    final String appId;
    final Iterator<ManagedBuffer> buffers;

    // The channel associated to the stream
    final Channel associatedChannel;

    // Used to keep track of the index of the buffer that the user has retrieved, just to ensure
    // that the caller only requests each chunk one at a time, in order.
    // 为了保证客户端按顺序每次请求一个块，所以用此属性跟踪客户端当前接收到的ManagedBuffer的索引
    int curChunk = 0;

    // Used to keep track of the number of chunks being transferred and not finished yet.
    volatile long chunksBeingTransferred = 0L;

    StreamState(String appId, Iterator<ManagedBuffer> buffers, Channel channel) {
      this.appId = appId; // 请求流所属的应用程序ID
      this.buffers = Preconditions.checkNotNull(buffers); // ManagedBuffer的缓冲
      this.associatedChannel = channel; // 与当前流相关联的channel
    }
  }

  public OneForOneStreamManager() {
    // For debugging purposes, start with a random stream id to help identifying different streams.
    // This does not need to be globally unique, only unique to this class.
    nextStreamId = new AtomicLong((long) new Random().nextInt(Integer.MAX_VALUE) * 1000);
    streams = new ConcurrentHashMap<>();
  }

  @Override
  public ManagedBuffer getChunk(long streamId, int chunkIndex) {
    StreamState state = streams.get(streamId);
    if (chunkIndex != state.curChunk) {
      throw new IllegalStateException(String.format(
        "Received out-of-order chunk index %s (expected %s)", chunkIndex, state.curChunk));
    } else if (!state.buffers.hasNext()) {
      throw new IllegalStateException(String.format(
        "Requested chunk index beyond end %s", chunkIndex));
    }
    state.curChunk += 1;
    ManagedBuffer nextChunk = state.buffers.next();

    if (!state.buffers.hasNext()) {
      logger.trace("Removing stream id {}", streamId);
      streams.remove(streamId);
    }

    return nextChunk;
  }

  @Override
  public ManagedBuffer openStream(String streamChunkId) {
    Pair<Long, Integer> streamChunkIdPair = parseStreamChunkId(streamChunkId);
    return getChunk(streamChunkIdPair.getLeft(), streamChunkIdPair.getRight());
  }

  public static String genStreamChunkId(long streamId, int chunkId) {
    return String.format("%d_%d", streamId, chunkId);
  }

  // Parse streamChunkId to be stream id and chunk id. This is used when fetch remote chunk as a
  // stream.
  public static Pair<Long, Integer> parseStreamChunkId(String streamChunkId) {
    String[] array = streamChunkId.split("_");
    assert array.length == 2:
      "Stream id and chunk index should be specified.";
    long streamId = Long.valueOf(array[0]);
    int chunkIndex = Integer.valueOf(array[1]);
    return ImmutablePair.of(streamId, chunkIndex);
  }

  @Override
  public void connectionTerminated(Channel channel) {
    RuntimeException failedToReleaseBufferException = null;

    // Close all streams which have been associated with the channel.
    for (Map.Entry<Long, StreamState> entry: streams.entrySet()) {
      StreamState state = entry.getValue();
      if (state.associatedChannel == channel) {
        streams.remove(entry.getKey());

        try {
          // Release all remaining buffers.
          while (state.buffers.hasNext()) {
            ManagedBuffer buffer = state.buffers.next();
            if (buffer != null) {
              buffer.release();
            }
          }
        } catch (RuntimeException e) {
          if (failedToReleaseBufferException == null) {
            failedToReleaseBufferException = e;
          } else {
            logger.error("Exception trying to release remaining StreamState buffers", e);
          }
        }
      }
    }

    if (failedToReleaseBufferException != null) {
      throw failedToReleaseBufferException;
    }
  }

  @Override
  public void checkAuthorization(TransportClient client, long streamId) {
    if (client.getClientId() != null) {
      StreamState state = streams.get(streamId);
      Preconditions.checkArgument(state != null, "Unknown stream ID.");
      if (!client.getClientId().equals(state.appId)) {
        throw new SecurityException(String.format(
          "Client %s not authorized to read stream %d (app %s).",
          client.getClientId(),
          streamId,
          state.appId));
      }
    }
  }

  @Override
  public void chunkBeingSent(long streamId) {
    StreamState streamState = streams.get(streamId);
    if (streamState != null) {
      streamState.chunksBeingTransferred++;
    }

  }

  @Override
  public void streamBeingSent(String streamId) {
    chunkBeingSent(parseStreamChunkId(streamId).getLeft());
  }

  @Override
  public void chunkSent(long streamId) {
    StreamState streamState = streams.get(streamId);
    if (streamState != null) {
      streamState.chunksBeingTransferred--;
    }
  }

  @Override
  public void streamSent(String streamId) {
    chunkSent(OneForOneStreamManager.parseStreamChunkId(streamId).getLeft());
  }

  @Override
  public long chunksBeingTransferred() {
    long sum = 0L;
    for (StreamState streamState: streams.values()) {
      sum += streamState.chunksBeingTransferred;
    }
    return sum;
  }

  /**
   * Registers a stream of ManagedBuffers which are served as individual chunks one at a time to
   * callers. Each ManagedBuffer will be release()'d after it is transferred on the wire. If a
   * client connection is closed before the iterator is fully drained, then the remaining buffers
   * will all be release()'d.
   *
   * If an app ID is provided, only callers who've authenticated with the given app ID will be
   * allowed to fetch from this stream.
   *
   * This method also associates the stream with a single client connection, which is guaranteed
   * to be the only reader of the stream. Once the connection is closed, the stream will never
   * be used again, enabling cleanup by `connectionTerminated`.
   */
  public long registerStream(String appId, Iterator<ManagedBuffer> buffers, Channel channel) {
    long myStreamId = nextStreamId.getAndIncrement();
    streams.put(myStreamId, new StreamState(appId, buffers, channel));
    return myStreamId;
  }

  @VisibleForTesting
  public int numStreamStates() {
    return streams.size();
  }
}
