package com.omnibank.txstream.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StreamingTransactionRepository
        extends JpaRepository<StreamingTransactionEntity, UUID> {

    @Query("SELECT t FROM StreamingTransactionEntity t " +
           "WHERE t.sourceAccount = :acct OR t.destinationAccount = :acct " +
           "ORDER BY t.initiatedAt DESC")
    List<StreamingTransactionEntity> findRecentForAccount(@Param("acct") String acct);

    @Query("SELECT t FROM StreamingTransactionEntity t " +
           "WHERE t.initiatedAt >= :since " +
           "ORDER BY t.initiatedAt DESC")
    List<StreamingTransactionEntity> findSince(@Param("since") Instant since);
}
