/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.segment;


import com.google.common.primitives.Ints;
import io.druid.collections.bitmap.ImmutableBitmap;
import io.druid.collections.bitmap.MutableBitmap;
import io.druid.java.util.common.StringUtils;
import io.druid.java.util.common.io.smoosh.FileSmoosher;
import io.druid.segment.data.BitmapSerdeFactory;
import io.druid.segment.data.ByteBufferWriter;
import io.druid.segment.data.CompressedObjectStrategy;
import io.druid.segment.data.CompressionFactory;
import io.druid.segment.data.DoubleSupplierSerializer;
import io.druid.segment.data.IOPeon;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;

public class DoubleColumnSerializer implements GenericColumnSerializer
{
  public static DoubleColumnSerializer create(
      IOPeon ioPeon,
      String filenameBase,
      CompressedObjectStrategy.CompressionStrategy compression,
      BitmapSerdeFactory bitmapSerdeFactory
  )
  {
    return new DoubleColumnSerializer(ioPeon, filenameBase, IndexIO.BYTE_ORDER, compression, bitmapSerdeFactory);
  }

  private final IOPeon ioPeon;
  private final String filenameBase;
  private final ByteOrder byteOrder;
  private final CompressedObjectStrategy.CompressionStrategy compression;
  private final BitmapSerdeFactory bitmapSerdeFactory;
  private DoubleSupplierSerializer writer;
  private ByteBufferWriter<ImmutableBitmap> nullValueBitmapWriter;
  private MutableBitmap nullRowsBitmap;
  private int rowCount = 0;

  public DoubleColumnSerializer(
      IOPeon ioPeon,
      String filenameBase,
      ByteOrder byteOrder,
      CompressedObjectStrategy.CompressionStrategy compression,
      BitmapSerdeFactory bitmapSerdeFactory
  )
  {
    this.ioPeon = ioPeon;
    this.filenameBase = filenameBase;
    this.byteOrder = byteOrder;
    this.compression = compression;
    this.bitmapSerdeFactory = bitmapSerdeFactory;
  }

  @Override
  public void open() throws IOException
  {
    writer = CompressionFactory.getDoubleSerializer(
        ioPeon,
        StringUtils.format("%s.double_column", filenameBase),
        byteOrder,
        compression
    );
    writer.open();
    nullValueBitmapWriter = new ByteBufferWriter<>(
        ioPeon,
        StringUtils.format("%s.nullBitmap", filenameBase),
        bitmapSerdeFactory.getObjectStrategy()
    );
    nullValueBitmapWriter.open();
    nullRowsBitmap = bitmapSerdeFactory.getBitmapFactory().makeEmptyMutableBitmap();
  }

  @Override
  public void serialize(@Nullable Object obj) throws IOException
  {
    if (obj == null) {
      nullRowsBitmap.add(rowCount);
      writer.add(0D);
    } else {
      writer.add(((Number) obj).doubleValue());
    }
    rowCount++;
  }

  @Override
  public void close() throws IOException
  {
    writer.close();
    nullValueBitmapWriter.write(bitmapSerdeFactory.getBitmapFactory().makeImmutableBitmap(nullRowsBitmap));
    nullValueBitmapWriter.close();
  }

  @Override
  public long getSerializedSize()
  {
    long bitmapSize = nullRowsBitmap.isEmpty()
                      ? 0L
                      : nullValueBitmapWriter.getSerializedSize();
    return Integer.BYTES + writer.getSerializedSize() + bitmapSize;
  }

  @Override
  public void writeToChannel(WritableByteChannel channel, FileSmoosher smoosher) throws IOException
  {
    channel.write(ByteBuffer.wrap(Ints.toByteArray((int) writer.getSerializedSize())));
    writer.writeToChannel(channel, smoosher);
    if (!nullRowsBitmap.isEmpty()) {
      nullValueBitmapWriter.writeToChannel(channel, smoosher);
    }
  }

}