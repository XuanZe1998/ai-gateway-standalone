package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepartmentBillingSnapshotTest {
    private final ModelPricingService pricing=mock(ModelPricingService.class);
    private final BillingRecordRepository records=mock(BillingRecordRepository.class);
    private final BalanceDeductionService balances=mock(BalanceDeductionService.class);
    private final EnterpriseLookupService accounts=mock(EnterpriseLookupService.class);
    private final UserUsagePolicyService policies=mock(UserUsagePolicyService.class);
    private final DepartmentBudgetService budgets=mock(DepartmentBudgetService.class);
    private BillingService service;
    @BeforeEach void setUp(){
        var discounts=mock(DiscountCalculationService.class);
        when(discounts.calculate(any(),any(),any())).thenReturn(new DiscountBreakdown(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE));
        when(pricing.getPrice("model",null)).thenReturn(new ModelPricingService.ModelPricing("model",null,new BigDecimal("0.001"),new BigDecimal("0.002")));
        when(records.save(any())).thenAnswer(call->{BillingRecordEntity bill=call.getArgument(0);bill.setId(10L);return bill;});
        service=new BillingService(pricing,records,balances,accounts,mock(PlatformSystemUserRepository.class),discounts,null,new ObjectMapper(),policies);
        service.setDepartmentBudgets(budgets);
        when(budgets.encode(any())).thenReturn("snapshot");
    }
    private DepartmentUsageSnapshot snapshot(boolean free,String funding){return new DepartmentUsageSnapshot("request-1","code:D1","原部门",funding,free,"DEPARTMENT",7,"original-account",2,DepartmentBudgetService.month(),10000L,null,1000L,"2026-10-19T00:00:00");}
    private BillingService.BillingContext context(DepartmentUsageSnapshot snapshot){return BillingService.BillingContext.create().userId("u1").modelName("model").serviceType("chat").platformUser(true)
            .promptTokens(100L).completionTokens(10L).totalTokens(110L).accountId("changed-account").accountType(2).usageSnapshot(snapshot);}
    @Test void freeGrantAtAdmissionSurvivesRevocationWithoutChargingOrConsultingLivePolicy(){
        service.recordBillingSync(context(snapshot(true,"DEPARTMENT")));
        var bill=ArgumentCaptor.forClass(BillingRecordEntity.class);verify(records).save(bill.capture());
        assertTrue(bill.getValue().getIsAdminFreeUsage());assertEquals(0,bill.getValue().getTotalCost().signum());
        assertEquals(0,new BigDecimal("0.12000000").compareTo(bill.getValue().getUsageValue()));
        assertEquals("code:D1",bill.getValue().getDepartmentId());assertEquals("EXEMPT",bill.getValue().getSettlementStatus());
        verifyNoInteractions(policies,balances,accounts);
    }
    @Test void paidSnapshotUsesOriginalAccountEvenAfterMembershipAndPolicyChanges(){
        when(accounts.lookupByAccount("original-account",2)).thenReturn(Optional.of(new EnterpriseLookupService.EnterpriseInfo()));
        service.recordBillingSync(context(snapshot(false,"PERSONAL")));
        verify(balances).deductBillingAndAlert(eq(10L),eq("original-account"),eq(2),eq(new BigDecimal("0.12000000")),any());
        verifyNoInteractions(policies);verify(accounts,never()).lookupByAccount("changed-account",2);
    }
    @Test void repeatedSharedCallbacksReuseSameBillAndNeverChargePersonalAccount(){
        var snapshot=snapshot(false,"DEPARTMENT");
        var existing=BillingRecordEntity.builder().id(20L).totalTokens(110L).totalCost(BigDecimal.ONE).build();
        when(records.findByBillingRequestId(snapshot.requestId())).thenReturn(Optional.of(existing));
        assertEquals(20L,service.recordBillingSync(context(snapshot)));assertEquals(20L,service.recordBillingSync(context(snapshot)));
        verify(records,never()).save(any());verify(budgets,times(2)).settle(existing,snapshot);verifyNoInteractions(balances,accounts,policies);
    }
}
