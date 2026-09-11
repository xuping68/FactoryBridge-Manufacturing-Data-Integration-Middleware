package io.factorybridge.application.port;

public interface ExternalDataClient {
    String fetchMeasurement(String sourceSystem, String sourceRecordId);
}
