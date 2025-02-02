/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.pulsar;

import io.trino.decoder.DecoderColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import java.util.List;
import java.util.Set;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.schema.SchemaInfo;

/**
 * Pulsar customized RowDecoderFactory interface.
 */
public interface PulsarRowDecoderFactory {

    /**
     * extract ColumnMetadata from pulsar SchemaInfo and HandleKeyValueType.
     * @param schemaInfo
     * @param handleKeyValueType
     * @return
     */
    List<ColumnMetadata> extractColumnMetadata(TopicName topicName, SchemaInfo schemaInfo,
                                               PulsarColumnHandle.HandleKeyValueType handleKeyValueType);

    /**
     * createRowDecoder RowDecoder by pulsar SchemaInfo and column DecoderColumnHandles.
     * @param schemaInfo
     * @param columns
     * @return
     */
    PulsarRowDecoder createRowDecoder(TopicName topicName, SchemaInfo schemaInfo,
                                      Set<DecoderColumnHandle> columns);

}
