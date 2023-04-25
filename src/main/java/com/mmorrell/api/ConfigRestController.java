package com.mmorrell.api;

import com.mmorrell.config.OpenBookConfig;
import com.mmorrell.strategies.openbook.sol.OpenBookSolUsdc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static com.mmorrell.config.OpenBookConfig.ALLOWED_PRICING_BPS_MISMATCH;
import static com.mmorrell.config.OpenBookConfig.ASK_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.BID_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.MIN_MIDPOINT_CHANGE;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_MICRO_LAMPORTS_DEFAULT;
import static com.mmorrell.config.OpenBookConfig.PYTH_CONFIDENCE_INTERVAL_CONFIDENCE;
import static com.mmorrell.config.OpenBookConfig.PYTH_PREDICTIVE_FACTOR;
import static com.mmorrell.config.OpenBookConfig.USDC_THRESHOLD_TO_LEAN_WSOL;
import static com.mmorrell.config.OpenBookConfig.WSOL_THRESHOLD_TO_LEAN_USDC;
import static com.mmorrell.config.OpenBookConfig.resetAsks;
import static com.mmorrell.config.OpenBookConfig.resetBids;
import static com.mmorrell.config.OpenBookConfig.tightenAsks;
import static com.mmorrell.config.OpenBookConfig.tightenAsksHalf;
import static com.mmorrell.config.OpenBookConfig.tightenBids;
import static com.mmorrell.config.OpenBookConfig.tightenBidsHalf;
import static com.mmorrell.config.OpenBookConfig.widenAsks;
import static com.mmorrell.config.OpenBookConfig.widenBids;

@RestController
public class ConfigRestController {

    private final OpenBookSolUsdc openBookSolUsdc;
    public ConfigRestController(OpenBookSolUsdc openBookSolUsdc) {
        this.openBookSolUsdc = openBookSolUsdc;
    }

    @GetMapping(value = "/1337pwn/1337status")
    public Map<String, Object> status() {
        final Map<String, Object> results = new HashMap<>();
        // Get current state of the algo, return in a map
        double usdcInventory = OpenBookConfig.USDC_BALANCE.orElse(0.0);
        double wsolInventory = OpenBookConfig.WSOL_BALANCE.orElse(0.0);

        results.put("inventory", Map.of(
                "usdc", String.format("%.6f", usdcInventory),
                "wsol", String.format("%.6f", wsolInventory),
                "usdcThresholdToLeanBase", String.format("%.6f", USDC_THRESHOLD_TO_LEAN_WSOL),
                "wsolThresholdToLeanUsdc", String.format("%.6f", WSOL_THRESHOLD_TO_LEAN_USDC)
        ));
        results.put("priority", Map.of(
                "rate", String.format("%d", PRIORITY_MICRO_LAMPORTS_DEFAULT),
                "limit", String.format("%d", OpenBookConfig.PRIORITY_UNITS)
        ));
//        results.put("defaultBidSpread", String.format("%.6f", OpenBookConfig.DEFAULT_BID_SPREAD_MULTIPLIER));
//        results.put("defaultAskSpread", String.format("%.6f", OpenBookConfig.DEFAULT_ASK_SPREAD_MULTIPLIER));
        results.put("heuristics", Map.of(
                "isLeaningAsxMissing", String.valueOf(OpenBookConfig.IS_WSOL_LEANING)
        ));
//        results.put("pyth", Map.of(
//                "predictiveFactor",
//                String.format("%.6f", PYTH_PREDICTIVE_FACTOR),
//                "confidenceIntervalConfidence",
//                String.format("%.6f", PYTH_CONFIDENCE_INTERVAL_CONFIDENCE)
//        ));
//        results.put("midpoint", Map.of(
//                "minChange", String.format("%.6f", MIN_MIDPOINT_CHANGE),
//                "allowedBpsMismatch", String.format("%.6f", ALLOWED_PRICING_BPS_MISMATCH)
//        ));
        results.put("tuning", Map.of(
                "bids", String.format("%.6f", BID_API_TUNING_FACTOR),
                "asks", String.format("%.6f", ASK_API_TUNING_FACTOR)
        ));

        return results;
    }

