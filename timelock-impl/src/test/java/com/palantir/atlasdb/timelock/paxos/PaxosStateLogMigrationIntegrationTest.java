/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.timelock.paxos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palantir.atlasdb.util.MetricsManagers;
import com.palantir.common.persist.Persistable;
import com.palantir.paxos.Client;
import com.palantir.paxos.PaxosAcceptor;
import com.palantir.paxos.PaxosAcceptorState;
import com.palantir.paxos.PaxosLearner;
import com.palantir.paxos.PaxosProposalId;
import com.palantir.paxos.PaxosStateLog;
import com.palantir.paxos.PaxosStateLogImpl;
import com.palantir.paxos.PaxosStateLogMigrator;
import com.palantir.paxos.PaxosStorageParameters;
import com.palantir.paxos.PaxosValue;
import com.palantir.paxos.SqliteConnections;
import com.palantir.paxos.SqlitePaxosStateLog;
import com.palantir.paxos.Versionable;
import com.palantir.sls.versions.OrderableSlsVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import javax.sql.DataSource;
import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PaxosStateLogMigrationIntegrationTest {
    @Parameterized.Parameters(name = "Async migration completed {0}")
    public static Collection<Object[]> succeeded() {
        return Arrays.asList(new Object[][] {{true}, {false}});
    }

    private static final Client CLIENT = Client.of("test");
    private static final PaxosUseCase useCase = PaxosUseCase.LEADER_FOR_ALL_CLIENTS;
    private static final long LATEST_ROUND_BEFORE_MIGRATING = 100;
    private static final long CUTOFF = LATEST_ROUND_BEFORE_MIGRATING - PaxosStateLogMigrator.SAFETY_BUFFER;
    private static final long ROUND_BEFORE_CUTOFF = CUTOFF - 1;

    private final boolean asyncMigrationCompleted;
    private final DeterministicScheduler executor = new DeterministicScheduler();

    private Path legacyDirectory;
    private DataSource sqlite;
    private PaxosStateLog<PaxosValue> fileBasedLearnerLog;

    @Rule
    public final TemporaryFolder TEMPORARY_FOLDER = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        legacyDirectory = TEMPORARY_FOLDER.newFolder("legacy").toPath();
        sqlite = SqliteConnections.getDefaultConfiguredPooledDataSource(
                TEMPORARY_FOLDER.newFolder("sqlite").toPath());
        fileBasedLearnerLog = createFileSystemLearnerLog(CLIENT);
    }

    public PaxosStateLogMigrationIntegrationTest(boolean asyncMigrationCompleted) {
        this.asyncMigrationCompleted = asyncMigrationCompleted;
    }

    @Test
    public void canMigrateWithEmptyLegacy() {
        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();

        PaxosLearner learner = paxosComponents.learner(CLIENT);
        assertThat(learner.getLearnedValue(0L)).isEmpty();
        assertThat(learner.getGreatestLearnedValue()).isEmpty();

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
    }

    @Test
    public void learnerMigratesLogStateFromLatestIncludingBuffer() throws IOException {
        fileBasedLearnerLog.writeRound(ROUND_BEFORE_CUTOFF, valueForRound(ROUND_BEFORE_CUTOFF));
        fileBasedLearnerLog.writeRound(CUTOFF, valueForRound(CUTOFF));
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        PaxosLearner learner = paxosComponents.learner(CLIENT);

        PaxosStateLog<PaxosValue> sqliteLog = createSqliteLog(paxosComponents.getLearnerParameters(CLIENT));
        assertValuePresent(LATEST_ROUND_BEFORE_MIGRATING, sqliteLog);
        assertValuePresent(CUTOFF, sqliteLog);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, sqliteLog);

        assertValueLearned(LATEST_ROUND_BEFORE_MIGRATING, learner);
        assertValueLearned(CUTOFF, learner);
        assertValueLearned(ROUND_BEFORE_CUTOFF, learner);

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    public void legacyLogIsTheSourceOfTruthForValuesBelowCutoff() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        PaxosLearner learner = paxosComponents.learner(CLIENT);

        fileBasedLearnerLog.writeRound(CUTOFF, valueForRound(CUTOFF));
        fileBasedLearnerLog.writeRound(ROUND_BEFORE_CUTOFF, valueForRound(ROUND_BEFORE_CUTOFF));

        PaxosStateLog<PaxosValue> sqliteLog = createSqliteLog(paxosComponents.getLearnerParameters(CLIENT));
        assertValueAbsent(CUTOFF, sqliteLog);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, sqliteLog);

        assertValueNotLearned(CUTOFF, learner);
        assertValueLearned(ROUND_BEFORE_CUTOFF, learner);

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    public void currentLogIsTheSourceOfTruthForValuesAboveCutoff() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        paxosComponents.learner(CLIENT);

        PaxosStateLog<PaxosValue> sqliteLog = createSqliteLog(paxosComponents.getLearnerParameters(CLIENT));
        sqliteLog.writeRound(CUTOFF, valueForRound(CUTOFF));
        sqliteLog.writeRound(ROUND_BEFORE_CUTOFF, valueForRound(ROUND_BEFORE_CUTOFF));

        assertValueAbsent(CUTOFF, fileBasedLearnerLog);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, fileBasedLearnerLog);

        PaxosLearner learner = paxosComponents.learner(CLIENT);
        assertValueLearned(CUTOFF, learner);
        assertValueNotLearned(ROUND_BEFORE_CUTOFF, learner);

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    public void learningValuesBeforeCutoffPersistsToLegacyLog() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        PaxosLearner learner = paxosComponents.learner(CLIENT);

        learner.learn(ROUND_BEFORE_CUTOFF, valueForRound(ROUND_BEFORE_CUTOFF));

        PaxosStateLog<PaxosValue> sqliteLog = createSqliteLog(paxosComponents.getLearnerParameters(CLIENT));
        assertValuePresent(ROUND_BEFORE_CUTOFF, fileBasedLearnerLog);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, sqliteLog);
        assertValueLearned(ROUND_BEFORE_CUTOFF, learner);

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(1L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
    }

    @Test
    public void noCrossClientPollution() {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        int otherRound = 200;
        Client otherClient = Client.of("other");
        PaxosStateLog<PaxosValue> otherFileBasedLog = createFileSystemLearnerLog(otherClient);
        otherFileBasedLog.writeRound(otherRound, valueForRound(otherRound));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        PaxosLearner learner = paxosComponents.learner(CLIENT);

        PaxosLearner otherLearner = paxosComponents.learner(otherClient);

        assertValueLearned(LATEST_ROUND_BEFORE_MIGRATING, learner);
        assertValueNotLearned(otherRound, learner);

        assertValueNotLearned(LATEST_ROUND_BEFORE_MIGRATING, otherLearner);
        assertValueLearned(otherRound, otherLearner);
    }

    @Test
    public void migrationCutoffForAcceptorBasedOnLearnerWhenEntriesPresent() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        long newRound = LATEST_ROUND_BEFORE_MIGRATING + 300;
        PaxosStateLog<PaxosAcceptorState> fileBasedAcceptorLog = createFileSystemAcceptorLog(CLIENT);
        fileBasedAcceptorLog.writeRound(ROUND_BEFORE_CUTOFF, stateForRound(ROUND_BEFORE_CUTOFF));
        fileBasedAcceptorLog.writeRound(CUTOFF, stateForRound(CUTOFF));
        fileBasedAcceptorLog.writeRound(newRound, stateForRound(newRound));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        paxosComponents.acceptor(CLIENT);

        PaxosStateLog<PaxosAcceptorState> sqliteLog = createSqliteLog(paxosComponents.getAcceptorParameters(CLIENT));
        assertStateAbsent(ROUND_BEFORE_CUTOFF, sqliteLog);
        assertStatePresent(CUTOFF, sqliteLog);
        assertStatePresent(newRound, sqliteLog);
    }

    @Test
    public void migrationCutoffForAcceptorIncludesAtLeastOneEntry() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        PaxosStateLog<PaxosAcceptorState> fileBasedAcceptorLog = createFileSystemAcceptorLog(CLIENT);
        fileBasedAcceptorLog.writeRound(ROUND_BEFORE_CUTOFF, stateForRound(ROUND_BEFORE_CUTOFF));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        paxosComponents.acceptor(CLIENT);

        PaxosStateLog<PaxosAcceptorState> sqliteLog = createSqliteLog(paxosComponents.getAcceptorParameters(CLIENT));
        assertStatePresent(ROUND_BEFORE_CUTOFF, sqliteLog);
    }

    @Test
    public void failWhenOldLogWritesAtGreaterSequenceAfterMigrationAlreadyRan() {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsAndMaybeMigrate();
        paxosComponents.learner(CLIENT);

        long newRound = LATEST_ROUND_BEFORE_MIGRATING + 3;
        fileBasedLearnerLog.writeRound(newRound, valueForRound(newRound));

        LocalPaxosComponents brokenComponents = createPaxosComponentsAndMaybeMigrate();
        assertThatThrownBy(() -> brokenComponents.learner(CLIENT))
                .as("Written to file based log at greater sequence after migration already ran")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void learnerMigratesLogStateWhenValidationDisabledAndTruncates() throws IOException {
        fileBasedLearnerLog.writeRound(ROUND_BEFORE_CUTOFF, valueForRound(ROUND_BEFORE_CUTOFF));
        fileBasedLearnerLog.writeRound(CUTOFF, valueForRound(CUTOFF));
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsSkipValidationAndTruncate();
        PaxosLearner learner = paxosComponents.learner(CLIENT);

        PaxosStateLog<PaxosValue> sqliteLog = createSqliteLog(paxosComponents.getLearnerParameters(CLIENT));
        assertValuePresent(LATEST_ROUND_BEFORE_MIGRATING, sqliteLog);
        assertValuePresent(CUTOFF, sqliteLog);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, sqliteLog);

        assertValueLearned(LATEST_ROUND_BEFORE_MIGRATING, learner);
        assertValueLearned(CUTOFF, learner);
        assertValueNotLearned(ROUND_BEFORE_CUTOFF, learner);

        assertThat(fileBasedLearnerLog.getGreatestLogEntry()).isEqualTo(PaxosAcceptor.NO_LOG_ENTRY);
        assertValueAbsent(ROUND_BEFORE_CUTOFF, fileBasedLearnerLog);
        assertValueAbsent(CUTOFF, fileBasedLearnerLog);
        assertValueAbsent(LATEST_ROUND_BEFORE_MIGRATING, fileBasedLearnerLog);

        assertThat(paxosComponents.getWriteCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
        assertThat(paxosComponents.getReadCounter(PaxosLearner.class).getCount())
                .isEqualTo(0L);
    }

    @Test
    public void doNotFailWhenOldLogWritesAtGreaterSequenceAfterMigrationAlreadyRanAndTruncate() throws IOException {
        fileBasedLearnerLog.writeRound(LATEST_ROUND_BEFORE_MIGRATING, valueForRound(LATEST_ROUND_BEFORE_MIGRATING));

        LocalPaxosComponents paxosComponents = createPaxosComponentsSkipValidationAndTruncate();
        paxosComponents.learner(CLIENT);

        long newRound = LATEST_ROUND_BEFORE_MIGRATING + 3;
        fileBasedLearnerLog.writeRound(newRound, valueForRound(newRound));

        LocalPaxosComponents newComponents = createPaxosComponentsSkipValidationAndTruncate();
        PaxosLearner learner = newComponents.learner(CLIENT);

        assertThat(fileBasedLearnerLog.getGreatestLogEntry()).isEqualTo(PaxosAcceptor.NO_LOG_ENTRY);
        assertValueAbsent(LATEST_ROUND_BEFORE_MIGRATING, fileBasedLearnerLog);
        assertValueAbsent(newRound, fileBasedLearnerLog);

        assertValueLearned(LATEST_ROUND_BEFORE_MIGRATING, learner);
        assertValueNotLearned(newRound, learner);
    }

    private void assertValueLearned(long round, PaxosLearner learner) {
        assertThat(learner.getLearnedValue(round)).hasValue(valueForRound(round));
    }

    private void assertValueNotLearned(long round, PaxosLearner learner) {
        assertThat(learner.getLearnedValue(round)).isEmpty();
    }

    private void assertValuePresent(long round, PaxosStateLog<PaxosValue> log) throws IOException {
        assertThat(PaxosValue.BYTES_HYDRATOR.hydrateFromBytes(log.readRound(round)))
                .isEqualTo(valueForRound(round));
    }

    private void assertValueAbsent(long round, PaxosStateLog<PaxosValue> log) throws IOException {
        assertThat(log.readRound(round)).isNull();
    }

    private void assertStatePresent(long round, PaxosStateLog<PaxosAcceptorState> log) throws IOException {
        assertThat(log.readRound(round)).isNotNull();
    }

    private void assertStateAbsent(long round, PaxosStateLog<PaxosAcceptorState> log) throws IOException {
        assertThat(log.readRound(round)).isNull();
    }

    private LocalPaxosComponents createPaxosComponentsAndMaybeMigrate() {
        return createPaxosComponents(false, asyncMigrationCompleted);
    }

    private LocalPaxosComponents createPaxosComponentsSkipValidationAndTruncate() {
        return createPaxosComponents(true, asyncMigrationCompleted);
    }

    private LocalPaxosComponents createPaxosComponents(boolean skipValidationAndTruncate, boolean ranAsync) {
        LocalPaxosComponents components = LocalPaxosComponents.createWithAsyncMigration(
                TimelockPaxosMetrics.of(useCase, MetricsManagers.createForTests()),
                useCase,
                legacyDirectory,
                sqlite,
                UUID.randomUUID(),
                true,
                OrderableSlsVersion.valueOf("0.0.0"),
                skipValidationAndTruncate,
                executor);
        if (ranAsync) {
            executor.runUntilIdle();
        }
        return components;
    }

    private PaxosValue valueForRound(long num) {
        return new PaxosValue("value", num, new byte[] {1});
    }

    private PaxosAcceptorState stateForRound(long num) {
        return PaxosAcceptorState.newState(new PaxosProposalId(num, "ID"));
    }

    private PaxosStateLog<PaxosValue> createFileSystemLearnerLog(Client client) {
        Path dir = useCase.logDirectoryRelativeToDataDirectory(legacyDirectory).resolve(client.value());
        String learnerLogDir =
                dir.resolve(PaxosTimeLockConstants.LEARNER_SUBDIRECTORY_PATH).toString();
        return new PaxosStateLogImpl<>(learnerLogDir);
    }

    private PaxosStateLog<PaxosAcceptorState> createFileSystemAcceptorLog(Client client) {
        Path dir = useCase.logDirectoryRelativeToDataDirectory(legacyDirectory).resolve(client.value());
        String learnerLogDir =
                dir.resolve(PaxosTimeLockConstants.ACCEPTOR_SUBDIRECTORY_PATH).toString();
        return new PaxosStateLogImpl<>(learnerLogDir);
    }

    private <T extends Persistable & Versionable> PaxosStateLog<T> createSqliteLog(PaxosStorageParameters parameters) {
        return SqlitePaxosStateLog.create(parameters.namespaceAndUseCase(), parameters.sqliteDataSource());
    }
}
