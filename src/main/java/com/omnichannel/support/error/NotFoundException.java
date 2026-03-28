package com.omnichannel.support.error;

public class NotFoundException extends ApplicationException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
