// 文件说明：BillingRecordRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BillingRecordRepository extends JpaRepository<BillingRecordEntity, Long> {

    List<BillingRecordEntity> findByUserId(String userId);

    List<BillingRecordEntity> findByModelName(String modelName);

    List<BillingRecordEntity> findByChannelId(String channelId);

    List<BillingRecordEntity> findByStartedAtBetween(LocalDateTime start, LocalDateTime end);

    List<BillingRecordEntity> findByStartedAtBetweenAndIsDeletedFalseOrderByStartedAtDesc(
            LocalDateTime start, LocalDateTime end);

    List<BillingRecordEntity> findByIsDeletedFalseOrderByStartedAtDesc(Pageable pageable);

    List<BillingRecordEntity> findByModelNameAndIsDeletedFalseOrderByStartedAtDesc(
            String modelName, Pageable pageable);

    @Query("SELECT COALESCE(SUM(b.totalCost), 0) FROM BillingRecordEntity b " +
           "WHERE b.isDeleted = false AND b.isSuccess = true AND b.startedAt BETWEEN :start AND :end")
    BigDecimal sumTotalCostByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(b.totalTokens), 0) FROM BillingRecordEntity b " +
           "WHERE b.isDeleted = false AND b.isSuccess = true AND b.startedAt BETWEEN :start AND :end")
    Long sumTotalTokensByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(b) FROM BillingRecordEntity b " +
           "WHERE b.isDeleted = false AND b.startedAt BETWEEN :start AND :end")
    Long countByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT b FROM BillingRecordEntity b WHERE b.isDeleted = false " +
           "AND (:userAccount IS NULL OR b.userAccount = :userAccount) " +
           "AND (:modelName IS NULL OR b.modelName = :modelName) " +
           "AND (:channelId IS NULL OR b.channelId = :channelId) " +
           "AND (:minCost IS NULL OR b.totalCost >= :minCost) " +
           "AND (:maxCost IS NULL OR b.totalCost <= :maxCost) " +
           "AND b.startedAt BETWEEN :start AND :end " +
           "ORDER BY b.startedAt DESC")
    List<BillingRecordEntity> searchWithFilters(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("userAccount") String userAccount,
            @Param("modelName") String modelName,
            @Param("channelId") String channelId,
            @Param("minCost") BigDecimal minCost,
            @Param("maxCost") BigDecimal maxCost);
}
