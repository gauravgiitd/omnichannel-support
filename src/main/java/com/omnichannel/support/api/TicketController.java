package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CreateTicketRequest;
import com.omnichannel.support.dto.MergeTicketsRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PatchTicketRequest;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.service.TicketMergeService;
import com.omnichannel.support.service.TicketService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final TicketMergeService ticketMergeService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketDto>>> listTickets() {
        return ResponseEntity.ok(ApiResponse.success(ticketService.listAllTickets()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TicketDto>> createTicket(@Valid @RequestBody CreateTicketRequest request) {
        TicketDto created = ticketService.createTicket(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<ApiResponse<TicketDto>> getTicket(@PathVariable("ticketId") String ticketId) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getTicket(ticketId)));
    }

    @PatchMapping(path = "/{ticketId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TicketDto>> patchTicket(
            @PathVariable("ticketId") String ticketId, @Valid @RequestBody PatchTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.patchTicket(ticketId, request)));
    }

    @GetMapping("/{ticketId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> listMessages(@PathVariable("ticketId") String ticketId) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.listMessages(ticketId)));
    }

    @PostMapping(path = "/{ticketId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postMessage(
            @PathVariable("ticketId") String ticketId, @Valid @RequestBody PostMessageRequest request) {
        MessageDto message = ticketService.postMessage(ticketId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message));
    }

    @PostMapping(path = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Void>> mergeTickets(@Valid @RequestBody MergeTicketsRequest request) {
        ticketMergeService.mergeTickets(
                request.primaryTicketNumber(), request.mergedTicketNumber(), request.mergedByActor());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