    @GetMapping(value = "/1337pwn/increaseBaseRate")
    public Map<String, String> increaseBaseRate() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.PRIORITY_MICRO_LAMPORTS_DEFAULT;
        results.put("old", String.valueOf(original));
        original += (float) 10_000;
        OpenBookConfig.setPriorityMicroLamportsDefault((int) original);
        results.put("new", String.valueOf(original));
        return results;
    }

    @GetMapping(value = "/1337pwn/reduceBaseRate")
    public Map<String, String> reduceBaseRate() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.PRIORITY_MICRO_LAMPORTS_DEFAULT;
        results.put("old", String.valueOf(original));
        original -= (float) 10_000;
        OpenBookConfig.setPriorityMicroLamportsDefault((int) original);
        results.put("new", String.valueOf(original));
        return results;
    }

    @GetMapping(value = "/1337pwn/widenBid")
    public Map<String, String> widenBid() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.BID_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        widenBids();
        results.put("new", String.valueOf(OpenBookConfig.BID_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/tightenBid")
    public Map<String, String> tightenBid() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.BID_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        tightenBids();
        results.put("new", String.valueOf(OpenBookConfig.BID_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/tightenBidHalf")
    public Map<String, String> tightenBidHalf() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.BID_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        tightenBidsHalf();
        results.put("new", String.valueOf(OpenBookConfig.BID_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/tightenAskHalf")
    public Map<String, String> tightenAskHalf() {
        final Map<String, String> results = new HashMap<>();
        double original = ASK_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        tightenAsksHalf();
        results.put("new", String.valueOf(OpenBookConfig.ASK_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/resetBid")
    public Map<String, String> resetBid() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.BID_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        resetBids();
        results.put("new", String.valueOf(OpenBookConfig.BID_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/resetAsk")
    public Map<String, String> resetAsk() {
        final Map<String, String> results = new HashMap<>();
        double original = ASK_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        resetAsks();
        results.put("new", String.valueOf(OpenBookConfig.ASK_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/widenAsk")
    public Map<String, String> widenAsk() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.ASK_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        widenAsks();
        results.put("new", String.valueOf(OpenBookConfig.ASK_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/tightenAsk")
    public Map<String, String> tightenAsk() {
        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.ASK_API_TUNING_FACTOR;
        results.put("old", String.valueOf(original));
        tightenAsks();
        results.put("new", String.valueOf(OpenBookConfig.ASK_API_TUNING_FACTOR));
        return results;
    }

    @GetMapping(value = "/1337pwn/setQuoteSize/{quoteSize}")
    public Map<String, String> setQuoteSize(@PathVariable (value ="quoteSize") String quoteSize) {
        float floatSize = Float.parseFloat(quoteSize);

        final Map<String, String> results = new HashMap<>();
        double original = OpenBookConfig.SOL_QUOTE_SIZE;
        results.put("old", String.valueOf(original));
        OpenBookConfig.setQuoteSize(floatSize);
        results.put("new", String.valueOf(OpenBookConfig.SOL_QUOTE_SIZE));
        return results;
    }

    @GetMapping(value = "/1337pwn/mktSell/{quoteSize}")
    public Map<String, String> mktSell(@PathVariable (value ="quoteSize") String quoteSize) {
        float floatSize = Float.parseFloat(quoteSize);

        final Map<String, String> results = new HashMap<>();
        openBookSolUsdc.marketSell(floatSize);
        results.put("status", "done");
        return results;
    }

    @GetMapping(value = "/1337pwn/bidCxl")
    public Map<String, String> hardBidCxl() {
        openBookSolUsdc.hardCancelSingleBid();
        return Map.of("status", "done");
    }

    @GetMapping(value = "/1337pwn/askCxl")
    public Map<String, String> hardAskCxl() {
        openBookSolUsdc.hardCancelSingleAsk();
        return Map.of("status", "done");
    }


}
