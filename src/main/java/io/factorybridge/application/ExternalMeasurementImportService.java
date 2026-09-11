package io.factorybridge.application;

import io.factorybridge.application.model.IngestionResult;
import io.factorybridge.application.port.ExternalDataClient;
import io.factorybridge.domain.*;
import java.util.Locale;

public final class ExternalMeasurementImportService {
    private final ExternalDataClient externalDataClient;
    private final MeasurementIngestionService ingestionService;

    public ExternalMeasurementImportService(
            ExternalDataClient externalDataClient, MeasurementIngestionService ingestionService) {
        this.externalDataClient = externalDataClient;
        this.ingestionService = ingestionService;
    }

    public IngestionResult importMeasurement(
            String sourceSystem, String sourceRecordId, String correlationId) {
        String source = sourceSystem.strip().toUpperCase(Locale.ROOT);
        String recordId = sourceRecordId.strip();
        String rawPayload = externalDataClient.fetchMeasurement(source, recordId);
        return ingestionService.ingestFetchedMeasurement(
                rawPayload, correlationId, source, recordId);
    }
}
