package com.example.autransactional.application.compliance;

import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.compliance.RfiStatus;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Solicitudes de informacion (RFI) de Kira: bandeja, sincronizacion y respuesta.
 *
 * Nunca se crea un RFI desde aqui. Kira lo genera, casi siempre para detener una
 * transferencia o un KYB en revision, y su reloj (due_at) no se prorroga: al vencer cierra
 * en not_resolved y lo bloqueado sigue bloqueado. Por eso la bandeja importa.
 *
 * Kira trata los RFIs como globales del integrador; el aislamiento por organizacion lo
 * imponemos aqui atribuyendo cada RFI a su empresa por user_id.
 */
@Service
public class AnswerRfiService {

    private static final Logger log = LoggerFactory.getLogger(AnswerRfiService.class);

    /** Tope de Kira por pagina. */
    private static final int PAGE_SIZE = 100;
    /** Corte de seguridad: 2.000 RFIs abiertos de una empresa no es un caso, es un bucle. */
    private static final int MAX_PAGES = 20;

    private final RfiRepository rfis;
    private final TenantRepository tenants;
    private final PayoutRepository payouts;
    private final KiraApiClient kira;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;

    public AnswerRfiService(RfiRepository rfis, TenantRepository tenants, PayoutRepository payouts,
                            KiraApiClient kira, AuditTrail audit, ObjectMapper objectMapper) {
        this.rfis = rfis;
        this.tenants = tenants;
        this.payouts = payouts;
        this.kira = kira;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RfiView> list(AuthenticatedOperator operator, boolean onlyOpen) {
        List<Rfi> found = onlyOpen
                ? rfis.findOpenByTenant(operator.tenantId())
                : rfis.findByTenant(operator.tenantId());
        return found.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public RfiView get(AuthenticatedOperator operator, String rfiId) {
        return toView(load(operator.tenantId(), rfiId));
    }

    /**
     * Trae de Kira los RFIs de la empresa y los asienta.
     *
     * Es la red de seguridad del webhook: rfi.* exige suscripcion explicita en Kira y, como
     * todo webhook, se entrega una sola vez.
     */
    @Transactional
    public List<RfiView> sync(AuthenticatedOperator operator) {
        assertCanManage(operator);
        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
        tenant.assertRegisteredInKira();

        int asentados = 0;
        int ajenos = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("user_id", tenant.getKiraUserId());
            query.put("limit", PAGE_SIZE);
            query.put("offset", page * PAGE_SIZE);

            List<JsonNode> entries = entries(kira.listRfis(query));
            for (JsonNode entry : entries) {
                // El filtro user_id es de Kira; la frontera entre empresas es nuestra.
                if (!tenant.getId().equals(ownerOf(entry).orElse(null))) {
                    ajenos++;
                    continue;
                }
                upsert(tenant.getId(), completeIfNeeded(entry));
                asentados++;
            }
            if (entries.size() < PAGE_SIZE) {
                break;
            }
        }
        if (ajenos > 0) {
            log.warn("Sincronizacion de RFIs de {}: {} entradas descartadas por no poder atribuirse a la empresa.",
                    tenant.getId().value(), ajenos);
        }

        audit.record(operator, "compliance.rfis_synced", "tenant", tenant.getId().value(), null, "OK",
                "rfis=" + asentados);
        return list(operator, false);
    }

    @Transactional
    public RfiView refresh(AuthenticatedOperator operator, String rfiId) {
        Rfi rfi = load(operator.tenantId(), rfiId);
        applyDetail(rfi, kira.getRfi(rfi.getKiraRfiId()));
        rfis.save(rfi);
        return toView(rfi);
    }

    /**
     * Responde items de texto con PATCH /v1/rfis/{id}/items.
     *
     * Se valida todo antes de llamar, y lo que Kira rechace se devuelve por item_id. Tras un
     * PATCH aceptado se relee el RFI: el estado del RFI (answered o sigue pending, si la
     * respuesta fue parcial) lo decide Kira, no la respuesta del PATCH.
     *
     * noRollbackFor: ante un 409 se asienta el cierre antes de avisar al operador.
     */
    @Transactional(noRollbackFor = DomainException.class)
    public RfiView answer(AuthenticatedOperator operator, String rfiId, RfiCommands.AnswerItems command) {
        assertCanManage(operator);
        Rfi rfi = load(operator.tenantId(), rfiId);
        rfi.assertAcceptsAnswers();

        Map<String, String> errors = validate(rfi, command);
        if (!errors.isEmpty()) {
            throw new RfiAnswerRejectedException("Hay respuestas invalidas; no se envio ninguna.", errors);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (RfiCommands.ItemAnswer answer : command.items()) {
            items.add(Map.of("item_id", answer.itemId(), "answer_value", answer.answerValue()));
        }

        try {
            kira.answerRfiItems(rfi.getKiraRfiId(), Map.of("items", items));
        } catch (KiraApiException e) {
            if (e.getStatusCode() == 409) {
                applyDetail(rfi, kira.getRfi(rfi.getKiraRfiId()));
                rfis.save(rfi);
                throw new DomainException("Kira ya cerro este RFI (" + rfi.getStatus()
                        + "); no admite respuestas.");
            }
            if (e.getStatusCode() == 422) {
                throw new RfiAnswerRejectedException(
                        "Kira rechazo las respuestas; no se guardo ninguna.", itemErrorsFrom(e));
            }
            throw e;
        }

        try {
            applyDetail(rfi, kira.getRfi(rfi.getKiraRfiId()));
        } catch (KiraApiException e) {
            // La respuesta ya quedo guardada en Kira: no se convierte en error para el operador.
            log.warn("RFI {} respondido pero no se pudo releer: {}. Pendiente de refresco.",
                    rfi.getKiraRfiId(), e.getMessage());
        }
        rfis.save(rfi);

        audit.record(operator, "compliance.rfi_answered", "rfi", rfi.getId(), null, "OK",
                "items=" + items.size() + " estado=" + rfi.getStatus());
        return toView(rfi);
    }

    /**
     * Proyeccion de la familia rfi.* de webhooks.
     *
     * Sin @Transactional a proposito: corre dentro de la transaccion del procesador de
     * webhooks, y un fallo al consultar Kira debe quedar como processing_error del evento,
     * no marcar la transaccion entera para rollback y perder la fila del evento.
     */
    public void applyWebhook(String kiraRfiId, String rawStatus) {
        if (kiraRfiId == null || kiraRfiId.isBlank()) {
            log.warn("Evento de RFI sin identificador: no se puede proyectar.");
            return;
        }
        Optional<Rfi> local = rfis.findByKiraRfiId(kiraRfiId);
        if (local.isPresent() && rawStatus != null) {
            local.get().applyRemoteStatus(RfiStatus.fromWire(rawStatus), null);
            rfis.save(local.get());
        }

        // El evento no trae items, plazo ni bloqueo: el detalle se lee siempre de Kira.
        JsonNode detail = unwrap(kira.getRfi(kiraRfiId));
        Optional<TenantId> owner = local.map(Rfi::getTenantId).or(() -> ownerOf(detail));
        if (owner.isEmpty()) {
            log.info("RFI {} sin empresa local a la que atribuirlo. Pendiente de reconciliacion.", kiraRfiId);
            return;
        }
        upsert(owner.get(), detail);
    }

    // ---------- Asentar lo que dice Kira ----------

    private void upsert(TenantId tenantId, JsonNode entry) {
        String kiraRfiId = kiraRfiIdOf(entry);
        if (kiraRfiId == null) {
            log.warn("Entrada de RFI sin rfi_id; se ignora.");
            return;
        }
        Rfi rfi = rfis.findByKiraRfiId(kiraRfiId)
                .orElseGet(() -> new Rfi(UUID.randomUUID().toString(), tenantId, kiraRfiId,
                        itemsOf(entry), null));
        if (!rfi.getTenantId().equals(tenantId)) {
            log.error("El RFI {} ya pertenece a otra organizacion; no se reasigna.", kiraRfiId);
            return;
        }
        applyDetail(rfi, entry);
        rfis.save(rfi);
    }

    private void applyDetail(Rfi rfi, JsonNode response) {
        JsonNode detail = unwrap(response);
        String status = text(detail, "status");
        rfi.applyRemoteStatus(status == null ? null : RfiStatus.fromWire(status),
                detail.has("items") ? itemsOf(detail) : null);
        rfi.describeDueDate(parseInstant(text(detail, "due_at")));
        JsonNode blocking = detail.path("blocking");
        rfi.describeBlocking(text(blocking, "type"), text(blocking, "transfer_uuid"));
    }

    /** La lista puede venir resumida; sin items no hay formulario que pintar. */
    private JsonNode completeIfNeeded(JsonNode entry) {
        return entry.has("items") ? entry : unwrap(kira.getRfi(kiraRfiIdOf(entry)));
    }

    /** Por user_id; si no viene, por el pago bloqueado, que si sabemos de quien es. */
    private Optional<TenantId> ownerOf(JsonNode entry) {
        String kiraUserId = text(entry, "user_id");
        if (kiraUserId != null) {
            return tenants.findByKiraUserId(kiraUserId).map(Tenant::getId);
        }
        String transfer = text(entry.path("blocking"), "transfer_uuid");
        if (transfer != null) {
            return payouts.findByKiraPayoutId(transfer).map(Payout::getTenantId);
        }
        return Optional.empty();
    }

    // ---------- Validacion ----------

    private Map<String, String> validate(Rfi rfi, RfiCommands.AnswerItems command) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode item : readItems(rfi)) {
            String itemId = text(item, "item_id");
            if (itemId != null) {
                byId.put(itemId, item);
            }
        }

        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (RfiCommands.ItemAnswer answer : command.items()) {
            String itemId = answer.itemId();
            JsonNode item = byId.get(itemId);
            if (!seen.add(itemId)) {
                errors.put(itemId, "El item aparece mas de una vez en la respuesta.");
            } else if (item == null) {
                errors.put(itemId, "El item no pertenece a este RFI.");
            } else if ("document".equalsIgnoreCase(text(item, "answer_type"))) {
                errors.put(itemId, "Este item se responde subiendo documentos, no con texto.");
            }
        }
        return errors;
    }

