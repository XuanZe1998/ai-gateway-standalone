package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 视频生成任务留档仓库（表 ai_video_task）。
 * 提供按网关任务号/上游任务号的精确查询、内部追溯用分页查询，
 * 以及第二阶段查询链路的终态结算更新（状态抢占/计费回写）。
 */
@Repository
public interface VideoTaskRepository extends JpaRepository<VideoTaskEntity, Long> {

    /** 按网关任务号查询未删除的留档记录（对外任务 ID 唯一） */
    Optional<VideoTaskEntity> findByTaskNoAndDeletedFalse(String taskNo);

    /** 按上游任务 ID 反查网关留档（同一上游任务理论上可能多次提交，故返回列表） */
    List<VideoTaskEntity> findByUpstreamTaskIdAndDeletedFalse(String upstreamTaskId);

    /**
     * 内部追溯分页查询（所有条件均可选，传 null 则不参与过滤）。
     *
     * @param userAccount 用户账号（精确匹配）
     * @param modelName   模型名称（精确匹配）
     * @param status      任务状态（精确匹配）
     * @param start       提交时间起（含）
     * @param end         提交时间止（含）
     * @param pageable    分页参数
     */
    @Query("SELECT t FROM VideoTaskEntity t WHERE t.deleted = false " +
           "AND (:userAccount IS NULL OR t.userAccount = :userAccount) " +
           "AND (:modelName IS NULL OR t.modelName = :modelName) " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:start IS NULL OR t.submittedAt >= :start) " +
           "AND (:end IS NULL OR t.submittedAt <= :end) " +
           "ORDER BY t.submittedAt DESC")
    Page<VideoTaskEntity> searchWithFilters(
            @Param("userAccount") String userAccount,
            @Param("modelName") String modelName,
            @Param("status") String status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    /**
     * 终态抢占：仅当当前状态仍为非终态时才置为终态并回写追溯字段。
     *
     * DB 串行化保证并发轮询下只有一个调用方抢到（返回 1），
     * 抢到的调用方才有权执行 succeeded 计费，防止重复结算。
     *
     * @return 受影响行数：1 抢占成功 / 0 已被其他调用置终态
     */
    @Transactional
    @Modifying
    @Query("UPDATE VideoTaskEntity t SET t.status = :status, " +
           "t.errorCode = :errorCode, t.errorMessage = :errorMessage, " +
           "t.responseSnapshot = :responseSnapshot, t.completedAt = :completedAt " +
           "WHERE t.id = :id " +
           "AND t.status NOT IN ('succeeded', 'failed', 'expired', 'cancelled')")
    int claimTerminal(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage,
                      @Param("responseSnapshot") String responseSnapshot,
                      @Param("completedAt") LocalDateTime completedAt);

    /**
     * 计费回写：把 succeeded 结算产生的 ai_billing_record 主键关联到留档。
     * WHERE billing_record_id IS NULL 作为幂等双保险，防止重复关联。
     *
     * @return 受影响行数：1 回写成功 / 0 已关联过
     */
    @Transactional
    @Modifying
    @Query("UPDATE VideoTaskEntity t SET t.billingRecordId = :billingRecordId " +
           "WHERE t.id = :id AND t.billingRecordId IS NULL")
    int attachBillingRecord(@Param("id") Long id, @Param("billingRecordId") Long billingRecordId);

    /**
     * 中间态刷新：queued/running 等非终态同步到留档（供内部追溯）。
     * 同样限非终态条件，防止中间态覆盖已结算的终态。
     *
     * @return 受影响行数
     */
    @Transactional
    @Modifying
    @Query("UPDATE VideoTaskEntity t SET t.status = :status " +
           "WHERE t.id = :id " +
           "AND t.status NOT IN ('succeeded', 'failed', 'expired', 'cancelled')")
    int updateRunningStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 后台轮询用：查询非终态且提交时间早于指定时间的任务（按提交时间升序，最旧的优先）。
     * 排除 deleted，防止已逻辑删除的任务被轮询。
     */
    @Query("SELECT t FROM VideoTaskEntity t WHERE t.deleted = false " +
           "AND t.status NOT IN ('succeeded', 'failed', 'expired', 'cancelled') " +
           "AND t.submittedAt < :before " +
           "ORDER BY t.submittedAt ASC")
    List<VideoTaskEntity> findNonTerminalTasks(@Param("before") LocalDateTime before,
                                               Pageable pageable);
}
