package com.bustix.cargo;

/** Maps to HTTP 409 in CargoWaybillController - confirm-and-issue was called on a waybill that isn't "requested" (out-of-order - mirrors InvalidWaybillStatusException's role for the rest of the state machine), or is missing a required field (e.g. no consigneeIdNumber on file or supplied) that issuing requires. */
public class RequestNotIssuableException extends RuntimeException {
    public RequestNotIssuableException(String message) {
        super(message);
    }
}
