package com.voxeo.moho;

import com.voxeo.utils.IEvent;


public interface ExceptionHandler {

    // Returns true if event processing should continue
    public boolean handle(Exception ex, IEvent<?> event);

}
