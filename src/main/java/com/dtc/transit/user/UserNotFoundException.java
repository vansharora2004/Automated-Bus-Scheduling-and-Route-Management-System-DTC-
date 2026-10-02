package com.dtc.transit.user;

import com.dtc.transit.common.error.NotFoundException;

public class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(Long id) {
        super("User " + id + " was not found");
    }
}
