package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Create Or Assign To Ticket Job
 * Equivalent to Rails CreateOrAssignToTicketWorker
 *
 * This job runs 5 seconds after a message is created to handle
 * out-of-order message delivery. The delay ensures all related
 * messages in the same "batch" are received before ticket assignment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateOrAssignToTicketJob {

    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final DelayedJobService delayedJobService;

    /**
     * Schedule ticket assignment for a message with 5 second delay
     * Equivalent to: CreateOrAssignToTicketWorker.perform_in(5.seconds, message_id)
     */
    public void scheduleAssignment(Long messageId) {
        delayedJobService.scheduleIn5Seconds(
                () -> assignToTicket(messageId),
                "CreateOrAssignToTicket-" + messageId
        );
    }

    /**
     * Perform the actual ticket assignment
     *
     * PARIDAD RAILS: Este job SOLO asigna mensajes a tickets existentes.
     * NO crea tickets nuevos - eso lo hace MessageService.assignToTicket()
     *
     * Rails CreateOrAssignToTicketWorker (líneas 4-18):
     * - Busca ticket abierto entre sender/recipient (bidireccional)
     * - Si hay ticket abierto -> asigna mensaje
     * - Si hay ticket cerrado Y es outgoing -> asigna mensaje
     * - NUNCA crea tickets
     */
    @Transactional
    public void assignToTicket(Long messageId) {
        try {
            Optional<Message> messageOpt = messageRepository.findById(messageId);
            if (messageOpt.isEmpty()) {
                log.warn("Message {} not found for ticket assignment", messageId);
                return;
            }

            Message message = messageOpt.get();

            // Skip if already assigned to a ticket
            if (message.getTicket() != null) {
                log.debug("Message {} already assigned to ticket {}", messageId, message.getTicket().getId());
                return;
            }

            // PARIDAD RAILS: Buscar ticket abierto (bidireccional como Rails línea 7)
            // Ticket.where(user_id: sender_id, agent_id: recipient_id, status: 'open')
            //   .or(Ticket.where(user_id: recipient_id, agent_id: sender_id, status: 'open')).last
            Optional<Ticket> lastOpenTicket = ticketRepository.findOpenTicketBidirectional(
                    message.getSender().getId(), message.getRecipient().getId(), TicketStatus.OPEN);

            if (lastOpenTicket.isPresent()) {
                // PARIDAD RAILS línea 11: Asignar a ticket abierto
                message.setTicket(lastOpenTicket.get());
                messageRepository.save(message);
                log.debug("Assigned message {} to open ticket {}", messageId, lastOpenTicket.get().getId());
                return;
            }

            // PARIDAD RAILS: Buscar ticket cerrado (bidireccional como Rails línea 8)
            Optional<Ticket> lastClosedTicket = ticketRepository.findClosedTicketBidirectional(
                    message.getSender().getId(), message.getRecipient().getId(), TicketStatus.CLOSED);

            if (lastClosedTicket.isPresent() && message.isOutgoing()) {
                // PARIDAD RAILS líneas 13-16: Solo outgoing se asigna a ticket cerrado
                message.setTicket(lastClosedTicket.get());
                messageRepository.save(message);
                log.debug("Assigned outgoing message {} to closed ticket {}", messageId, lastClosedTicket.get().getId());
                return;
            }

            // PARIDAD RAILS: Si llegamos aquí, no hay ticket para asignar
            // El mensaje queda sin ticket (Rails no crea tickets aquí)
            log.debug("No existing ticket found for message {} - leaving unassigned", messageId);

        } catch (Exception e) {
            log.error("Error assigning message {} to ticket: {}", messageId, e.getMessage(), e);
        }
    }
}
