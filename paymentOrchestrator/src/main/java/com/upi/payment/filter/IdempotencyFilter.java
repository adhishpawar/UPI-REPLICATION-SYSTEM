package com.upi.payment.filter;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.repository.TransactionRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

@Component
@Order(1)  //will run before filters -idempotency check must be first
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter{

    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected  void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain)
            throws ServletException, IOException
    {
        //only apply to payment initiation endpoint
        if(!request.getRequestURI().equals("/api/v1/payments")
        || !"POST".equals(request.getMethod()))
        {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader("Idempotency=Key");

        if(idempotencyKey == null || idempotencyKey.isBlank())
        {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(
                    "{\"errorCode\" : \"MISSING_IDEMPOTENCY_KEY\"}");
            return;
        }

        //PHASE1 : Check if this key already exists
        //findByIdempotency uses UNIQUE index --> O(log n) BTree Lookup
        transactionRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> {
           try{
               log.info("Idempotency hit: key={}, txnId={}",
                       idempotencyKey, existing.getTransactionId());
               response.setStatus(200);
               response.setContentType("application/json");
               //Return original response - exactly what was returned first time
               response.getWriter().write(existing.getIdempotencyResponse());
           } catch (IOException e)
           {
               throw new RuntimeException(e);
           }
        });

        //If response already written (idempotency hit), Stop chain
        if(response.isCommitted()) return;

        //PHASE 2: New request - wrap response to capture it for storage
        ContentCachingResponseWrapper wrappedResponse =
                new ContentCachingResponseWrapper(response);

        chain.doFilter(request, wrappedResponse);

        //After Processing: save the response body against the key
        //This is done by PaymentService.initiatePayment() before returning
        // (idempotencyKey + responseBody stored on the Transaction entity)
        wrappedResponse.copyBodyToResponse();
    }
}
