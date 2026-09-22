package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, String> {

    boolean existsByEventId(String eventId);

    Optional<WebhookEventEntity> findByEventId(String eventId);

    /**
     * Eventos almacenados que nunca llegaron a proyectarse y que aun tienen reintentos. Kira no
     * reintenta, asi que esta fila es el unico rastro que queda de ese cambio de estado.
     *
     * Mas antiguo primero: el evento perdido mas viejo es el que lleva mas tiempo desalineando
     * el modelo de lectura. El filtro por retry_count es lo que evita que las filas agotadas
     * ocupen el lote para siempre y dejen sin sitio a los eventos nuevos.
     */
    List<WebhookEventEntity> findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(int maxRetries);

    List<WebhookEventEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId,
                                                                org.springframework.data.domain.Pageable page);

    /** Incidencias: eventos que fallaron proyectando al menos una vez, sea que aun se reintenten o no. */
    List<WebhookEventEntity> findByTenantIdAndProcessingErrorIsNotNullOrderByCreatedAtDesc(
            String tenantId, org.springframework.data.domain.Pageable page);
}
