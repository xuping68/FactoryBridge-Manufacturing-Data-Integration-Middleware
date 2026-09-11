package io.factorybridge.adapter.persistence;

import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 統一 operational DB 交易與錯誤邊界，包含提交當下才發生的錯誤。 */
@Component
class PersistenceTransactions {
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate independentTransaction;
    private final TransactionTemplate readTransaction;

    PersistenceTransactions(PlatformTransactionManager transactionManager) {
        writeTransaction = new TransactionTemplate(transactionManager);
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
    }

    <T> T write(Supplier<T> operation) {
        return withinTransaction(writeTransaction, operation);
    }

    <T> T writeIndependently(Supplier<T> operation) {
        return withinTransaction(independentTransaction, operation);
    }

    <T> T read(Supplier<T> operation) {
        return withinTransaction(readTransaction, operation);
    }

    private <T> T withinTransaction(TransactionTemplate transaction, Supplier<T> operation) {
        try {
            return transaction.execute(status -> operation.get());
        } catch (DataAccessException | TransactionException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "The operational data store could not complete the request.",
                    exception);
        }
    }
}
