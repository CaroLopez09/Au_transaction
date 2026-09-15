package com.example.autransactional.application.platform;

import com.example.autransactional.application.account.DepositView;
import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.account.VirtualAccountView;
import com.example.autransactional.application.tenant.OnboardingView;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.application.tenant.UboView;
import com.example.autransactional.application.treasury.PayoutView;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.UboRepository;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Consola de operaciones y cumplimiento de AU (arquitectura §2.2): todas las organizaciones, de
 * solo lectura, mas "Actualizar desde el proveedor". Cada consulta a una ficha queda en la
 * bitacora: ver datos de otra empresa es un acceso que tiene que poder rendirse cuentas.
 */
@Service
public class PlatformConsoleService {

    /** Una verificacion que lleva mas de esto en revision merece seguimiento humano. */
    static final Duration STALE_REVIEW = Duration.ofDays(2);
    /** Plazo de RFI a partir del cual se considera "proximo a vencer". */
    static final Duration RFI_DUE_SOON = Duration.ofDays(3);

    private final TenantRepository tenants;
    private final UboRepository ubos;
    private final VirtualAccountRepository accounts;
    private final PayoutRepository payouts;
    private final DepositRepository deposits;
    private final RfiRepository rfis;
    private final SubmitOnboardingService onboarding;
    private final OpenVirtualAccountService accountService;
    private final AuditTrail audit;

