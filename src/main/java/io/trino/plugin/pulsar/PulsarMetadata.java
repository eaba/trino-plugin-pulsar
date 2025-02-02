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

import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static io.trino.spi.StandardErrorCode.QUERY_REJECTED;
import static java.util.Objects.requireNonNull;
import static io.trino.plugin.pulsar.PulsarConnectorUtils.restoreNamespaceDelimiterIfNeeded;
import static io.trino.plugin.pulsar.PulsarConnectorUtils.rewriteNamespaceDelimiterIfNeeded;
import static io.trino.plugin.pulsar.PulsarHandleResolver.convertColumnHandle;
import static io.trino.plugin.pulsar.PulsarHandleResolver.convertTableHandle;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableLayoutHandle;
import io.trino.spi.connector.ConnectorTableLayoutResult;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.TableNotFoundException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.schema.KeyValueSchemaInfo;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;

/**
 * This connector helps to work with metadata.
 */
public class PulsarMetadata implements ConnectorMetadata {

    private final String connectorId;
    //private final PulsarAdmin pulsarAdmin;
    private final PulsarConnectorConfig pulsarConnectorConfig;

    private final PulsarDispatchingRowDecoderFactory decoderFactory;
    private final PulsarAuth pulsarAuth;

    private static final String INFORMATION_SCHEMA = "information_schema";

    private static final Logger log = Logger.get(PulsarMetadata.class);

    private final LoadingCache<SchemaTableName, TopicName> tableNameTopicNameCache =
            CacheBuilder.newBuilder()
                    // use a short live cache to make sure one query not get matched the topic many times and
                    // prevent get the wrong cache due to the topic changes in the Pulsar.
                    .expireAfterWrite(30, TimeUnit.SECONDS)
                    .build(new CacheLoader<SchemaTableName, TopicName>() {
                        @Override
                        public TopicName load(SchemaTableName schemaTableName) throws Exception {
                            return getMatchedPulsarTopic(schemaTableName);
                        }
                    });

    @Inject
    public PulsarMetadata(PulsarConnectorId connectorId, PulsarConnectorConfig pulsarConnectorConfig,
                          PulsarDispatchingRowDecoderFactory decoderFactory, PulsarAuth pulsarAuth) {
        this.decoderFactory = decoderFactory;
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.pulsarConnectorConfig = pulsarConnectorConfig;
        this.pulsarAuth = pulsarAuth;
        try {
            this.pulsarAdmin = pulsarConnectorConfig.getPulsarAdmin();
        } catch (PulsarClientException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session) {
        List<String> trinoSchemas = new LinkedList<>();
        try (PulsarAdmin pulsarAdmin = PulsarAdminClientProvider.getPulsarAdmin(pulsarConnectorConfig)) {
            List<String> tenants = pulsarAdmin.tenants().getTenants();
            for (String tenant : tenants) {
                trinoSchemas.addAll(pulsarAdmin.namespaces().getNamespaces(tenant).stream().map(namespace ->
                        rewriteNamespaceDelimiterIfNeeded(namespace, pulsarConnectorConfig)).collect(Collectors.toList()));
            }
        } catch (PulsarAdminException e) {
            if (e.getStatusCode() == 401) {
                throw new TrinoException(QUERY_REJECTED, "Failed to get schemas from pulsar: Unauthorized");
            }
            throw new RuntimeException("Failed to get schemas from pulsar: "
                    + ExceptionUtils.getRootCause(e).getLocalizedMessage(), e);
        }
        return trinoSchemas;
    }

    @Override
    public PulsarTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName,
                                            Optional<ConnectorTableVersion> startVersion, Optional<ConnectorTableVersion> endVersion)
    {
        if (startVersion.isPresent() || endVersion.isPresent()) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support versioned tables");
        }

