/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
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
package com.palantir.atlasdb.keyvalue.dbkvs.impl;

import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.dbkvs.DbKeyValueServiceConfig;
import com.palantir.atlasdb.keyvalue.dbkvs.DbKeyValueServiceRuntimeConfig;
import com.palantir.atlasdb.keyvalue.impl.ForwardingKeyValueService;
import com.palantir.atlasdb.spi.KeyValueServiceRuntimeConfig;
import com.palantir.atlasdb.spi.LocalConnectionConfig;
import com.palantir.nexus.db.monitoring.timer.SqlTimer;
import com.palantir.nexus.db.monitoring.timer.SqlTimers;
import com.palantir.nexus.db.pool.ConnectionManager;
import com.palantir.nexus.db.pool.HikariClientPoolConnectionManagers;
import com.palantir.nexus.db.pool.ReentrantManagedConnectionSupplier;
import com.palantir.nexus.db.sql.ConnectionBackedSqlConnectionImpl;
import com.palantir.nexus.db.sql.SQL;
import com.palantir.nexus.db.sql.SqlConnection;
import com.palantir.nexus.db.sql.SqlConnectionHelper;
import com.palantir.refreshable.Refreshable;
import java.sql.Connection;
import java.util.Optional;
import java.util.function.Supplier;

// This class should be removed and replaced by DbKvs when InDbTimestampStore depends directly on DbKvs
public final class ConnectionManagerAwareDbKvs extends ForwardingKeyValueService {
    private final DbKeyValueService kvs;
    private final ConnectionManager connManager;
    private final SqlConnectionSupplier sqlConnectionSupplier;

    /**
     * @deprecated This method does not support live reloading the DB password. Use
     * {@link #create(DbKeyValueServiceConfig, Refreshable, boolean)} instead.
     */
    @Deprecated
    public static ConnectionManagerAwareDbKvs create(DbKeyValueServiceConfig config) {
        return create(config, AtlasDbConstants.DEFAULT_INITIALIZE_ASYNC);
    }

    /**
     * @deprecated This method does not support live reloading the DB password. Use
     * {@link #create(DbKeyValueServiceConfig, Refreshable, boolean)} instead.
     */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester")
    public static ConnectionManagerAwareDbKvs create(DbKeyValueServiceConfig config, boolean initializeAsync) {
        return create(config, Refreshable.only(Optional.empty()), initializeAsync);
    }

    public static ConnectionManagerAwareDbKvs create(
            DbKeyValueServiceConfig config,
            Refreshable<Optional<KeyValueServiceRuntimeConfig>> runtimeConfig,
            boolean initializeAsync) {
        ConnectionManager connManager;
        if (config.sharedResourcesConfig().isPresent()) {
            LocalConnectionConfig localConnectionConfig =
                    config.sharedResourcesConfig().get().connectionConfig();
            connManager = HikariClientPoolConnectionManagers.createShared(
                    config.connection(),
                    localConnectionConfig.poolSize(),
                    localConnectionConfig.acquireFromSharedPoolTimeoutInSeconds());
        } else {
            connManager = HikariClientPoolConnectionManagers.create(config.connection());
        }
        runtimeConfig.subscribe(newRuntimeConfig -> updateConnManagerConfig(connManager, config, newRuntimeConfig));
        ReentrantManagedConnectionSupplier connSupplier = new ReentrantManagedConnectionSupplier(connManager);
        SqlConnectionSupplier sqlConnSupplier = getSimpleTimedSqlConnectionSupplier(connSupplier);
        return new ConnectionManagerAwareDbKvs(
                DbKvs.create(config, sqlConnSupplier, initializeAsync), connManager, sqlConnSupplier);
    }

    private static void updateConnManagerConfig(
            ConnectionManager connManager,
            DbKeyValueServiceConfig config,
            Optional<KeyValueServiceRuntimeConfig> runtimeConfig) {
        if (runtimeConfig.isPresent() && runtimeConfig.get() instanceof DbKeyValueServiceRuntimeConfig) {
            DbKeyValueServiceRuntimeConfig dbRuntimeConfig = (DbKeyValueServiceRuntimeConfig) runtimeConfig.get();
            connManager.setPassword(dbRuntimeConfig.getDbPassword().unmasked());
        } else {
            // no runtime config (or wrong type), use the password from the install config
            connManager.setPassword(config.connection().getDbPassword().unmasked());
        }
    }

    private static SqlConnectionSupplier getSimpleTimedSqlConnectionSupplier(
            ReentrantManagedConnectionSupplier connectionSupplier) {
        Supplier<Connection> supplier = connectionSupplier;
        SQL sql = new SQL() {
            @Override
            protected SqlConfig getSqlConfig() {
                return new SqlConfig() {
                    @Override
                    public boolean isSqlCancellationDisabled() {
                        return false;
                    }

                    protected Iterable<SqlTimer> getSqlTimers() {
                        return ImmutableList.of(SqlTimers.createDurationSqlTimer(), SqlTimers.createSqlStatsSqlTimer());
                    }

                    @Override
                    public SqlTimer getSqlTimer() {
                        return SqlTimers.createCombinedSqlTimer(getSqlTimers());
                    }
                };
            }
        };

        return new SqlConnectionSupplier() {
            @Override
            public SqlConnection get() {
                return new ConnectionBackedSqlConnectionImpl(
                        supplier.get(),
                        () -> {
                            throw new UnsupportedOperationException(
                                    "This SQL connection does not provide reliable timestamp.");
                        },
                        new SqlConnectionHelper(sql));
            }

            @Override
            public void close() {
                connectionSupplier.close();
            }
        };
    }

    private ConnectionManagerAwareDbKvs(
            DbKeyValueService kvs, ConnectionManager connManager, SqlConnectionSupplier sqlConnectionSupplier) {
        this.kvs = kvs;
        this.connManager = connManager;
        this.sqlConnectionSupplier = sqlConnectionSupplier;
    }

    @Override
    public KeyValueService delegate() {
        return kvs;
    }

    public ConnectionManager getConnectionManager() {
        return connManager;
    }

    public SqlConnectionSupplier getSqlConnectionSupplier() {
        return sqlConnectionSupplier;
    }

    public String getTablePrefix() {
        return kvs.getTablePrefix();
    }
}