    public PlatformConsoleService(TenantRepository tenants, UboRepository ubos, VirtualAccountRepository accounts,
                                  PayoutRepository payouts, DepositRepository deposits, RfiRepository rfis,
                                  SubmitOnboardingService onboarding, OpenVirtualAccountService accountService,
                                  AuditTrail audit) {
        this.tenants = tenants;
        this.ubos = ubos;
        this.accounts = accounts;
        this.payouts = payouts;
        this.deposits = deposits;
        this.rfis = rfis;
        this.onboarding = onboarding;
        this.accountService = accountService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TenantSummary> tenants(AuthenticatedOperator operator) {
        assertPlatform(operator);
        Instant now = Instant.now();
        return tenants.findAll().stream()
                .map(t -> summarize(t, now))
                .sorted(Comparator.comparing(TenantSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public Tenant360 tenant(AuthenticatedOperator operator, String tenantId) {
        assertPlatform(operator);
        Tenant tenant = load(tenantId);
        Instant now = Instant.now();
        audit.record(operator, "platform.tenant_viewed", "tenant", tenant.getId().value(), null, "OK", null);
        return new Tenant360(
                summarize(tenant, now),
                OnboardingView.from(tenant),
                UboView.Roster.from(ubos.rosterOf(tenant.getId())),
                accounts.findByTenant(tenant.getId()).stream().map(VirtualAccountView::from).toList(),
                payouts.findByTenant(tenant.getId(), 20).stream().map(PayoutView::from).toList(),
                deposits.findByTenant(tenant.getId(), 20).stream().map(DepositView::from).toList(),
                rfis.findByTenant(tenant.getId()).stream().map(r -> RfiSummary.from(r, now)).toList());
    }

    /** Relee en Kira la empresa y sus cuentas: el mismo trabajo que los workers, a demanda. */
    @Transactional
    public Tenant360 refresh(AuthenticatedOperator operator, String tenantId) {
        assertPlatform(operator);
        Tenant tenant = load(tenantId);
        onboarding.reconcile(tenant.getId());
        for (VirtualAccount account : accounts.findByTenant(tenant.getId())) {
            accountService.reconcile(account);
        }
        audit.record(operator, "platform.tenant_refreshed", "tenant", tenant.getId().value(), null, "OK", null);
        return tenant(operator, tenantId);
    }

    /** Lo que pide atencion humana en todas las organizaciones, lo mas grave primero. */
    @Transactional(readOnly = true)
    public List<ReviewItem> reviewQueue(AuthenticatedOperator operator) {
        assertPlatform(operator);
        Instant now = Instant.now();
        List<ReviewItem> items = new ArrayList<>();
        for (Tenant t : tenants.findAll()) {
            String name = t.getName();
            String id = t.getId().value();
            if (t.getStatus() == TenantStatus.REJECTED) {
                items.add(new ReviewItem(id, name, "onboarding.rejected", "critical",
                        "Vinculacion no aprobada", t.getRejectionReason(), t.getUpdatedAt()));
            } else if (t.getStatus() == TenantStatus.REVIEW) {
                boolean stale = t.getUpdatedAt() != null && t.getUpdatedAt().isBefore(now.minus(STALE_REVIEW));
                items.add(new ReviewItem(id, name, "onboarding.review", stale ? "critical" : "attention",
                        stale ? "En revision manual desde hace mas de 2 dias" : "En revision manual", null,
                        t.getUpdatedAt()));
            } else if (t.isVerified() && !t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS)) {
                items.add(new ReviewItem(id, name, "onboarding.product_pending", "attention",
                        "Verificada pero sin producto habilitado", null, t.getUpdatedAt()));
            }
            for (VirtualAccount account : accounts.findByTenant(t.getId())) {
                if (account.isActivationDelayed(now)) {
                    items.add(new ReviewItem(id, name, "account.activation_delayed", "attention",
                            "Cuenta virtual con activacion demorada", account.getId(), account.getCreatedAt()));
                }
            }
            for (Rfi rfi : rfis.findOpenByTenant(t.getId())) {
                if (rfi.isOverdue(now)) {
                    items.add(new ReviewItem(id, name, "rfi.overdue", "critical", "Solicitud de informacion vencida",
                            rfi.getKiraRfiId(), rfi.getDueDate()));
                } else if (rfi.getDueDate() != null && rfi.getDueDate().isBefore(now.plus(RFI_DUE_SOON))) {
                    items.add(new ReviewItem(id, name, "rfi.due_soon", "attention",
                            "Solicitud de informacion por vencer", rfi.getKiraRfiId(), rfi.getDueDate()));
                }
            }
            for (Payout payout : payouts.findByTenant(t.getId(), 100)) {
                if (payout.getStatus() == PayoutStatus.KYT_PENDING || payout.getStatus() == PayoutStatus.IN_REVIEW) {
                    items.add(new ReviewItem(id, name, "payout.held", "attention", "Pago retenido por el proveedor",
                            payout.getId(), payout.getUpdatedAt()));
                }
            }
        }
        items.sort(Comparator.comparing((ReviewItem i) -> "critical".equals(i.severity()) ? 0 : 1)
                .thenComparing(ReviewItem::since, Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }

    private TenantSummary summarize(Tenant t, Instant now) {
        List<Rfi> abiertos = rfis.findOpenByTenant(t.getId());
        List<Payout> recientes = payouts.findByTenant(t.getId(), 100);
        return new TenantSummary(
                t.getId().value(),
                t.getName(),
                t.getKiraUserId(),
                t.getStatus().name(),
                t.isVerificationTriggered(),
                t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS),
                t.getMissingFields().forProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS).size(),
                t.getRejectionReason(),
                ubos.findByTenant(t.getId()).size(),
                accounts.findByTenant(t.getId()).size(),
                abiertos.size(),
                (int) abiertos.stream().filter(r -> r.isOverdue(now)).count(),
                (int) recientes.stream().filter(p -> p.getStatus() == PayoutStatus.KYT_PENDING
                        || p.getStatus() == PayoutStatus.IN_REVIEW).count(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private Tenant load(String tenantId) {
        return tenants.findById(TenantId.of(tenantId))
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
    }

    private static void assertPlatform(AuthenticatedOperator operator) {
        if (!operator.role().isPlatform()) {
            throw new DomainException("Solo el equipo de operaciones de AU accede a la consola.");
        }
    }

    public record TenantSummary(String id, String name, String kiraUserId, String status,
                                boolean verificationTriggered, boolean readyForVirtualAccounts,
                                int pendingFields, String rejectionReason, int beneficialOwners,
                                int virtualAccounts, int openRfis, int overdueRfis, int heldPayouts,
                                Instant createdAt, Instant updatedAt) {
    }

    public record RfiSummary(String id, String kiraRfiId, String status, String resolutionReason, Instant dueDate,
                             boolean overdue, String blockingType) {

        static RfiSummary from(Rfi r, Instant now) {
            return new RfiSummary(r.getId(), r.getKiraRfiId(), r.getStatus().name(), r.getResolutionReason(),
                    r.getDueDate(), r.isOverdue(now), r.getBlockingType());
        }
    }

    public record Tenant360(TenantSummary summary, OnboardingView onboarding, UboView.Roster beneficialOwners,
                            List<VirtualAccountView> accounts, List<PayoutView> payouts,
                            List<DepositView> deposits, List<RfiSummary> rfis) {
    }

    public record ReviewItem(String tenantId, String tenantName, String kind, String severity, String title,
                             String detail, Instant since) {
    }
}
