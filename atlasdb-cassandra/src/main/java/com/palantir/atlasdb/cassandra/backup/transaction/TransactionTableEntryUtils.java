/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.cassandra.backup.transaction;

import com.palantir.atlasdb.transaction.service.TransactionStatus;
import com.palantir.atlasdb.transaction.service.TransactionStatuses;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;

public final class TransactionTableEntryUtils {
    private TransactionTableEntryUtils() {}

    public static TransactionTableEntry fromStatus(long startTimestamp, TransactionStatus commitStatus) {
        return TransactionStatuses.caseOf(commitStatus)
                .committed(commitTs -> TransactionTableEntries.committedLegacy(startTimestamp, commitTs))
                .aborted(() -> TransactionTableEntries.explicitlyAborted(startTimestamp))
                .otherwise(() -> {
                    throw new SafeIllegalStateException(
                            "Illegal transaction status", SafeArg.of("status", commitStatus));
                });
    }
}