    /**
     * El 422 de un PATCH por lotes trae errors[] por item. La forma de error no es uniforme en
     * Kira, asi que se toleran message, error y code; sin detalle por item, va el mensaje general.
     */
    private Map<String, String> itemErrorsFrom(KiraApiException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        try {
            JsonNode root = e.getRawBody() == null ? null : objectMapper.readTree(e.getRawBody());
            JsonNode list = root == null ? null : root.has("errors") ? root.get("errors") : root.path("details");
            if (list != null && list.isArray()) {
                for (JsonNode err : list) {
                    String itemId = text(err, "item_id");
                    String message = firstNonNull(text(err, "message"), text(err, "error"), text(err, "code"));
                    if (itemId != null) {
                        errors.put(itemId, message == null ? "Respuesta invalida." : message);
                    }
                }
            }
        } catch (Exception ignored) {
            // Cuerpo ilegible: se queda el mensaje general.
        }
        if (errors.isEmpty()) {
            errors.put("rfi", e.getMessage());
        }
        return errors;
    }

    // ---------- Lectura ----------

    private RfiView toView(Rfi rfi) {
        Payout blocked = null;
        if (rfi.getBlockingResourceId() != null) {
            blocked = payouts.findByKiraPayoutId(rfi.getBlockingResourceId())
                    .filter(p -> p.getTenantId().equals(rfi.getTenantId()))
                    .orElse(null);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : readItems(rfi)) {
            if (item.isObject()) {
                items.add(objectMapper.convertValue(item, Map.class));
            }
        }
        return RfiView.from(rfi, items, blocked);
    }

