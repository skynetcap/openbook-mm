package com.mmorrell;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@SpringBootApplication
public class SerumApplication {

    final StrategyManager strategyManager;

    public SerumApplication(StrategyManager strategyManager) {
        this.strategyManager = strategyManager;
    }

    // @RequestMapping("/")
    Map<String, Object> home() {
        return Map.of(
                "email", "contact@mmorrell.com",
                "status", "building",
                "solana", Map.of(
                        "bid", String.format("%.3f", strategyManager.getOpenBookSolUsdc().getBestBidPrice()),
                        "ask", String.format("%.3f", strategyManager.getOpenBookSolUsdc().getBestAskPrice())
                )
        );
    }

    public static void main(String[] args) {
        SpringApplication.run(SerumApplication.class, args);
    }

}