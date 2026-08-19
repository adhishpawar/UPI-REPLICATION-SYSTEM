package com.upi.payment.exception;

/**
 * The authenticated user does not own the VPA they are trying to pay from.
 *
 * <p>Maps to <b>403</b>, not 404 — and the choice is deliberate, because it
 * cuts the opposite way to how transactions are handled:
 *
 * <ul>
 *   <li>Reading someone else's transaction returns <b>404</b>. A 403 would
 *       confirm the id exists and let an attacker enumerate valid transaction
 *       ids.</li>
 *   <li>Paying from someone else's VPA returns <b>403</b>. VPAs are public by
 *       design — anyone can discover that {@code priya@okaxis} exists simply
 *       by resolving it in order to pay her. Hiding behind a 404 would conceal
 *       nothing and would leave a legitimate caller thinking their own VPA was
 *       misconfigured.</li>
 * </ul>
 *
 * <p>The rule underneath: obscure the existence of things meant to be secret,
 * and be clear about refusals concerning things that are not.
 */
public class VpaOwnershipException extends PaymentBaseException {
    public VpaOwnershipException(String vpaAddress) {
        super("The VPA " + vpaAddress + " does not belong to the authenticated user",
              "VPA_NOT_OWNED");
    }
}
