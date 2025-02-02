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

import static java.util.Objects.requireNonNull;
import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.Type;
import java.util.List;

/**
 * Implementation of a record set.
 */
public class PulsarRecordSet implements RecordSet {

    private final List<PulsarColumnHandle> columnHandles;
    private final List<Type> columnTypes;
    private final PulsarSplit pulsarSplit;
    private final PulsarConnectorConfig pulsarConnectorConfig;

    private PulsarDispatchingRowDecoderFactory decoderFactory;

    public PulsarRecordSet(PulsarSplit split, List<PulsarColumnHandle> columnHandles, PulsarConnectorConfig
            pulsarConnectorConfig, PulsarDispatchingRowDecoderFactory decoderFactory) {
        requireNonNull(split, "split is null");
        this.columnHandles = requireNonNull(columnHandles, "column handles is null");
        ImmutableList.Builder<Type> types = ImmutableList.builder();
        for (PulsarColumnHandle column : columnHandles) {
            types.add(column.getType());
        }
        this.columnTypes = types.build();

        this.pulsarSplit = split;

        this.pulsarConnectorConfig = pulsarConnectorConfig;

        this.decoderFactory = decoderFactory;
    }


    @Override
    public List<Type> getColumnTypes() {
        return this.columnTypes;
    }

    @Override
    public RecordCursor cursor() {
        return new PulsarRecordCursor(this.columnHandles, this.pulsarSplit,
                this.pulsarConnectorConfig, this.decoderFactory);
    }
}