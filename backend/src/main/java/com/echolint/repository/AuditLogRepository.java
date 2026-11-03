package com.echolint.repository;

import com.echolint.entity.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /** 数据保留清理（P2-30）：按创建时间查过期行 id，每次只取一页（≤500）， 由调用方逐批删除，避免一条大 DELETE 长时间持锁。 */
    @Query("select a.id from AuditLog a where a.createdAt < :cutoff order by a.id")
    List<Long> findIdsByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
