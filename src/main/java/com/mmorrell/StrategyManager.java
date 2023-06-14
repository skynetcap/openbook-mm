package com.mmorrell;

import com.mmorrell.strategies.openbook.sol.OpenBookSolUsdc;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Getter
public class StrategyManager {

    private final OpenBookSolUsdc openBookSolUsdc;


    public StrategyManager(OpenBookSolUsdc openBookSolUsdc) {
        this.openBookSolUsdc = openBookSolUsdc;
    }

    @PostConstruct
    public void strategyStartup() {
        openBookSolUsdc.start();
    }

}
