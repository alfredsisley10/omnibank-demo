package com.omnibank.appmaprec.web;

import com.omnibank.accounts.consumer.api.AccountOpening;
import com.omnibank.accounts.consumer.api.ConsumerAccountService;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.appmaprec.api.RecordingId;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.api.PaymentService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes a small catalog of pre-canned banking flows the recording UI
 * can trigger one click at a time. Each flow exercises a documented
 * cross-module call chain so the resulting AppMap is interesting to
 * inspect — e.g. the {@code openAccount} playbook produces a trace that
 * spans portal-controller → consumer accounts → ledger postings.
 *
 * <p>If the caller supplies {@code recordingId} in the request body,
 * each playbook step is also recorded as a narrative action against the
 * named recording so the saved appmap carries a human-readable summary
 * of what the agent should be capturing.</p>
 */
@RestController
@RequestMapping("/api/v1/appmap/playbooks")
public class PlaybookController {

    private final ConsumerAccountService accounts;
    private final PaymentService payments;
    private final RecordingService recordings;

    public PlaybookController(ConsumerAccountService accounts,
                              PaymentService payments,
                              RecordingService recordings) {
        this.accounts = accounts;
        this.payments = payments;
        this.recordings = recordings;
    }

    @GetMapping
    public List<Map<String, Object>> catalog() {
        return List.of(
                Map.of(
                        "id", "open-account",
                        "label", "Open consumer checking account",
                        "method", "POST",
                        "path", "/api/v1/appmap/playbooks/open-account",
                        "description", "Opens a CHECKING account, posts the opening deposit, and queries balance"
                ),
                Map.of(
                        "id", "submit-payment",
                        "label", "Submit ACH payment",
                        "method", "POST",
                        "path", "/api/v1/appmap/playbooks/submit-payment",
                        "description", "Submits an ACH payment, stores the entity, and reads back its status"
                ),
                Map.of(
                        "id", "balance-lookup",
                        "label", "Balance lookup (read-only)",
                        "method", "POST",
                        "path", "/api/v1/appmap/playbooks/balance-lookup",
                        "description", "Pure read flow exercising the account/ledger projection"
                )
        );
    }

    @PostMapping("/open-account")
    public Map<String, Object> openAccount(@RequestBody PlaybookRequest req) {
        String customerId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Money initial = Money.of(BigDecimal.valueOf(250), CurrencyCode.USD);
        AccountNumber number = accounts.open(new AccountOpening.Request(
                CustomerId.of(customerId),
                ConsumerProduct.CHECKING,
                CurrencyCode.USD,
                Optional.of(initial)
        ));
        accounts.balance(number);
        annotate(req.recordingId(),
                "playbook.open_account",
                "Opened CHECKING account for " + customerId
                        + " with initial deposit " + initial.amount() + " " + initial.currency(),
                Optional.of(number.raw()));
        return Map.of(
                "playbook", "open-account",
                "customerId", customerId,
                "accountNumber", number.raw(),
                "initialDeposit", initial.amount()
        );
    }

    @PostMapping("/submit-payment")
    public Map<String, Object> submitPayment(@RequestBody PlaybookRequest req) {
        String idem = "PB-" + UUID.randomUUID();
        PaymentRequest pr = new PaymentRequest(
                idem,
                PaymentRail.ACH,
                AccountNumber.of("OB-C-PLAY1234"),
                Optional.of(RoutingNumber.of("026073150")),
                "9876543210",
                "Playbook Beneficiary",
                Money.of(BigDecimal.valueOf(125.50), CurrencyCode.USD),
                "appmap-recording-ui playbook",
                Instant.now()
        );
        PaymentId id = payments.submit(pr);
        annotate(req.recordingId(),
                "playbook.submit_payment",
                "Submitted ACH payment " + id.value() + " for " + pr.amount().amount() + " " + pr.amount().currency(),
                Optional.of(id.value().toString()));
        return Map.of(
                "playbook", "submit-payment",
                "paymentId", id.value(),
                "status", payments.status(id).name(),
                "rail", pr.rail().name()
        );
    }

    @PostMapping("/balance-lookup")
    public Map<String, Object> balanceLookup(@RequestBody PlaybookRequest req) {
        String acct = req.account() == null || req.account().isBlank()
                ? "OB-C-PLAY1234"
                : req.account();
        var balance = accounts.balance(AccountNumber.of(acct));
        annotate(req.recordingId(),
                "playbook.balance_lookup",
                "Read balance for " + acct,
                Optional.of(acct));
        return Map.of(
                "playbook", "balance-lookup",
                "account", acct,
                "currency", balance.available().currency().name(),
                "available", balance.available().amount(),
                "ledger", balance.ledger().amount()
        );
    }

    private void annotate(String recordingId, String kind, String description, Optional<String> ref) {
        if (recordingId == null || recordingId.isBlank()) return;
        try {
            recordings.recordAction(RecordingId.of(recordingId), kind, description, ref);
        } catch (RuntimeException ignore) {
            // Annotation is best-effort. The playbook itself is the
            // primary deliverable; if the recording id is stale the
            // step still executed.
        }
    }

    public record PlaybookRequest(String recordingId, String account) {}
}
