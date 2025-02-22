/*
 * Copyright 2018 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.radarbase.connect.rest.fitbit.converter;

import static org.radarbase.connect.rest.fitbit.request.FitbitRequestGenerator.JSON_READER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.confluent.connect.avro.AvroData;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import okhttp3.Headers;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.IndexedRecord;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.source.SourceRecord;
import org.radarbase.connect.rest.converter.PayloadToSourceRecordConverter;
import org.radarbase.connect.rest.fitbit.request.FitbitRestRequest;
import org.radarbase.connect.rest.fitbit.user.User;
import org.radarbase.connect.rest.request.RestRequest;
import org.radarcns.active.connect.GroupingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.radarcns.active.connect.ConnectDataLog;

/**
 * Abstract class to help convert Fitbit data to Avro Data.
 */
public abstract class FitbitAvroConverter implements PayloadToSourceRecordConverter {
  private static final Logger logger = LoggerFactory.getLogger(FitbitAvroConverter.class);
  private static final Map<String, TimeUnit> TIME_UNIT_MAP = new HashMap<>();

  static {
    TIME_UNIT_MAP.put("minute", TimeUnit.MINUTES);
    TIME_UNIT_MAP.put("second", TimeUnit.SECONDS);
    TIME_UNIT_MAP.put("hour", TimeUnit.HOURS);
    TIME_UNIT_MAP.put("day", TimeUnit.DAYS);
    TIME_UNIT_MAP.put("millisecond", TimeUnit.MILLISECONDS);
    TIME_UNIT_MAP.put("nanosecond", TimeUnit.NANOSECONDS);
    TIME_UNIT_MAP.put("microsecond", TimeUnit.MICROSECONDS);
  }

  private final AvroData avroData;

  public FitbitAvroConverter(AvroData avroData) {
    this.avroData = avroData;
  }

  @Override
  public Collection<SourceRecord> convert(
      RestRequest restRequest, Headers headers, byte[] data) throws IOException {
    if (data == null) {
      throw new IOException("Failed to read body");
    }
    JsonNode activities = JSON_READER.readTree(data);

    User user = ((FitbitRestRequest) restRequest).getUser();
    final SchemaAndValue key = user.getObservationKey(avroData);
    double timeReceived = System.currentTimeMillis() / 1000d;



    ConnectDataLog fitbitLog = new ConnectDataLog();
    fitbitLog.setTime(timeReceived);
    fitbitLog.setDataGroupingType(GroupingType.PASSIVE_FITBIT);
 //   return processRecords((FitbitRestRequest)restRequest, activities, timeReceived)

    SchemaAndValue avr = avroData.toConnectData(fitbitLog.getSchema(), fitbitLog);
    SourceRecord logSourceRecord = new SourceRecord(null,null,"connect_data_log", key.schema(),key.value(),  avr.schema(), avr.value());


    Collection<SourceRecord> records = processRecords((FitbitRestRequest)restRequest, activities, timeReceived)
        .filter(t -> validateRecord((FitbitRestRequest)restRequest, t))
        .map(t -> {
          SchemaAndValue avro = avroData.toConnectData(t.value.getSchema(), t.value);
          Map<String, ?> offset = Collections.singletonMap(
              TIMESTAMP_OFFSET_KEY, t.sourceOffset.toEpochMilli());

          return new SourceRecord(restRequest.getPartition(), offset, t.topic,
              key.schema(), key.value(), avro.schema(), avro.value());
        })
        .collect(Collectors.toList());



    // don't add log if records only time_zone/steps
    try {
      var sendLog = false;
      for (SourceRecord sourceRecord : records) {
        if(!sourceRecord.topic().equals("connect_fitbit_intraday_steps")  && !sourceRecord.topic().equals("connect_fitbit_time_zone")) {
          sendLog = true;
        }
      }

      if(sendLog) {
        records.add(logSourceRecord);
      }
    }
    catch(Exception e) {
      logger.warn("Failed at adding a system log", e);
    }



    return records;
  }

  private boolean validateRecord(FitbitRestRequest request, TopicData record) {
    if (record == null) {
      return false;
    }
    Instant endDate = request.getUser().getEndDate();
    if (endDate == null) {
      return true;
    }
    Field timeField = record.value.getSchema().getField("time");
    if (timeField != null) {
      long time = (long) (((Double)record.value.get(timeField.pos()) * 1000.0));
      return Instant.ofEpochMilli(time).isBefore(endDate);
    }
    return true;
  }

  /** Process the JSON records generated by given request. */
  protected abstract Stream<TopicData> processRecords(
      FitbitRestRequest request,
      JsonNode root,
      double timeReceived);

  /** Get Fitbit dataset interval used in some intraday API calls. */
  protected static int getRecordInterval(JsonNode root, int defaultValue) {
    JsonNode type = root.get("datasetType");
    JsonNode interval = root.get("datasetInterval");
    if (type == null || interval == null) {
      logger.warn("Failed to get data interval; using {} instead", defaultValue);
      return defaultValue;
    }
    return (int)TIME_UNIT_MAP
        .getOrDefault(type.asText(), TimeUnit.SECONDS)
        .toSeconds(interval.asLong());
  }

  /** Converts an iterable (like a JsonNode containing an array) to a stream. */
  protected static <T> Stream<T> iterableToStream(Iterable<T> iter) {
    return StreamSupport.stream(iter.spliterator(), false);
  }

  protected static Optional<Long> optLong(JsonNode node, String fieldName) {
    JsonNode v = node.get(fieldName);
    return v != null && v.canConvertToLong() ? Optional.of(v.longValue()) : Optional.empty();
  }

  protected static Optional<Double> optDouble(JsonNode node, String fieldName) {
    JsonNode v = node.get(fieldName);
    return v != null && v.isNumber() ? Optional.of(v.doubleValue()) : Optional.empty();
  }

  protected static Optional<Integer> optInt(JsonNode node, String fieldName) {
    JsonNode v = node.get(fieldName);
    return v != null && v.canConvertToInt() ? Optional.of(v.intValue()) : Optional.empty();
  }

  protected static Optional<String> optString(JsonNode node, String fieldName) {
    JsonNode v = node.get(fieldName);
    return v != null && v.isTextual() ? Optional.ofNullable(v.textValue()) : Optional.empty();
  }

  protected static Optional<Boolean> optBoolean(JsonNode node, String fieldName) {
    JsonNode v = node.get(fieldName);
    return v != null && v.isBoolean() ? Optional.of(v.booleanValue()) : Optional.empty();
  }

  protected static Optional<ObjectNode> optObject(JsonNode parent, String fieldName) {
    JsonNode v = parent.get(fieldName);
    return v != null && v.isObject() ? Optional.of((ObjectNode) v) : Optional.empty();
  }

  protected static Optional<Iterable<JsonNode>> optArray(JsonNode parent, String fieldName) {
    JsonNode v = parent.get(fieldName);
    return v != null && v.isArray() && v.size() != 0 ?
        Optional.of(v) : Optional.empty();
  }

  /** Single value for a topic. */
  protected static class TopicData {
    Instant sourceOffset;
    final String topic;
    final IndexedRecord value;

    public TopicData(Instant sourceOffset, String topic, IndexedRecord value) {
      this.sourceOffset = sourceOffset;
      this.topic = topic;
      this.value = value;
    }
  }
}
