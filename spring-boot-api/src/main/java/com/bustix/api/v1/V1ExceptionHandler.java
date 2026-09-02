package com.bustix.api.v1;

import com.bustix.booking.MultiSeatRescheduleNotSupportedException;
import com.bustix.booking.OperatorInactiveException;
import com.bustix.booking.SeatConflictException;
import com.bustix.booking.TenantMismatchException;
import com.bustix.booking.TooLateToRescheduleException;
import com.bustix.cargo.BookingTripMismatchException;
import com.bustix.cargo.ConsigneeIdentityMismatchException;
import com.bustix.cargo.InvalidWaybillItemsException;
import com.bustix.cargo.InvalidWaybillStatusException;
import com.bustix.cargo.NoCargoRateConfiguredException;
import com.bustix.cargo.ProhibitedItemException;
import com.bustix.cargo.WaybillAlreadyCancelledException;
import com.bustix.refund.BookingAlreadyCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * One error shape for the whole {@code /v1} surface: RFC 9457
 * {@code application/problem+json}. Scoped to {@code com.bustix.api.v1}, so
 * the internal {@code /api} controllers keep their existing plain-string
 * error bodies. Replaces the per-controller {@code @ExceptionHandler}
 * methods the v1 controllers carried through WS-2.
 *
 * Every body carries a machine-readable {@code code} (a stable kebab slug)
 * alongside the human {@code detail}. Bean-validation failures add an
 * {@code errors} array of {field, message}.
 *
 * A {@code @PreAuthorize} scope denial (valid token, missing
 * {@code SCOPE_*}) becomes a 403 problem+json here. A missing or invalid
 * token is a 401 raised by the security filter chain before any controller
 * runs and uses Spring Security's default bearer-token error body - unifying
 * that is a later follow-up.
 */
@RestControllerAdvice(basePackages = "com.bustix.api.v1")
public class V1ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V1ExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(TenantMismatchException.class)
    public ProblemDetail handleTenantMismatch(TenantMismatchException e) {
        return problem(HttpStatus.FORBIDDEN, "tenant-mismatch", e.getMessage());
    }

    /**
     * A method-security {@code @PreAuthorize} denial - the token is valid but
     * lacks the required {@code SCOPE_*} authority for this endpoint. (A
     * missing/invalid token is a 401 raised by the security filter chain
     * before a controller runs, and never reaches this advice.)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "insufficient-scope",
                "Your API credentials are not granted the scope required for this endpoint.");
    }

    @ExceptionHandler({
            ProhibitedItemException.class,
            NoCargoRateConfiguredException.class,
            InvalidWaybillItemsException.class,
            BookingTripMismatchException.class})
    public ProblemDetail handleBadBusinessRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, codeFor(e), e.getMessage());
    }

    @ExceptionHandler({
            SeatConflictException.class,
            OperatorInactiveException.class,
            BookingAlreadyCancelledException.class,
            TooLateToRescheduleException.class,
            MultiSeatRescheduleNotSupportedException.class,
            InvalidWaybillStatusException.class,
            WaybillAlreadyCancelledException.class,
            ConsigneeIdentityMismatchException.class})
    public ProblemDetail handleConflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, codeFor(e), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "One or more fields in the request are invalid.");
        pd.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList());
        return pd;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class})
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request-body",
                "The request body could not be read as JSON.");
    }

    /** A rejected argument the controller checked itself, e.g. an unacceptable webhook URL. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleBadParam(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-parameter", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled /v1 error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred. If it persists, contact support with the time of the request.");
    }

    private static final Map<Class<?>, String> CODES = Map.ofEntries(
            Map.entry(SeatConflictException.class, "seat-conflict"),
            Map.entry(OperatorInactiveException.class, "operator-inactive"),
            Map.entry(BookingAlreadyCancelledException.class, "booking-already-cancelled"),
            Map.entry(TooLateToRescheduleException.class, "too-late-to-reschedule"),
            Map.entry(MultiSeatRescheduleNotSupportedException.class, "multi-seat-reschedule-unsupported"),
            Map.entry(ProhibitedItemException.class, "prohibited-item"),
            Map.entry(NoCargoRateConfiguredException.class, "no-cargo-rate-configured"),
            Map.entry(InvalidWaybillItemsException.class, "invalid-waybill-items"),
            Map.entry(BookingTripMismatchException.class, "booking-trip-mismatch"),
            Map.entry(InvalidWaybillStatusException.class, "invalid-waybill-status"),
            Map.entry(WaybillAlreadyCancelledException.class, "waybill-already-cancelled"),
            Map.entry(ConsigneeIdentityMismatchException.class, "consignee-id-mismatch"));

    private static String codeFor(Throwable e) {
        return CODES.getOrDefault(e.getClass(), "conflict");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                status, detail != null && !detail.isBlank() ? detail : status.getReasonPhrase());
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        return pd;
    }
}
