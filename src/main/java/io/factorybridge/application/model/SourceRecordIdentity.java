package io.factorybridge.application.model;

import java.util.Objects;

/** import 請求預期的來源識別；與 raw 一起保存，讓重跑仍能核對原始取得條件。 */
public record SourceRecordIdentity(String sourceSystem, String sourceRecordId) {
    public SourceRecordIdentity {
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
    }
}