    private JsonNode readItems(Rfi rfi) {
        try {
            return objectMapper.readTree(rfi.getItemsPayload());
        } catch (Exception e) {
            log.warn("Items ilegibles en el RFI {}: {}", rfi.getId(), e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private String itemsOf(JsonNode detail) {
        JsonNode items = detail.path("items");
        return items.isArray() ? items.toString() : "[]";
    }

    private static List<JsonNode> entries(JsonNode response) {
        JsonNode list = response == null ? null
                : response.isArray() ? response
                : response.has("data") ? response.get("data") : response.path("items");
        List<JsonNode> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(out::add);
        }
        return out;
    }

    private static JsonNode unwrap(JsonNode response) {
        return response != null && response.has("data") ? response.get("data") : response;
    }

    private static String kiraRfiIdOf(JsonNode entry) {
        return firstNonNull(text(entry, "rfi_id"), text(entry, "id"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void assertCanManage(AuthenticatedOperator operator) {
        if (!operator.role().canManageCompliance()) {
            throw new DomainException("Tu rol no puede gestionar RFIs.");
        }
    }

    private Rfi load(TenantId tenantId, String rfiId) {
        return rfis.findByIdAndTenant(rfiId, tenantId)
                .orElseThrow(() -> new DomainException("RFI no encontrado."));
    }
}
