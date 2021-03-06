/*
 *  Copyright (c) 2017 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.uber.hoodie.hadoop.realtime;

import com.uber.hoodie.common.table.log.HoodieUnMergedLogRecordScanner;
import com.uber.hoodie.common.util.DefaultSizeEstimator;
import com.uber.hoodie.common.util.FSUtils;
import com.uber.hoodie.common.util.queue.BoundedInMemoryExecutor;
import com.uber.hoodie.common.util.queue.BoundedInMemoryQueueProducer;
import com.uber.hoodie.common.util.queue.FunctionBasedQueueProducer;
import com.uber.hoodie.common.util.queue.IteratorBasedQueueProducer;
import com.uber.hoodie.hadoop.RecordReaderValueIterator;
import com.uber.hoodie.hadoop.SafeParquetRecordReaderWrapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;

class RealtimeUnmergedRecordReader extends AbstractRealtimeRecordReader implements
    RecordReader<Void, ArrayWritable> {

  // Log Record unmerged scanner
  private final HoodieUnMergedLogRecordScanner logRecordScanner;

  // Parquet record reader
  private final RecordReader<Void, ArrayWritable> parquetReader;

  // Parquet record iterator wrapper for the above reader
  private final RecordReaderValueIterator<Void, ArrayWritable> parquetRecordsIterator;

  // Executor that runs the above producers in parallel
  private final BoundedInMemoryExecutor<ArrayWritable, ArrayWritable, ?> executor;

  // Iterator for the buffer consumer
  private final Iterator<ArrayWritable> iterator;

  /**
   * Construct a Unmerged record reader that parallely consumes both parquet and log records and buffers for upstream
   * clients to consume
   *
   * @param split      File split
   * @param job        Job Configuration
   * @param realReader Parquet Reader
   */
  public RealtimeUnmergedRecordReader(HoodieRealtimeFileSplit split, JobConf job,
      RecordReader<Void, ArrayWritable> realReader) {
    super(split, job);
    this.parquetReader = new SafeParquetRecordReaderWrapper(realReader);
    // Iterator for consuming records from parquet file
    this.parquetRecordsIterator = new RecordReaderValueIterator<>(this.parquetReader);
    this.executor = new BoundedInMemoryExecutor<>(getMaxCompactionMemoryInBytes(), getParallelProducers(),
        Optional.empty(), x -> x, new DefaultSizeEstimator<>());
    // Consumer of this record reader
    this.iterator = this.executor.getQueue().iterator();
    this.logRecordScanner = new HoodieUnMergedLogRecordScanner(
        FSUtils.getFs(split.getPath().toString(), jobConf), split.getBasePath(),
        split.getDeltaFilePaths(), getReaderSchema(), split.getMaxCommitTime(), Boolean.valueOf(jobConf
        .get(COMPACTION_LAZY_BLOCK_READ_ENABLED_PROP, DEFAULT_COMPACTION_LAZY_BLOCK_READ_ENABLED)),
        false, jobConf.getInt(MAX_DFS_STREAM_BUFFER_SIZE_PROP, DEFAULT_MAX_DFS_STREAM_BUFFER_SIZE),
        record -> {
          // convert Hoodie log record to Hadoop AvroWritable and buffer
          GenericRecord rec = (GenericRecord) record.getData().getInsertValue(getReaderSchema()).get();
          ArrayWritable aWritable = (ArrayWritable) avroToArrayWritable(rec, getWriterSchema());
          this.executor.getQueue().insertRecord(aWritable);
        });
    // Start reading and buffering
    this.executor.startProducers();
  }

  /**
   * Setup log and parquet reading in parallel. Both write to central buffer.
   */
  @SuppressWarnings("unchecked")
  private List<BoundedInMemoryQueueProducer<ArrayWritable>> getParallelProducers() {
    List<BoundedInMemoryQueueProducer<ArrayWritable>> producers = new ArrayList<>();
    producers.add(new FunctionBasedQueueProducer<>(buffer -> {
      logRecordScanner.scan();
      return null;
    }));
    producers.add(new IteratorBasedQueueProducer<>(parquetRecordsIterator));
    return producers;
  }

  @Override
  public boolean next(Void key, ArrayWritable value) throws IOException {
    if (!iterator.hasNext()) {
      return false;
    }
    // Copy from buffer iterator and set to passed writable
    value.set(iterator.next().get());
    return true;
  }

  @Override
  public Void createKey() {
    return parquetReader.createKey();
  }

  @Override
  public ArrayWritable createValue() {
    return parquetReader.createValue();
  }

  @Override
  public long getPos() throws IOException {
    //TODO: vb - No logical way to represent parallel stream pos in a single long.
    // Should we just return invalid (-1). Where is it used ?
    return 0;
  }

  @Override
  public void close() throws IOException {
    this.parquetRecordsIterator.close();
    this.executor.shutdownNow();
  }

  @Override
  public float getProgress() throws IOException {
    return Math.min(parquetReader.getProgress(), logRecordScanner.getProgress());
  }
}
