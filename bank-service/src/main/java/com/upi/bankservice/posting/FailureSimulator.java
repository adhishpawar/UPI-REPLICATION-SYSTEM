package com.upi.bankservice.posting;

import lombok.extern.slf4j.Slf4j;

/**
 * Deterministic failure injection, for demonstrating the recovery paths.
 *
 * <p>Three rules make this safe to have in the codebase:
 *
 * <ol>
 *   <li><b>Never random.</b> A failure happens only when the caller explicitly
 *       names a scenario. Random chaos in a payment system produces
 *       irreproducible bugs and teaches nothing.</li>
 *   <li><b>Never on normal traffic.</b> No scenario named, no failure. A
 *       request that does not ask for a failure behaves exactly as it would in
 *       production.</li>
 *   <li><b>Injected before any money moves</b>, so the resulting failure is
 *       genuinely indistinguishable from a real one to everything upstream.
 *       The orchestrator is not told it is a drill.</li>
 * </ol>
 *
 * <p>This is the difference between demonstrating recovery and faking it. The
 * simulated part is the <em>cause</em> of the failure; the detection,
 * classification, reconciliation and repair that follow are entirely real.
 *
 * @see <a href="file:../../../../../../../docs/testing/failure-scenarios.md">failure-scenarios.md</a>
 */
@Slf4j
public final class FailureSimulator {

    private FailureSimulator() { }

    /** Sleep past the caller's timeout. The posting never happens, but the
     *  caller cannot know that -- which is precisely scenario C. */
    public static final String TIMEOUT = "TIMEOUT";

    /** Post nothing, but hang long enough that the caller gives up. Then the
     *  caller's belief ("unknown") and reality ("nothing happened") differ,
     *  and only reconciliation can tell them apart. */
    public static final String SLOW = "SLOW";

    /** A definite, retryable-looking server error. Outcome still unknown. */
    public static final String SERVER_ERROR = "SERVER_ERROR";

    /** A definite business refusal. The one case that is genuinely FAILED. */
    public static final String REJECT = "REJECT";

    public static void apply(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return;
        }
        log.warn("FAILURE INJECTION ACTIVE: {} (demo only)", scenario);

        switch (scenario.toUpperCase()) {
            case TIMEOUT -> sleep(12_000);   // > the orchestrator's 5s timeout
            case SLOW -> sleep(7_000);
            case SERVER_ERROR -> throw new SimulatedServerException(
                    "Simulated downstream failure at the money-holder");
            case REJECT -> throw new PostingService.InsufficientFundsException(
                    "simulated", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
            default -> log.warn("Unknown failure scenario '{}' - ignoring", scenario);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class SimulatedServerException extends RuntimeException {
        public SimulatedServerException(String m) { super(m); }
    }
}
