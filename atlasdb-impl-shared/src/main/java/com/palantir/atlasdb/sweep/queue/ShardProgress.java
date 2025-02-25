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
package com.palantir.atlasdb.sweep.queue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetException;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetRequest;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.atlasdb.schema.generated.SweepShardProgressTable;
import com.palantir.atlasdb.schema.generated.TargetedSweepTableFactory;
import com.palantir.common.streams.KeyedStream;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.util.PersistableBoolean;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ShardProgress {
    private static final SafeLogger log = SafeLoggerFactory.get(ShardProgress.class);
    static final TableReference TABLE_REF =
            TargetedSweepTableFactory.of().getSweepShardProgressTable(null).getTableRef();

    private static final int SHARD_COUNT_INDEX = -1;
    static final ShardAndStrategy SHARD_COUNT_SAS = ShardAndStrategy.conservative(SHARD_COUNT_INDEX);

    // The constant value is NEVER to be re-used.
    private static final int _UNUSED_OLDEST_SEEN_INDEX = -2;

    private static final int LAST_SEEN_COMMIT_TS_INDEX = -3;
    private static final ShardAndStrategy LAST_SEEN_COMMIT_TIMESTAMP =
            ShardAndStrategy.conservative(LAST_SEEN_COMMIT_TS_INDEX);

    private final KeyValueService kvs;

    public ShardProgress(KeyValueService kvs) {
        this.kvs = kvs;
    }

    /**
     * Returns the persisted number of shards for the sweep queue.
     */
    public int getNumberOfShards() {
        return maybeGet(SHARD_COUNT_SAS)
                .map(Long::intValue)
                .orElse(AtlasDbConstants.LEGACY_DEFAULT_TARGETED_SWEEP_SHARDS);
    }

    /**
     * Updates the persisted number of shards to newNumber, if newNumber is greater than the currently persisted number
     * of shards.
     *
     * @param newNumber the desired new number of shards
     * @return the latest known persisted number of shards, which may be greater than newNumber
     */
    public int updateNumberOfShards(int newNumber) {
        Preconditions.checkArgument(newNumber <= AtlasDbConstants.MAX_SWEEP_QUEUE_SHARDS);
        return (int) increaseValueFromToAtLeast(SHARD_COUNT_SAS, getNumberOfShards(), newNumber);
    }

    /**
     * Returns the last swept timestamp for the given shard and strategy.
     */
    public long getLastSweptTimestamp(ShardAndStrategy shardAndStrategy) {
        return maybeGet(shardAndStrategy).orElse(SweepQueueUtils.INITIAL_TIMESTAMP);
    }

    /**
     * Returns the last swept timestamps for the given set of shard and strategy.
     */
    public Map<ShardAndStrategy, Long> getLastSweptTimestamps(Set<ShardAndStrategy> shardAndStrategies) {
        Map<Cell, Value> lastSweptEntries = getEntries(shardAndStrategies);
        return KeyedStream.of(shardAndStrategies)
                .map(shardAndStrategy -> Optional.ofNullable(lastSweptEntries.get(cellForShard(shardAndStrategy)))
                        .map(ShardProgress::hydrateValue)
                        .orElse(SweepQueueUtils.INITIAL_TIMESTAMP))
                .collectToMap();
    }

    /**
     * Updates the persisted last swept timestamp for the given shard and strategy to timestamp if it is greater than
     * the currently persisted last swept timestamp.
     *
     * @param shardAndStrategy shard and strategy to update for
     * @param timestamp timestamp to update to
     * @return the latest known persisted sweep timestamp for the shard and strategy
     */
    public long updateLastSweptTimestamp(ShardAndStrategy shardAndStrategy, long timestamp) {
        return increaseValueFromToAtLeast(shardAndStrategy, getLastSweptTimestamp(shardAndStrategy), timestamp);
    }

    /**
     * Updates the persisted last seen commitTimestamp for the given shard to timestamp if it is greater than
     * the currently persisted last seen commitTimestamp.
     * Note that this is only done for Conservative sweep strategy.
     *
     * @param shardAndStrategy shard and strategy to update for
     * @param commitTimestamp commit timestamp to update to
     */
    public void updateLastSeenCommitTimestamp(ShardAndStrategy shardAndStrategy, long commitTimestamp) {
        tryUpdateLastSeenCommitTimestamp(shardAndStrategy, commitTimestamp);
    }

    public Optional<Long> getLastSeenCommitTimestamp() {
        return maybeGet(LAST_SEEN_COMMIT_TIMESTAMP);
    }

    private void tryUpdateLastSeenCommitTimestamp(ShardAndStrategy shardAndStrategy, long lastSeenCommitTs) {
        if (!shardAndStrategy.isConservative()) {
            return;
        }

        Optional<Long> previous = getLastSeenCommitTimestamp();
        boolean updateNeeded =
                previous.map(persisted -> persisted < lastSeenCommitTs).orElse(true);
        while (updateNeeded) {
            byte[] colValNew = createColumnValue(lastSeenCommitTs);
            CheckAndSetRequest casRequest = createRequest(
                    LAST_SEEN_COMMIT_TIMESTAMP, previous.orElse(SweepQueueUtils.INITIAL_TIMESTAMP), colValNew);
            try {
                kvs.checkAndSet(casRequest);
                updateNeeded = false;
            } catch (CheckAndSetException exception) {
                Optional<Long> current = getLastSeenCommitTimestamp();
                if (current.equals(previous)) {
                    log.warn(
                            "Failed to update last seen commit timestamp. Values before and after CAS match.",
                            SafeArg.of("previous", previous),
                            SafeArg.of("current", current),
                            SafeArg.of("last seen", lastSeenCommitTs),
                            exception);
                    throw exception;
                }
                previous = current;
                updateNeeded =
                        previous.map(persisted -> persisted < lastSeenCommitTs).orElse(true);
            }
        }
    }

    private Optional<Long> maybeGet(ShardAndStrategy shardAndStrategy) {
        Map<Cell, Value> result = getEntry(shardAndStrategy);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hydrateValue(result));
    }

    private Map<Cell, Value> getEntry(ShardAndStrategy shardAndStrategy) {
        return getEntries(ImmutableSet.of(shardAndStrategy));
    }

    private Map<Cell, Value> getEntries(Set<ShardAndStrategy> shardAndStrategies) {
        Map<Cell, Long> queryMap = KeyedStream.of(shardAndStrategies)
                .mapEntries((shardAndStrategy, _unused) ->
                        Maps.immutableEntry(cellForShard(shardAndStrategy), SweepQueueUtils.READ_TS))
                .collectToMap();
        return kvs.get(TABLE_REF, queryMap);
    }

    private static Cell cellForShard(ShardAndStrategy shardAndStrategy) {
        SweepShardProgressTable.SweepShardProgressRow row = SweepShardProgressTable.SweepShardProgressRow.of(
                shardAndStrategy.shard(),
                PersistableBoolean.of(shardAndStrategy.isConservative()).persistToBytes());
        return Cell.create(
                row.persistToBytes(), SweepShardProgressTable.SweepShardProgressNamedColumn.VALUE.getShortName());
    }

    private static long hydrateValue(Value val) {
        return SweepShardProgressTable.Value.BYTES_HYDRATOR
                .hydrateFromBytes(val.getContents())
                .getValue();
    }

    private static long hydrateValue(Map<Cell, Value> entry) {
        return hydrateValue(Iterables.getOnlyElement(entry.values()));
    }

    private long increaseValueFromToAtLeast(ShardAndStrategy shardAndStrategy, long oldVal, long newVal) {
        byte[] colValNew = createColumnValue(newVal);

        long currentValue = oldVal;
        while (currentValue < newVal) {
            CheckAndSetRequest casRequest = createRequest(shardAndStrategy, currentValue, colValNew);
            try {
                kvs.checkAndSet(casRequest);
                return newVal;
            } catch (CheckAndSetException e) {
                log.info(
                        "Failed to check and set from expected old value {} to new value {}. Retrying if the old "
                                + "value changed under us.",
                        SafeArg.of("old value", currentValue),
                        SafeArg.of("new value", newVal),
                        e);
                currentValue = rethrowIfUnchanged(shardAndStrategy, currentValue, e);
            }
        }
        return currentValue;
    }

    public void resetProgressForShard(ShardAndStrategy shardAndStrategy) {
        // TODO (jkong): This is a bit crappy. Really we want this to be INITIAL_TIMESTAMP, but that doesn't
        //  serialise because we require an unsigned integer.
        byte[] colValZero = createColumnValue(SweepQueueUtils.RESET_TIMESTAMP);

        long currentValue = getLastSweptTimestamp(shardAndStrategy);
        while (currentValue > SweepQueueUtils.RESET_TIMESTAMP) {
            CheckAndSetRequest casRequest = createRequest(shardAndStrategy, currentValue, colValZero);
            try {
                log.info(
                        "Attempting to reset targeted sweep progress for a shard.",
                        SafeArg.of("shardAndStrategy", shardAndStrategy));
                kvs.checkAndSet(casRequest);
                log.info(
                        "Reset targeted sweep progress for a shard.", SafeArg.of("shardAndStrategy", shardAndStrategy));
            } catch (CheckAndSetException e) {
                log.info(
                        "Failed to reset targeted sweep progress for a shard; trying again if someone changed it "
                                + "(unless they also reset it).",
                        SafeArg.of("shardAndStrategy", shardAndStrategy),
                        e);
                currentValue = rethrowIfUnchanged(shardAndStrategy, currentValue, e);
            }
        }
    }

    static byte[] createColumnValue(long newVal) {
        return SweepShardProgressTable.Value.of(newVal).persistValue();
    }

    private long rethrowIfUnchanged(ShardAndStrategy shardStrategy, long oldVal, CheckAndSetException ex) {
        long updatedOldVal = hydrateValue(getEntry(shardStrategy));
        if (updatedOldVal == oldVal) {
            throw ex;
        }
        return updatedOldVal;
    }

    private CheckAndSetRequest createRequest(ShardAndStrategy shardAndStrategy, long oldVal, byte[] colValNew) {
        if (isDefaultValue(shardAndStrategy, oldVal)) {
            return maybeGet(shardAndStrategy)
                    .map(persistedValue -> createSingleCellRequest(shardAndStrategy, persistedValue, colValNew))
                    .orElseGet(() -> createNewCellRequest(shardAndStrategy, colValNew));
        } else {
            return createSingleCellRequest(shardAndStrategy, oldVal, colValNew);
        }
    }

    @SuppressWarnings("ImmutablesReferenceEquality")
    private static boolean isDefaultValue(ShardAndStrategy shardAndStrategy, long oldVal) {
        return SweepQueueUtils.firstSweep(oldVal)
                || (shardAndStrategy == SHARD_COUNT_SAS
                        && oldVal == AtlasDbConstants.LEGACY_DEFAULT_TARGETED_SWEEP_SHARDS);
    }

    static CheckAndSetRequest createNewCellRequest(ShardAndStrategy shardAndStrategy, byte[] colValNew) {
        return CheckAndSetRequest.newCell(TABLE_REF, cellForShard(shardAndStrategy), colValNew);
    }

    private static CheckAndSetRequest createSingleCellRequest(
            ShardAndStrategy shardAndStrategy, long oldVal, byte[] colValNew) {
        byte[] colValOld = createColumnValue(oldVal);
        return CheckAndSetRequest.singleCell(TABLE_REF, cellForShard(shardAndStrategy), colValOld, colValNew);
    }
}
