package io.factorybridge.adapter.web;

final class PayloadTooLargeException extends RuntimeException {
    PayloadTooLargeException() {
        super("Measurement payload exceeds the 64 KiB limit.");
    }
}
