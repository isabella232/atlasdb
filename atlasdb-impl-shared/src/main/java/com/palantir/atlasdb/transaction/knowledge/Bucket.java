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

package com.palantir.atlasdb.transaction.knowledge;

import com.palantir.atlasdb.AtlasDbConstants;
import org.immutables.value.Value;

@Value.Immutable
public interface Bucket {
    @Value.Parameter
    long value();

    static Bucket ofIndex(long value) {
        return ImmutableBucket.of(value);
    }

    default long getMaxTsInCurrentBucket() {
        return ((value() + 1) * AtlasDbConstants.ABORTED_TIMESTAMPS_BUCKET_SIZE) - 1;
    }

    static Bucket forTimestamp(long startTimestamp) {
        return ofIndex(startTimestamp / AtlasDbConstants.ABORTED_TIMESTAMPS_BUCKET_SIZE);
    }

    default long getMinTsInBucket() {
        return value() * AtlasDbConstants.ABORTED_TIMESTAMPS_BUCKET_SIZE;
    }
}
