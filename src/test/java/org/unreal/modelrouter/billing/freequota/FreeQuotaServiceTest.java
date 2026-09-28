// 文件说明：测试 FreeQuotaServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.billing.freequota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FreeQuotaServiceTest {

    @Mock
    private FreeQuotaRepository repository;

    @Mock
    private FreeQuotaProperties properties;

    @InjectMocks
    private FreeQuotaService service;

    @Test
    void shouldHitFreeQuota_whenEnoughRemaining() {
        FreeQuotaEntity quota = quotaOf(1000L, 500L);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isFreeQuotaServiceType("chat")).thenReturn(true);
        when(repository.findByUserIdAndDeletedFalse("u1")).thenReturn(Optional.of(quota));
        when(repository.deductQuota("u1", 300L, "system", 0L)).thenReturn(1);

        FreeQuotaResult result = service.deductStreamingQuota("u1", "chat", 300L);

        assertThat(result.hitFreeQuota()).isTrue();
        assertThat(result.deductedTokens()).isEqualTo(300L);
    }

    @Test
    void shouldAcceptOverageAndExhaust_whenTotalTokensExceedRemaining() {
        FreeQuotaEntity quota = quotaOf(1000L, 500L);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isFreeQuotaServiceType("chat")).thenReturn(true);
        when(repository.findByUserIdAndDeletedFalse("u1")).thenReturn(Optional.of(quota));
        when(repository.deductAndExhaust(eq("u1"), eq(500L), any(), eq("system"), eq(0L))).thenReturn(1);

        FreeQuotaResult result = service.deductStreamingQuota("u1", "chat", 600L);

        assertThat(result.hitFreeQuota()).isTrue();
        assertThat(result.deductedTokens()).isEqualTo(500L);
        assertThat(result.remainingAfter()).isEqualTo(0L);
        verify(repository).deductAndExhaust(eq("u1"), eq(500L), any(), eq("system"), eq(0L));
    }

    @Test
    void shouldMiss_whenTrialExhausted() {
        FreeQuotaEntity quota = quotaOf(1000L, 500L);
        quota.setTrialExhausted(true);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isFreeQuotaServiceType("chat")).thenReturn(true);
        when(repository.findByUserIdAndDeletedFalse("u1")).thenReturn(Optional.of(quota));

        FreeQuotaResult result = service.deductStreamingQuota("u1", "chat", 300L);

        assertThat(result.hitFreeQuota()).isFalse();
        verify(repository, never()).deductQuota(any(), anyLong(), any(), anyLong());
        verify(repository, never()).deductAndExhaust(any(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void shouldProvisionTeacherQuotaIdempotently() {
        FreeQuotaEntity quota = quotaOf(5_000_000L, 0L);
        quota.setQuotaTier("TEACHER");
        when(properties.isEnabled()).thenReturn(true);
        when(properties.resolvePolicy(List.of("TEACHER")))
                .thenReturn(new FreeQuotaProperties.QuotaPolicy("TEACHER", 5_000_000L));
        when(repository.findByUserIdAndDeletedFalse("1001"))
                .thenReturn(Optional.empty(), Optional.of(quota));
        when(repository.createIfMissing(eq("1001"), eq(5_000_000L), eq("TEACHER"),
                any(), any(), eq("system"))).thenReturn(1);

        FreeQuotaEntity result = service.ensureQuota("1001", List.of("TEACHER"));

        assertThat(result.getQuotaTier()).isEqualTo("TEACHER");
        assertThat(result.getTotalQuota()).isEqualTo(5_000_000L);
        verify(repository).createIfMissing(eq("1001"), eq(5_000_000L), eq("TEACHER"),
                any(), any(), eq("system"));
        verify(repository).updateQuotaPolicy("1001", 5_000_000L, "TEACHER", "system");
    }

    @Test
    void shouldReconcileQuotaPolicy_whenCampusRoleChanges() {
        FreeQuotaEntity quota = quotaOf(5_000_000L, 100L);
        quota.setQuotaTier("TEACHER");
        when(properties.isEnabled()).thenReturn(true);
        when(properties.resolvePolicy(List.of("TEACHER")))
                .thenReturn(new FreeQuotaProperties.QuotaPolicy("TEACHER", 5_000_000L));
        when(repository.findByUserIdAndDeletedFalse("1001")).thenReturn(Optional.of(quota));

        FreeQuotaEntity result = service.ensureQuota("1001", List.of("TEACHER"));

        assertThat(result).isSameAs(quota);
        verify(repository).updateQuotaPolicy("1001", 5_000_000L, "TEACHER", "system");
        verify(repository, never()).createIfMissing(any(), anyLong(), any(), any(), any(), any());
    }

    private FreeQuotaEntity quotaOf(long total, long used) {
        FreeQuotaEntity q = new FreeQuotaEntity();
        q.setUserId("u1");
        q.setTotalQuota(total);
        q.setUsedQuota(used);
        q.setRemainingQuota(total - used);
        q.setTrialExhausted(false);
        q.setVersion(0L);
        q.setDeleted(false);
        return q;
    }
}
