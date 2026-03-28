package com.omnichannel.support.error;

public class ValidationException extends ApplicationException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