        TopicName topicName = getMatchedTopicName(tableName);
        checkTopicAuthorization(session, topicName.toString());
        return new PulsarTableHandle(
                this.connectorId,
                tableName.getSchemaName(),
                tableName.getTableName(),
                topicName.getLocalName());
    }
    /*private static String getDataFormat(Optional<PulsarTopicFieldGroup> fieldGroup)
    {
        return fieldGroup.map(PulsarTopicFieldGroup::dataFormat).orElse(DummyRowDecoder.NAME);
    }
    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(ConnectorSession session, ConnectorTableHandle table,
                                                            Constraint constraint,
                                                            Optional<Set<ColumnHandle>> desiredColumns) {

        PulsarTableHandle handle = convertTableHandle(table);
        ConnectorTableLayout layout = new ConnectorTableLayout(
                new PulsarTableLayoutHandle(handle, constraint.getSummary()));
        return ImmutableList.of(new ConnectorTableLayoutResult(layout, constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle) {
        return new ConnectorTableLayout(handle);
    }*/

    @Override
    public Optional<ConnectorTableLayout> getLayoutForTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle)
    {
        return new ConnectorTableLayout(tableExecuteHandle);
    }
    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table) {
        ConnectorTableMetadata connectorTableMetadata;
        PulsarTableHandle pulsarTableHandle = (PulsarTableHandle) table;
        SchemaTableName schemaTableName = new SchemaTableName(pulsarTableHandle.getSchemaName(), pulsarTableHandle.getTableName());// convertTableHandle(table).toSchemaTableName();
        connectorTableMetadata = getTableMetadata(session, schemaTableName, true);
        if (connectorTableMetadata == null) {
            ImmutableList.Builder<ColumnMetadata> builder = ImmutableList.builder();
            connectorTableMetadata = new ConnectorTableMetadata(schemaTableName, builder.build());
        }
        return connectorTableMetadata;
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName) {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();

        if (schemaName.isPresent()) {
            String schemaNameOrNull = schemaName.get();

            if (schemaNameOrNull.equals(INFORMATION_SCHEMA)) {
                // no-op for now but add pulsar connector specific system tables here
            } else {
                List<String> pulsarTopicList = null;
                try (PulsarAdmin pulsarAdmin = PulsarAdminClientProvider.getPulsarAdmin(pulsarConnectorConfig)){
                    pulsarTopicList = pulsarAdmin.topics()
                            .getList(restoreNamespaceDelimiterIfNeeded(schemaNameOrNull, pulsarConnectorConfig),
                                    TopicDomain.persistent);
                } catch (PulsarAdminException e) {
                    if (e.getStatusCode() == 404) {
                        log.warn("Schema " + schemaNameOrNull + " does not exsit");
                        return builder.build();
                    } else if (e.getStatusCode() == 401) {
                        throw new TrinoException(QUERY_REJECTED,
                                String.format("Failed to get tables/topics in %s: Unauthorized", schemaNameOrNull));
                    }
                    throw new RuntimeException("Failed to get tables/topics in " + schemaNameOrNull + ": "
                            + ExceptionUtils.getRootCause(e).getLocalizedMessage(), e);
                }
                if (pulsarTopicList != null) {
                    pulsarTopicList.stream()
                            .map(topic -> TopicName.get(topic).getPartitionedTopicName())
                            .distinct()
                            .forEach(topic -> builder.add(new SchemaTableName(schemaNameOrNull,
                                    TopicName.get(topic).getLocalName())));
                }
            }
        }
        return builder.build();
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle) {
        PulsarTableHandle pulsarTableHandle = (PulsarTableHandle)tableHandle;

        ConnectorTableMetadata tableMetaData = getTableMetadata(session, pulsarTableHandle.toSchemaTableName(), false);
        if (tableMetaData == null) {
            return new HashMap<>();
        }

        ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();

        tableMetaData.getColumns().forEach(columnMetadata -> {

            PulsarColumnMetadata pulsarColumnMetadata = (PulsarColumnMetadata) columnMetadata;

            PulsarColumnHandle pulsarColumnHandle = new PulsarColumnHandle(
                    connectorId,
                    pulsarColumnMetadata.getNameWithCase(),
                    pulsarColumnMetadata.getType(),
                    pulsarColumnMetadata.isHidden(),
                    pulsarColumnMetadata.isInternal(),
                    pulsarColumnMetadata.getDecoderExtraInfo().getMapping(),
                    pulsarColumnMetadata.getDecoderExtraInfo().getDataFormat(),
                    pulsarColumnMetadata.getDecoderExtraInfo().getFormatHint(),
                    pulsarColumnMetadata.getHandleKeyValueType());

            columnHandles.put(
                    columnMetadata.getName(),
                    pulsarColumnHandle);
        });

        PulsarInternalColumn.getInternalFields().forEach(pulsarInternalColumn -> {
            PulsarColumnHandle pulsarColumnHandle = pulsarInternalColumn.getColumnHandle(connectorId, false);
            columnHandles.put(pulsarColumnHandle.getName(), pulsarColumnHandle);
        });

        return columnHandles.build();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle
            columnHandle) {
        PulsarTableHandle handle = (PulsarTableHandle)tableHandle;//convertTableHandle(tableHandle);
        return ((PulsarTableHandle)columnHandle).getColumnMetadata();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix
            prefix) {

        requireNonNull(prefix, "prefix is null");

        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();

        List<SchemaTableName> tableNames;
        if (!prefix.getTable().isPresent()) {
            tableNames = listTables(session, prefix.getSchema());
        } else {
            tableNames = ImmutableList.of(new SchemaTableName(prefix.getSchema().get(), prefix.getTable().get()));
        }

        for (SchemaTableName tableName : tableNames) {
            ConnectorTableMetadata connectorTableMetadata = getTableMetadata(session, tableName, true);
            if (connectorTableMetadata != null) {
                columns.put(tableName, connectorTableMetadata.getColumns());
            }
        }

        return columns.build();
    }
    @Override
    public Iterator<RelationColumnsMetadata> streamRelationColumns(
            ConnectorSession session,
            Optional<String> schemaName,
            UnaryOperator<Set<SchemaTableName>> relationFilter)
    {
        Map<SchemaTableName, RelationColumnsMetadata> relationColumns = new HashMap<>();

        for (SchemaTableName tableName : listTables(session, schemaName)) {
            try {
                relationColumns.put(tableName, forTable(tableName, getColumnsMetadata(tableName.getTableName())));
            }
            catch (TableNotFoundException e) {
                // table disappeared during listing operation
            }
        }

        return relationFilter.apply(relationColumns.keySet()).stream()
                .map(relationColumns::get)
                .iterator();
    }

    @Override
    public void cleanupQuery(ConnectorSession session) {
        if (pulsarConnectorConfig.getAuthorizationEnabled()) {
            pulsarAuth.cleanSession(session);
        }
    }

    private ConnectorTableMetadata getTableMetadata(ConnectorSession session, SchemaTableName schemaTableName,
                                                    boolean withInternalColumns) {

        if (schemaTableName.getSchemaName().equals(INFORMATION_SCHEMA)) {
            return null;
        }

        TopicName topicName = getMatchedTopicName(schemaTableName);

        checkTopicAuthorization(session, topicName.toString());

        SchemaInfo schemaInfo;
        try (PulsarAdmin pulsarAdmin = PulsarAdminClientProvider.getPulsarAdmin(pulsarConnectorConfig))  {
            schemaInfo = pulsarAdmin.schemas().getSchemaInfo(topicName.getSchemaName());
        } catch (PulsarAdminException e) {
            if (e.getStatusCode() == 404) {
                // use default schema because there is no schema
                schemaInfo = PulsarSqlSchemaInfoProvider.defaultSchema();

            } else if (e.getStatusCode() == 401) {
                throw new TrinoException(QUERY_REJECTED,
                        String.format("Failed to get pulsar topic schema information for topic %s: Unauthorized",
                                topicName));
            } else {
                throw new TrinoException(PULSAR_ADMIN_ERROR,"Failed to get pulsar topic schema information for topic "
                        + topicName + ": " + ExceptionUtils.getRootCause(e).getLocalizedMessage(), e);
            }
        }
        catch (PulsarClientException e) {
            throw new TrinoException(PULSAR_ADMIN_ERROR, "fail to create pulsar admin client", e);
        }
        List<ColumnMetadata> handles = getPulsarColumns(
                topicName, schemaInfo, withInternalColumns, PulsarColumnHandle.HandleKeyValueType.NONE
        );


        return new ConnectorTableMetadata(schemaTableName, handles);
    }

    /**
     * Convert pulsar schema into presto table metadata.
     */
    @VisibleForTesting
    public List<ColumnMetadata> getPulsarColumns(TopicName topicName,
                                                 SchemaInfo schemaInfo,
                                                 boolean withInternalColumns,
                                                 PulsarColumnHandle.HandleKeyValueType handleKeyValueType) {
        SchemaType schemaType = schemaInfo.getType();
        if (schemaType.isStruct() || schemaType.isPrimitive()) {
            return getPulsarColumnsFromSchema(topicName, schemaInfo, withInternalColumns, handleKeyValueType);
        } else if (schemaType.equals(SchemaType.KEY_VALUE)) {
            return getPulsarColumnsFromKeyValueSchema(topicName, schemaInfo, withInternalColumns);
        } else {
            throw new IllegalArgumentException("Unsupported schema : " + schemaInfo);
        }
    }

    List<ColumnMetadata> getPulsarColumnsFromSchema(TopicName topicName,
                                                    SchemaInfo schemaInfo,
                                                    boolean withInternalColumns,
                                                    PulsarColumnHandle.HandleKeyValueType handleKeyValueType) {
        ImmutableList.Builder<ColumnMetadata> builder = ImmutableList.builder();
        builder.addAll(decoderFactory.extractColumnMetadata(topicName, schemaInfo, handleKeyValueType));
        if (withInternalColumns) {
            PulsarInternalColumn.getInternalFields()
                    .stream()
                    .forEach(pulsarInternalColumn -> builder.add(pulsarInternalColumn.getColumnMetadata(false)));
        }
        return builder.build();
    }

    List<ColumnMetadata> getPulsarColumnsFromKeyValueSchema(TopicName topicName,
                                                            SchemaInfo schemaInfo,
                                                            boolean withInternalColumns) {
        ImmutableList.Builder<ColumnMetadata> builder = ImmutableList.builder();
        KeyValue<SchemaInfo, SchemaInfo> kvSchemaInfo = KeyValueSchemaInfo.decodeKeyValueSchemaInfo(schemaInfo);
        SchemaInfo keySchemaInfo = kvSchemaInfo.getKey();
        List<ColumnMetadata> keyColumnMetadataList = getPulsarColumns(topicName, keySchemaInfo, false,
                PulsarColumnHandle.HandleKeyValueType.KEY);
        builder.addAll(keyColumnMetadataList);

        SchemaInfo valueSchemaInfo = kvSchemaInfo.getValue();
        List<ColumnMetadata> valueColumnMetadataList = getPulsarColumns(topicName, valueSchemaInfo, false,
                PulsarColumnHandle.HandleKeyValueType.VALUE);
        builder.addAll(valueColumnMetadataList);

        if (withInternalColumns) {
            PulsarInternalColumn.getInternalFields()
                    .forEach(pulsarInternalColumn -> builder.add(pulsarInternalColumn.getColumnMetadata(false)));
        }
        return builder.build();
    }

    private TopicName getMatchedTopicName(SchemaTableName schemaTableName) {
        String namespace = restoreNamespaceDelimiterIfNeeded(schemaTableName.getSchemaName(), pulsarConnectorConfig);

        Set<String> topicsSetWithoutPartition = null;
        try (PulsarAdmin pulsarAdmin = PulsarAdminClientProvider.getPulsarAdmin(pulsarConnectorConfig)) {
            List<String> allTopics = pulsarAdmin.topics().getList(namespace, TopicDomain.persistent);
            topicsSetWithoutPartition = allTopics.stream()
                    .map(t -> t.split(TopicName.PARTITIONED_TOPIC_SUFFIX)[0])
                    .collect(Collectors.toSet());
        }
        catch (PulsarAdminException e) {
            if (e.getStatusCode() == 404) {
                throw new TrinoException(NOT_FOUND, "Schema " + namespace + " does not exist", e);
            }
            else if (e.getStatusCode() == 401) {
                throw new TrinoException(PULSAR_ADMIN_ERROR, format("fail to get topics in schema %s: Unauthorized", namespace), e);
            }
            throw new TrinoException(PULSAR_ADMIN_ERROR, format("fail to get topics in schema %s", namespace), e);
        }
        catch (PulsarClientException e) {
            throw new TrinoException(PULSAR_ADMIN_ERROR, "fail to create pulsar admin client", e);
        }

        List<String> matchedTopics = topicsSetWithoutPartition.stream()
                .filter(t -> TopicName.get(t).getLocalName().equalsIgnoreCase(schemaTableName.getTableName()))
                .collect(Collectors.toList());

        if (matchedTopics.size() == 0) {
            return null;
        }
        else if (matchedTopics.size() != 1) {
            String errMsg = format("There are multiple topics %s matched the table name %s",
                    matchedTopics.toString(), format("%s/%s", namespace, schemaTableName.getTableName()));
            throw new TableNotFoundException(schemaTableName, errMsg);
        }
        if (log.isDebugEnabled()) {
            log.debug("matched topic %s for table %s ", matchedTopics.get(0), schemaTableName);
        }
        return TopicName.get(matchedTopics.get(0));
    }

    private TopicName getMatchedPulsarTopic(SchemaTableName schemaTableName) {
        String namespace = restoreNamespaceDelimiterIfNeeded(schemaTableName.getSchemaName(), pulsarConnectorConfig);

        Set<String> topicsSetWithoutPartition;
        try {
            List<String> allTopics = this.pulsarAdmin.topics().getList(namespace, TopicDomain.persistent);
            topicsSetWithoutPartition = allTopics.stream()
                    .map(t -> t.split(TopicName.PARTITIONED_TOPIC_SUFFIX)[0])
                    .collect(Collectors.toSet());
        } catch (PulsarAdminException e) {
            if (e.getStatusCode() == 404) {
                throw new TrinoException(NOT_FOUND, "Schema " + namespace + " does not exist");
            } else if (e.getStatusCode() == 401) {
                throw new TrinoException(QUERY_REJECTED,
                        String.format("Failed to get topics in schema %s: Unauthorized", namespace));
            }
            throw new RuntimeException("Failed to get topics in schema " + namespace
                    + ": " + ExceptionUtils.getRootCause(e).getLocalizedMessage(), e);
        }

        List<String> matchedTopics = topicsSetWithoutPartition.stream()
                .filter(t -> TopicName.get(t).getLocalName().equalsIgnoreCase(schemaTableName.getTableName()))
                .collect(Collectors.toList());

        if (matchedTopics.size() == 0) {
            log.error("Table %s not found", String.format("%s/%s", namespace, schemaTableName.getTableName()));
            throw new TableNotFoundException(schemaTableName);
        } else if (matchedTopics.size() != 1) {
            String errMsg = String.format("There are multiple topics %s matched the table name %s",
                    matchedTopics.toString(),
                    String.format("%s/%s", namespace, schemaTableName.getTableName()));
            log.error(errMsg);
            throw new TableNotFoundException(schemaTableName, errMsg);
        }
        log.info("matched topic %s for table %s ", matchedTopics.get(0), schemaTableName);
        return TopicName.get(matchedTopics.get(0));
    }

    void checkTopicAuthorization(ConnectorSession session, String topic) {
        if (!pulsarConnectorConfig.getAuthorizationEnabled()) {
            return;
        }
        pulsarAuth.checkTopicAuth(session, topic);
    }

}
