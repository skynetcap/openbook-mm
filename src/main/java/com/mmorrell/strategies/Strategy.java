package com.mmorrell.strategies;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class Strategy {

    public abstract void start();

    @PostConstruct
    public void startupComplete() {
        log.info(this.getClass().getSimpleName() + " strategy instantiated.");
    }

}
