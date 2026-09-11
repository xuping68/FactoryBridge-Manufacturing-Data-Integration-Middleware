package io.factorybridge.adapter.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StagingJpaRepository extends JpaRepository<StagingEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from StagingEntity record where record.id = :id")
    Optional<StagingEntity> findForAcceptance(@Param("id") UUID id);

    // 條件更新避免遲到的錯誤處理覆蓋已成功的收件結果。
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update StagingEntity record
            set record.status = 'REJECTED', record.errorCode = :errorCode, record.errorMessage = :message
            where record.id = :id and record.status = 'RECEIVED'
            """)
    int rejectReceivedRecord(
            @Param("id") UUID id,
            @Param("errorCode") String errorCode,
            @Param("message") String message);
}
