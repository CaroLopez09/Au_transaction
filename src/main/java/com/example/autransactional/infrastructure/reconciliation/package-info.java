/**
 * Workers de reconciliacion.
 *
 * Kira entrega cada webhook UNA sola vez y sin reintentos: si el BFF estaba caido, si el
 * evento se perdio en la red o si la proyeccion fallo, ese cambio de estado no vuelve.
 * Los trabajos programados de este paquete cierran ese hueco preguntando por el recurso,
 * que es la autoridad final:
 *
 * <ul>
 *   <li>pagos en vuelo (CREATED, PENDING, PROCESSING, KYT_PENDING, IN_REVIEW) contra
 *       GET /v1/payouts/{id}, via {@code PayoutRepository#findInFlight};</li>
 *   <li>cotizaciones ACTIVE cuyo TTL de 15 minutos ya paso, via
 *       {@code QuotationRepository#findActiveExpiredBefore};</li>
 *   <li>enlaces de liveness de UBOs vencidos a los 7 dias, via
 *       {@code UboRepository#findPendingLivenessExpiredBefore};</li>
 *   <li>filas de {@code webhooks_log} almacenadas con processing_error y nunca proyectadas, via
 *       {@code WebhookEventJpaRepository#findByProcessedFalseOrderByCreatedAtAsc}. Este ultimo no
 *       pregunta por ningun recurso: hay eventos cuyo dato no existe en ningun GET.</li>
 * </ul>
 *
 * El planificador ya esta habilitado en
 * {@link com.example.autransactional.infrastructure.config.AsyncConfig}.
 */
package com.example.autransactional.infrastructure.reconciliation;
