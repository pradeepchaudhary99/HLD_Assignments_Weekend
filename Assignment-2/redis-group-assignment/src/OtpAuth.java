import java.security.SecureRandom;
import java.util.*;

/**
 * TRACK B · Feature B2 — OTP login + sessions.              Redis types: STRING with TTL, counter, HASH
 *
 * Problem: users log in with a phone number + 6-digit SMS code. Codes must expire, users must not
 * spam "resend", attackers must not brute-force 1,000,000 codes, and after login every API server
 * must recognise the session token. All of these are "small data with an expiry" — Redis's sweet spot.
 *
 * Keys you own:   otp:{phone}            STRING  the code            expires in 120 s
 *                 otp:cooldown:{phone}   STRING  "resend" blocker    expires in 30 s
 *                 otp:attempts:{phone}   STRING  wrong-guess counter expires in 120 s
 *                 session:{token}        HASH    phone, createdAt    expires 30 min after last use
 *
 *   POST /auth/otp     {"phone":"9876543210"}               -> 200 (code is printed in the server log = "SMS")
 *   POST /auth/verify  {"phone":"...","code":"123456"}      -> 200 {"token": "..."}
 *   GET  /auth/me      header Authorization: Bearer <token>
 *   POST /auth/logout  header Authorization: Bearer <token>
 */
final class OtpAuth {

    static final int OTP_TTL_SECONDS = 120;
    static final int RESEND_COOLDOWN_SECONDS = 30;
    static final int MAX_ATTEMPTS = 3;
    static final int SESSION_TTL_SECONDS = 30 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** status: OK, WRONG_CODE, EXPIRED (no code / code expired), LOCKED (too many attempts). token only when OK. */
    record VerifyResult(String status, String token) {}

    /**
     * TODO(1): Send an OTP.
     *   1. Cooldown: SET otp:cooldown:{phone} 1 NX EX RESEND_COOLDOWN_SECONDS
     *      NX means "only if it doesn't exist". Reply is "OK" if set, null if it already existed.
     *      null -> throw new Http.ApiError(429, "Wait before requesting another code")
     *   2. code = 6 random digits:  String.format("%06d", RANDOM.nextInt(1_000_000))
     *   3. SET otp:{phone} code EX OTP_TTL_SECONDS     and  DEL otp:attempts:{phone}  (fresh code, fresh attempts)
     *   4. System.out.println("[SMS to " + phone + "] Your QuickBite code is " + code);   return code
     */
    static String requestOtp(String phone) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Verify a code.
     *   1. attempts = INCR otp:attempts:{phone};  if attempts == 1 -> EXPIRE it OTP_TTL_SECONDS
     *      attempts > MAX_ATTEMPTS -> LOCKED   (count BEFORE checking, so even a correct 4th guess is refused)
     *   2. stored = GET otp:{phone};  null -> EXPIRED;  different from code -> WRONG_CODE
     *   3. Correct: DEL otp:{phone} and otp:attempts:{phone}  (a code works exactly once)
     *      token = UUID.randomUUID().toString()
     *      HSET session:{token} phone {phone} createdAt {millis}    then EXPIRE it SESSION_TTL_SECONDS
     *      return new VerifyResult("OK", token)
     */
    static VerifyResult verifyOtp(String phone, String code) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /**
     * TODO(3): Look up a session (every authenticated request calls this).
     *   HGETALL session:{token} -> empty list means no session: return null.
     *   Otherwise push the expiry back to SESSION_TTL_SECONDS (sliding: active users stay logged in)
     *   and return the fields as a Map (phone -> ..., createdAt -> ...).
     */
    static Map<String, String> session(String token) {
        if (token == null || token.isBlank()) return null;
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /** TODO(4): Logout = delete the session. Return true if a session was actually deleted. */
    static boolean logout(String token) {
        throw new UnsupportedOperationException("TODO(4) not implemented yet");
    }

    private OtpAuth() {}
}
