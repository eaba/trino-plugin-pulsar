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

import static io.trino.spi.transaction.IsolationLevel.READ_COMMITTED;
import static io.trino.spi.transaction.IsolationLevel.checkConnectorSupports;
import static java.util.Objects.requireNonNull;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.log.Logger;
import io.trino.plugin.base.classloader.ClassLoaderSafeConnectorMetadata;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;
import javax.inject.Inject;

/**
 * This file contains implementation of the connector to the Presto engine.
 */
public class PulsarConnector implements Connector {

    private static final Logger log = Logger.get(PulsarConnector.class);

    private final LifeCycleManager lifeCycleManager;
    private final PulsarMetadata metadata;
    private final PulsarSplitManager splitManager;
    private final PulsarRecordSetProvider recordSetProvider;
    private final PulsarConnectorConfig pulsarConnectorConfig;

    @Inject
    public PulsarConnector(
            LifeCycleManager lifeCycleManager,
            PulsarMetadata metadata,
            PulsarSplitManager splitManager,
            PulsarRecordSetProvider recordSetProvider,
            PulsarConnectorConfig pulsarConnectorConfig
    ) {
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.splitManager = requireNonNull(splitManager, "splitManager is null");
        this.recordSetProvider = requireNonNull(recordSetProvider, "recordSetProvider is null");
        this.pulsarConnectorConfig = requireNonNull(pulsarConnectorConfig, "pulsarConnectorConfig is null");
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly) {
        checkConnectorSupports(READ_COMMITTED, isolationLevel);
        return PulsarTransactionHandle.INSTANCE;
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorTransactionHandle transactionHandle) {
        return new ClassLoaderSafeConnectorMetadata(metadata, getClass().getClassLoader());
    }

    @Override
    public ConnectorSplitManager getSplitManager() {
        return splitManager;
    }

    @Override
    public ConnectorRecordSetProvider getRecordSetProvider() {
        return recordSetProvider;
    }

    public void initConnectorCache() throws Exception {
        PulsarConnectorCache.getConnectorCache(pulsarConnectorConfig);
    }

    @Override
    public final void shutdown() {
        try {
            this.pulsarConnectorConfig.close();
        } catch (Exception e) {
            log.error(e, "Failed to close pulsar connector");
        }
        try {
            lifeCycleManager.stop();
        } catch (Exception e) {
            log.error(e, "Error shutting down connector");
        }
    }
}
