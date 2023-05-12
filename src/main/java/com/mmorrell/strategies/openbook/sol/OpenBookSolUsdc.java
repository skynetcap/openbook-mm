package com.mmorrell.strategies.openbook.sol;

import com.google.common.collect.ImmutableList;
import com.mmorrell.SerumApplication;
import com.mmorrell.config.OpenBookConfig;
import com.mmorrell.pricing.PythPricingSource;
import com.mmorrell.serum.manager.SerumManager;
import com.mmorrell.serum.model.MarketBuilder;
import com.mmorrell.serum.model.Order;
import com.mmorrell.serum.model.OrderBook;
import com.mmorrell.serum.model.OrderTypeLayout;
import com.mmorrell.serum.model.SelfTradeBehaviorLayout;
import com.mmorrell.serum.model.SerumUtils;
import com.mmorrell.serum.program.SerumProgram;
import com.mmorrell.strategies.Strategy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.ComputeBudgetProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.mmorrell.config.OpenBookConfig.ADVERSITY_BASE_PRIORITY_RATE_TO_ADD;
import static com.mmorrell.config.OpenBookConfig.ADVERSITY_BASE_REDUCTION;
import static com.mmorrell.config.OpenBookConfig.ALLOWED_PRICING_BPS_MISMATCH;
import static com.mmorrell.config.OpenBookConfig.ASK_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.ASK_SPREAD_MULTIPLIER;
import static com.mmorrell.config.OpenBookConfig.BID_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.BID_SPREAD_MULTIPLIER;
import static com.mmorrell.config.OpenBookConfig.CROSS_DETECTION_PADDING;
import static com.mmorrell.config.OpenBookConfig.DEFAULT_ASK_SPREAD_MULTIPLIER;
import static com.mmorrell.config.OpenBookConfig.DEFAULT_BID_SPREAD_MULTIPLIER;
import static com.mmorrell.config.OpenBookConfig.EVENT_QUEUE_SIZE_ASK_WIDEN;
import static com.mmorrell.config.OpenBookConfig.EVENT_QUEUE_SIZE_THRESHOLD_FOR_WIDEN;
import static com.mmorrell.config.OpenBookConfig.GIGA_LEANING;
import static com.mmorrell.config.OpenBookConfig.IS_WSOL_LEANING;
import static com.mmorrell.config.OpenBookConfig.KNOWN_FISH;
import static com.mmorrell.config.OpenBookConfig.KNOWN_SHARPS;
import static com.mmorrell.config.OpenBookConfig.MIN_MIDPOINT_CHANGE;
import static com.mmorrell.config.OpenBookConfig.NEW_ORDER_DELAY_DURATION_SECONDS;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_MICRO_LAMPORTS;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_MICRO_LAMPORTS_DEFAULT;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_UNITS;
import static com.mmorrell.config.OpenBookConfig.PYTH_CONFIDENCE_INTERVAL_CONFIDENCE;
import static com.mmorrell.config.OpenBookConfig.PYTH_PREDICTIVE_FACTOR;
import static com.mmorrell.config.OpenBookConfig.PYTH_PREDICTIVE_FACTOR_BIDS;
import static com.mmorrell.config.OpenBookConfig.SOL_ASK_AMOUNT;
import static com.mmorrell.config.OpenBookConfig.SOL_QUOTE_SIZE;
import static com.mmorrell.config.OpenBookConfig.SOL_USDC_MARKET_ID;
import static com.mmorrell.config.OpenBookConfig.SOL_USDC_OOA;
import static com.mmorrell.config.OpenBookConfig.SPACE_MONKEY;
import static com.mmorrell.config.OpenBookConfig.START_SOL_PRICE;
import static com.mmorrell.config.OpenBookConfig.TARGET_MAX_UNITS;
import static com.mmorrell.config.OpenBookConfig.USDC_BID_AMOUNT_IN_WSOL;
import static com.mmorrell.config.OpenBookConfig.USDC_THRESHOLD_TO_LEAN_WSOL;
import static com.mmorrell.config.OpenBookConfig.generateLeanFactor;
import static com.mmorrell.config.OpenBookConfig.getPriorityMicroLamports;
import static com.mmorrell.config.OpenBookConfig.solUsdcMarket;

@Component
@Slf4j
@Getter
public class OpenBookSolUsdc extends Strategy {
    private final RpcClient rpcClient;
    private final RpcClient dataRpcClient;
    private final SerumManager serumManager;
    private final ScheduledExecutorService executorService;
    private final ExecutorService orderExecutorService = Executors.newFixedThreadPool(64);
    private final PythPricingSource pythPricingSource;
    private double bestBidPrice;
    private double bestAskPrice;
    private float lastPlacedBidPrice = 0.0f, lastPlacedAskPrice = 0.0f;
    private static Instant bidAdversityTimestamp = Instant.now();
    private static Instant askAdversityTimestamp = Instant.now();
    private static Instant lastHardCancelBidTimestamp = Instant.now();     // Hard cancel timers
    private static Instant lastHardCancelAskTimestamp = Instant.now();
    private static Instant lastBidTimestamp = Instant.now();
    private static Instant lastAskTimestamp = Instant.now();
    private static final Deque<Float> smaValues = new ArrayDeque<>(5);
    private static final Deque<Float> askSmaValues = new ArrayDeque<>(4);
    private List<Order> bidOrders;
    private List<Order> askOrders;

    public OpenBookSolUsdc(final SerumManager serumManager,
                           final RpcClient rpcClient,
                           @Qualifier("data") final RpcClient dataRpcClient,
                           final PythPricingSource pythPricingSource) {
        this.executorService = Executors.newScheduledThreadPool(128);
        this.serumManager = serumManager;
        this.rpcClient = rpcClient;
        this.dataRpcClient = dataRpcClient;
        this.pythPricingSource = pythPricingSource;
        OpenBookConfig.mmAccount = readMmAccountFromPrivateKey();
        initializeWrappedSolAccount();
        OpenBookConfig.solUsdcMarketBuilder = new MarketBuilder()
                .setClient(dataRpcClient)
                .setPublicKey(SOL_USDC_MARKET_ID)
                //.setRetrieveEventQueue(true)
                .setRetrieveOrderBooks(true);
        solUsdcMarket = OpenBookConfig.solUsdcMarketBuilder.build();
        this.bestBidPrice = solUsdcMarket.getBidOrderBook().getBestBid().getFloatPrice();
        this.bestAskPrice = solUsdcMarket.getAskOrderBook().getBestAsk().getFloatPrice();
        updateOb();
    }

    private void solUsdcEventLoop() {
        // Whole block synchronized, dont want diff states sent at same time. Orders are async/instant / non-blocking
        synchronized (this) {
            OrderBook bidOrderBook;
            OrderBook askOrderBook;
            Optional<Float> pythSolPrice;
            Optional<Float> pythSolPriceConfidence;
            bidOrderBook = solUsdcMarket.getBidOrderBook();
            bidOrders = ImmutableList.copyOf(bidOrderBook.getOrders());
            askOrderBook = solUsdcMarket.getAskOrderBook();
            askOrders = ImmutableList.copyOf(askOrderBook.getOrders());
            pythSolPrice = pythPricingSource.getSolMidpointPrice(); //25
            pythSolPriceConfidence = pythPricingSource.getSolPriceConfidence(); //0.03

            final Order bestBid = bidOrderBook.getBestBid();
            final Order bestAsk = askOrderBook.getBestAsk();
            final Optional<Order> topOfBookFish = askOrders.stream()
                    .filter(order -> KNOWN_FISH.contains(order.getOwner()))
                    .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()));
            this.bestBidPrice = bidOrders.stream()
                    .filter(order -> !KNOWN_FISH.contains(order.getOwner()))
                    .filter(order -> !order.getOwner().equals(SOL_USDC_OOA)) // not us either
                    .max((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .map(Order::getFloatPrice)
                    .orElse(bestBid.getFloatPrice());
            this.bestAskPrice = askOrders.stream()
                    .filter(order -> !KNOWN_FISH.contains(order.getOwner()))
                    .filter(order -> !order.getOwner().equals(SOL_USDC_OOA)) // not us either
                    .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .map(Order::getFloatPrice)
                    .orElse(bestAsk.getFloatPrice());

            if (topOfBookFish.isPresent()) {
                Order fishOrder = topOfBookFish.get(); // Average it with the next best quote
                if (fishOrder.getFloatPrice() <= bestAskPrice) {
                    Optional<Order> topOfBookNotFish = askOrders.stream()
                            .filter(order -> !KNOWN_FISH.contains(order.getOwner()))
                            .filter(order -> !order.getOwner().equals(SOL_USDC_OOA)) // not us either
                            .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()));
                    topOfBookNotFish.ifPresent(order -> this.bestAskPrice =
                            (fishOrder.getFloatPrice() + order.getFloatPrice()) / 2.0);
                }
            }
            boolean isCancelBid, isReadyToNewBid, shouldCancelBid;
            synchronized (this) {
                isCancelBid = bidOrders.stream().anyMatch(order -> order.getOwner().equals(SOL_USDC_OOA));
                isReadyToNewBid = Math.abs(Duration.between(Instant.now(), lastBidTimestamp).toSeconds()) >=
                        NEW_ORDER_DELAY_DURATION_SECONDS;
                shouldCancelBid = isCancelBid || !isReadyToNewBid;
            }
            float nextPlacedBidPrice = (float) bestBidPrice * BID_SPREAD_MULTIPLIER;
            if (pythSolPrice.isPresent() && pythSolPriceConfidence.isPresent()) {
                float halfConfidence = pythSolPriceConfidence.get() * PYTH_CONFIDENCE_INTERVAL_CONFIDENCE;
                float lowerBoundEstimation = pythSolPrice.get() - halfConfidence;
                float pythSolPriceFloat = lowerBoundEstimation * (1.0f - PYTH_PREDICTIVE_FACTOR_BIDS); //DEJ
                if (lastPlacedBidPrice != 0 && lastPlacedBidPrice >= pythSolPriceFloat) {
                    lastPlacedBidPrice = 0;
                    bidAdversityTimestamp = Instant.now();
                    nextPlacedBidPrice =
                            ((float) (Math.max(nextPlacedBidPrice, bestBidPrice) + pythSolPriceFloat) / 2.0f);
                }
            }
            long durationSinceBidAdversity = Math.abs(Duration.between(Instant.now(), bidAdversityTimestamp).toSeconds());
            long durationSinceBidAdversityMs = Math.abs(Duration.between(Instant.now(), bidAdversityTimestamp).toMillis());
            long durationSinceBid = Math.abs(Duration.between(Instant.now(), lastBidTimestamp).toSeconds());
            int bidSpreadAdversityDurationThreshold = 4;
            if (durationSinceBidAdversityMs <= (bidSpreadAdversityDurationThreshold * 1_000L)) {
                double bpsToRemove = 0.999999 - (.000003 * (4.001 - (durationSinceBidAdversityMs / 1_000.0)));
                nextPlacedBidPrice = nextPlacedBidPrice * (float) bpsToRemove; //bug
            }
            int bidAdversityDurationThreshold = 3;
            if (durationSinceBidAdversity < bidAdversityDurationThreshold) {
                double rateToAdd = ((double) (bidAdversityDurationThreshold - (durationSinceBidAdversity - 1))
                        * ADVERSITY_BASE_PRIORITY_RATE_TO_ADD) - ADVERSITY_BASE_REDUCTION;
                double newRate = PRIORITY_MICRO_LAMPORTS_DEFAULT + rateToAdd;
                OpenBookConfig.setPriorityMicroLamports((int) newRate);
            } else {
                OpenBookConfig.useDefaultPriorityMicroLamports();
            }
            float percentageChangeFromLastBid = 1.00f - (lastPlacedBidPrice / nextPlacedBidPrice);
            boolean asxBidPresent = false;
            for (Order bidOrder : bidOrders) {
                if (bidOrder.getOwner().equals(SPACE_MONKEY) && bidOrder.getFloatPrice() >= nextPlacedBidPrice &&
                        (bidOrder.getFloatQuantity() * bidOrder.getFloatPrice() >= 700)) {
                    asxBidPresent = true; // If ASX isn't quoting (ABOVE YOU) widen
                    OpenBookConfig.setPriorityMicroLamports(getPriorityMicroLamports() + 15_000);
                    nextPlacedBidPrice = nextPlacedBidPrice * 1.00015f; // add bps if hes above us
                }
            }

            Order ourCurrentBid = null;
            for (Order bidOrder : bidOrders) {
                if (bidOrder.getOwner().equals(SOL_USDC_OOA)) {
                    if (ourCurrentBid == null || bidOrder.getFloatPrice() >= ourCurrentBid.getFloatPrice()) {
                        ourCurrentBid = bidOrder; // use this as our best bid
                    }
                }
            }
            boolean isOurBidInModel = true;
            if (ourCurrentBid != null) {
                float ourCurrentPrice = ourCurrentBid.getFloatPrice();
                float difference = Math.abs(nextPlacedBidPrice - ourCurrentPrice);
                float bpsDifference = difference / nextPlacedBidPrice;  // 0.01 cent / 25$
                if (bpsDifference >= ALLOWED_PRICING_BPS_MISMATCH) {
                    isOurBidInModel = false;
                    //log.info("Bid stale. Ex: " + nextPlacedBidPrice + ", seen: " + ourCurrentPrice + ", diff: " +
                    // bpsDifference);
                }
            }
            if (!shouldCancelBid) {
                OpenBookConfig.setPriorityMicroLamports((int) ((double) getPriorityMicroLamports() * 1.35)); // fresh order
            }
            float smoothedBidPrice = nextPlacedBidPrice;
            final float finalSmoothedBidPrice = smoothedBidPrice;
            Optional<Order> sharpAboveBid = bidOrders.stream()
                    .filter(order -> order.getFloatPrice() >= finalSmoothedBidPrice)
                    .filter(order -> !order.getOwner().equals(SOL_USDC_OOA))
                    .filter(order -> KNOWN_SHARPS.contains(order.getOwner()))
                    .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .stream().findAny();
            if (sharpAboveBid.isPresent()) {
                float jumpsPrice = sharpAboveBid.get().getFloatPrice();
                if (Math.abs(jumpsPrice - smoothedBidPrice) <= 0.0058 && jumpsPrice >= smoothedBidPrice) {
                    smoothedBidPrice += 0.0059f;
                }
            }
            // API tuning
            if (BID_API_TUNING_FACTOR != 1f) {
                smoothedBidPrice = smoothedBidPrice * BID_API_TUNING_FACTOR;
                log.info("Tuning bid: " + smoothedBidPrice + ", " + BID_API_TUNING_FACTOR);
            }
            float incomingBid = smoothedBidPrice;
            if (lastPlacedBidPrice == 0 || (Math.abs(percentageChangeFromLastBid) >= MIN_MIDPOINT_CHANGE) ||
                    (!asxBidPresent && durationSinceBid >= 1) || !isOurBidInModel) {
                if ((incomingBid >= lastPlacedAskPrice * (1 - CROSS_DETECTION_PADDING)) && durationSinceBid <= 1) {
                    log.info("USDC Bid cross: " + incomingBid + ", last ask: " + lastPlacedAskPrice);
                } else {
                    placeUsdcBid(USDC_BID_AMOUNT_IN_WSOL, incomingBid, shouldCancelBid);
                    lastPlacedBidPrice = incomingBid;
                }
            }
            // Asks
            boolean isCancelAsk, isReadyToPlaceNewAsk, shouldCancelAsk;
            synchronized (this) {
                isCancelAsk = askOrders.stream().anyMatch(order -> order.getOwner().equals(SOL_USDC_OOA));
                isReadyToPlaceNewAsk = Math.abs(Duration.between(Instant.now(), lastAskTimestamp).toSeconds()) >=
                        NEW_ORDER_DELAY_DURATION_SECONDS;
                shouldCancelAsk = isCancelAsk || !isReadyToPlaceNewAsk;
            }
            float nextPlacedAskPrice = (float) bestAskPrice * ASK_SPREAD_MULTIPLIER;
            if (pythSolPrice.isPresent() && pythSolPriceConfidence.isPresent()) {
                float halfConfidence = pythSolPriceConfidence.get() * PYTH_CONFIDENCE_INTERVAL_CONFIDENCE;
                float upperBoundEstimation = pythSolPrice.get() + halfConfidence;
                float pythSolPriceFloat = upperBoundEstimation * (1 + PYTH_PREDICTIVE_FACTOR); // DEJ
                if (lastPlacedAskPrice != 0 && lastPlacedAskPrice <= pythSolPriceFloat) {
                    //log.info("Adv ask: " + nextPlacedAskPrice + " vs. Pyth " + pythSolPriceFloat);
                    askAdversityTimestamp = Instant.now();
                    nextPlacedAskPrice =
                            ((float) (Math.min(nextPlacedAskPrice, bestAskPrice) + pythSolPriceFloat) / 2.0f);
                    //log.info("Next ask:" + nextPlacedAskPrice);
                    lastPlacedAskPrice = 0; // re-quote
                }
            }
            long durationSinceAskAdversity = Math.abs(Duration.between(Instant.now(), askAdversityTimestamp).toSeconds());
            long durationSinceAskAdversityMs = Math.abs(Duration.between(Instant.now(), askAdversityTimestamp).toMillis());
            long durationSinceAsk = Math.abs(Duration.between(Instant.now(), lastAskTimestamp).toSeconds());
            // If adversity in past 4 sec, widen
            int askSpreadAdversityDurationThreshold = 3;
            if (durationSinceAskAdversityMs <= (askSpreadAdversityDurationThreshold * 1_000L)) {
                double bpsToAddMultiplier = 0.000001;
                if (GIGA_LEANING) {
                    bpsToAddMultiplier = 0.0000001;
                }
                float before = nextPlacedAskPrice;
                double bpsToAdd =
                        1.00000 + Math.abs(bpsToAddMultiplier * ((double) askSpreadAdversityDurationThreshold -
                                (durationSinceBidAdversityMs / 1_000.0)));
                nextPlacedAskPrice = nextPlacedAskPrice * (float) bpsToAdd; //bug
                // log.info("Adv bps: " + bpsToAdd + ", new: " + nextPlacedAskPrice + ", old: " + before);
            }
            // If recent ask adversity, use a massive priority fee.
            int askAdversityDurationThreshold = 2;
            if (durationSinceAskAdversity < askAdversityDurationThreshold || GIGA_LEANING) {
                double rateToAdd = ((double) (askAdversityDurationThreshold - (durationSinceAskAdversity - 1)) *
                        ADVERSITY_BASE_PRIORITY_RATE_TO_ADD) - ADVERSITY_BASE_REDUCTION;
                double newRate = PRIORITY_MICRO_LAMPORTS_DEFAULT + Math.abs(rateToAdd);
                OpenBookConfig.setPriorityMicroLamports((int) newRate);
            } else {
                OpenBookConfig.useDefaultPriorityMicroLamports();
            }
            float percentageChangeFromLastAsk = 1.00f - (lastPlacedAskPrice / nextPlacedAskPrice);
            boolean asxAskPresent = false; // If ASX isn't quoting (BELOW YOU) raise proirity rate
            for (Order askOrder : askOrders) {
                if (askOrder.getOwner().equals(SPACE_MONKEY) && askOrder.getFloatPrice() <= nextPlacedAskPrice &&
                        (askOrder.getFloatQuantity() * askOrder.getFloatPrice() >= 700)) {
                    asxAskPresent = true;
                    OpenBookConfig.setPriorityMicroLamports(getPriorityMicroLamports() + 15_000);
                }
            }
            Order ourCurrentAsk = null;
            for (Order askOrder : askOrders) {
                if (askOrder.getOwner().equals(SOL_USDC_OOA)) {
                    if (ourCurrentAsk == null || askOrder.getFloatPrice() <= ourCurrentAsk.getFloatPrice()) {
                        // use this as our best ask
                        ourCurrentAsk = askOrder;
                    }
                }
            }
            boolean isOurAskInModel = true;
            if (ourCurrentAsk != null) {
                float ourCurrentPrice = ourCurrentAsk.getFloatPrice();
                float expectedAskPrice = nextPlacedAskPrice;
                float difference = Math.abs(expectedAskPrice - ourCurrentPrice);
                float bpsDifference = difference / expectedAskPrice;  // 0.01 cent / 25$
                // half a bip of mispricing allowed for float fuckery
                if (bpsDifference >= ALLOWED_PRICING_BPS_MISMATCH) {
                    isOurAskInModel = false;
                }
            }
            if (!shouldCancelAsk) {
                // Add fee for fresh placements
                OpenBookConfig.setPriorityMicroLamports((int) ((double) getPriorityMicroLamports() * 1.55));
            }
            // Cheat codes
            final List<PublicKey> askSharps = new ArrayList<>(KNOWN_SHARPS);
            final float nextPlacedAskFinal = nextPlacedAskPrice;
            askSharps.add(PublicKey.valueOf("7pYyuhKrMTswQqZ9eXx813Qsx99yzvnbaUD3mUvX7wFm")); // ground
            Optional<Order> jumpsBestAsk = askOrders.stream()
                    .filter(order -> order.getFloatPrice() < nextPlacedAskFinal)
                    .filter(order -> !order.getOwner().equals(SOL_USDC_OOA))
                    .filter(order -> askSharps.contains(order.getOwner()))
                    .max((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .stream().findAny();
            if (jumpsBestAsk.isPresent()) {
                float jumpsPrice = jumpsBestAsk.get().getFloatPrice();
                if (Math.abs(jumpsPrice - nextPlacedAskPrice) <= 0.0058 && jumpsPrice <= nextPlacedAskPrice) {
                    nextPlacedAskPrice -= 0.0059f;
                }

            }
            // API tuning
            if (ASK_API_TUNING_FACTOR != 1f) {
                nextPlacedAskPrice = nextPlacedAskPrice * ASK_API_TUNING_FACTOR;
                log.info("Tuning ask: " + nextPlacedAskPrice + ", " + ASK_API_TUNING_FACTOR);
            }
            // Event Queue size-based tuning. If EQ >= 30 length, widen ask 1 bps
            // 30 to be replaced by standard deviation or moving average
//            int eventQueueSize = solUsdcMarket.getEventQueue().getEvents().size();
//            if (eventQueueSize >= EVENT_QUEUE_SIZE_THRESHOLD_FOR_WIDEN) {
//                float newAskPrice = nextPlacedAskPrice * EVENT_QUEUE_SIZE_ASK_WIDEN;
//                log.info("EQ size: Wide ask " + nextPlacedAskPrice + " to " + newAskPrice + ", # = " + eventQueueSize);
//                nextPlacedAskPrice = newAskPrice;
//            }

            // Only place ask if we haven't placed, or the change is >= 0.1% change
            if (lastPlacedAskPrice == 0 || (Math.abs(percentageChangeFromLastAsk) >= MIN_MIDPOINT_CHANGE) ||
                    (!asxAskPresent && durationSinceAsk >= 1) || !isOurAskInModel ) {
                if (nextPlacedAskPrice <= lastPlacedBidPrice * (1 + CROSS_DETECTION_PADDING) /* padding */  && (durationSinceAsk <= 1)) {
                    log.info("SOL Ask cross: " + nextPlacedAskPrice + ", last bid: " + lastPlacedBidPrice);
                } else {
                    placeSolAsk(SOL_ASK_AMOUNT, nextPlacedAskPrice, shouldCancelAsk);
                    lastPlacedAskPrice = nextPlacedAskPrice;
                }
            }
        }
    }

    @Scheduled(
            initialDelay = OpenBookConfig.LEAN_CALCULATION_INITIAL_DELAY,
            fixedRate = OpenBookConfig.LEAN_CALCULATION_INTERVAL
    )
    public void updateLeanSizes() {
        // Lean WSOL is USDC balance is low.
        OpenBookConfig.USDC_BALANCE = getUsdcBalance();
        OpenBookConfig.WSOL_BALANCE = getWSolBalance();

        if (OpenBookConfig.USDC_BALANCE.isPresent()) {
            double amount = OpenBookConfig.USDC_BALANCE.get();
            if (amount <= USDC_THRESHOLD_TO_LEAN_WSOL) {
                double leanFactor = generateLeanFactor("WSOL");
                SOL_ASK_AMOUNT = OpenBookConfig.SOL_QUOTE_SIZE * (float) leanFactor;
                if (OpenBookConfig.WSOL_BALANCE.isPresent() && OpenBookConfig.WSOL_BALANCE.get() <= SOL_ASK_AMOUNT) {
                    SOL_ASK_AMOUNT = OpenBookConfig.SOL_QUOTE_SIZE;
                } else {
                    GIGA_LEANING = false;
                }

                // Tighten if we have high WSOL inventory (half units),  Improvement: As a function, not constant
                float spreadTightenFactor = 0.99985f;
                if (OpenBookConfig.WSOL_BALANCE.isPresent()) {
                    if (OpenBookConfig.WSOL_BALANCE.get() >= (OpenBookConfig.SOL_QUOTE_SIZE * TARGET_MAX_UNITS) / 2) {
                        if (OpenBookConfig.WSOL_BALANCE.get() > OpenBookConfig.SOL_QUOTE_SIZE) {
                            GIGA_LEANING = true;
                        }
                    }
                }

                // Tighten spread too
                ASK_SPREAD_MULTIPLIER = DEFAULT_ASK_SPREAD_MULTIPLIER * spreadTightenFactor;
                // set last placed Ask to zero, so it re-quotes
                lastPlacedAskPrice = 0f;
                IS_WSOL_LEANING = true;
            } else {
                SOL_ASK_AMOUNT = SOL_QUOTE_SIZE;
                IS_WSOL_LEANING = false;
            }
        }

        // Lean USDC is WSOL balance is low.
        if (OpenBookConfig.WSOL_BALANCE.isPresent()) {
            double amount = OpenBookConfig.WSOL_BALANCE.get();
            if (amount <= OpenBookConfig.WSOL_THRESHOLD_TO_LEAN_USDC) {
                BID_SPREAD_MULTIPLIER = DEFAULT_BID_SPREAD_MULTIPLIER * 1.0012f;  // Tighten spread only
                lastPlacedBidPrice = 0f;  // set last placed Bid to zero, so it re-quotes
            } else {
                BID_SPREAD_MULTIPLIER = DEFAULT_BID_SPREAD_MULTIPLIER;
            }
        }
    }

    @Scheduled(fixedRate = OpenBookConfig.ORDER_BOOK_POLL_INTERVAL)
    public void updateOb() {
        try {
            solUsdcMarket.reload(OpenBookConfig.solUsdcMarketBuilder);
        } catch (Exception ex) {
            log.debug("OB load error: " + ex.getClass().getName());
        }
    }

    private void placeSolAsk(float solAmount, float price, boolean cancel) {
        if (price <= lastPlacedBidPrice) {
            log.info("SOL Ask cross: " + price + ", last bid: " + lastPlacedBidPrice);
            return;
        }

        final Transaction placeTx = new Transaction();
        placeTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitPrice(
                        PRIORITY_MICRO_LAMPORTS
                )
        );
        placeTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitLimit(
                        PRIORITY_UNITS
                )
        );
        placeTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        float inputPrice = price;
        int smaPeriod = 4;
        // Price SMA calculation
        synchronized (askSmaValues) {
            if (askSmaValues.size() < smaPeriod) {
                askSmaValues.push(price);
                inputPrice = price;
            } else if (askSmaValues.size() == smaPeriod) {
                askSmaValues.removeLast();
                askSmaValues.addFirst(inputPrice);
                inputPrice = askSmaValues.stream().reduce(0f, Float::sum) / (float) smaPeriod;
                //log.info("SMA ask: " + inputPrice);
            }
        }
        Order askOrder = Order.builder()
                .buy(false)
                .clientOrderId(OpenBookConfig.ASK_CLIENT_ID)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY) // ONLY DO LIMIT WHEN WE HAVE CHECKANDSETSEQUENCENumber
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.ABORT_TRANSACTION)
                .floatPrice(inputPrice)
                .floatQuantity(solAmount)
                .build();
        serumManager.setOrderPrices(askOrder, solUsdcMarket);
        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            OpenBookConfig.ASK_CLIENT_ID
                    )
            );
        } else {
            lastAskTimestamp = Instant.now();
        }
        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        SOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.WSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        OpenBookConfig.mmAccount,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        SOL_USDC_OOA,
                        solUsdcMarket,
                        askOrder
                )
        );
        Runnable runnable = () -> {
            try {
                String orderTx = rpcClient.getApi().sendTransaction(placeTx, OpenBookConfig.mmAccount);
                log.info("SOL Ask: $" + askOrder.getFloatPrice() + " x " + askOrder.getFloatQuantity() + ": " +
                        orderTx + ", Pyth: " + pythPricingSource.getSolMidpointPrice().orElse(0.0f) + " +/-" +
                        pythPricingSource.getSolPriceConfidence().orElse(0.0f));
            } catch (RpcException e) {
                log.error("SOL OrderTx Error = " + e.getMessage());
            }
        };
        orderExecutorService.submit(runnable);
    }

    private void placeUsdcBid(float amount, float price, boolean cancel) {
        final Transaction placeTx = new Transaction();
        placeTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitPrice(
                        PRIORITY_MICRO_LAMPORTS
                )
        );
        placeTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitLimit(
                        PRIORITY_UNITS
                )
        );
        placeTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        float inputPrice = price;
        int smaPeriod = 5;
        // Price SMA calculation
        synchronized (smaValues) {
            if (smaValues.size() < smaPeriod) {
                smaValues.push(price);
                inputPrice = price;
            } else if (smaValues.size() == smaPeriod) {
                smaValues.removeLast();
                smaValues.addFirst(inputPrice);
                inputPrice = smaValues.stream().reduce(0f, Float::sum) / (float) smaPeriod;
                //log.info("SMA bid: " + inputPrice);
            }
        }
        Order bidOrder = Order.builder()
                .buy(true)
                .clientOrderId(OpenBookConfig.BID_CLIENT_ID)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY)  //limit requires setSequence smart contract
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.ABORT_TRANSACTION)
                .floatPrice(inputPrice)
                .floatQuantity(amount)
                .build();
        serumManager.setOrderPrices(bidOrder, solUsdcMarket);
        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            OpenBookConfig.BID_CLIENT_ID
                    )
            );
        } else {
            lastBidTimestamp = Instant.now();
        }
        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        SOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.WSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        OpenBookConfig.mmAccount,
                        OpenBookConfig.USDC_QUOTE_WALLET,
                        SOL_USDC_OOA,
                        solUsdcMarket,
                        bidOrder
                )
        );
        Runnable runnable = () -> {
            try {
                String orderTx = rpcClient.getApi().sendTransaction(placeTx, OpenBookConfig.mmAccount);
                log.info("USDC Bid: $" + bidOrder.getFloatPrice() + " x " + bidOrder.getFloatQuantity() + ": " +
                        orderTx + ", Pyth: " + pythPricingSource.getSolMidpointPrice().orElse(0.0f) + " +/-" +
                        pythPricingSource.getSolPriceConfidence().orElse(0.0f));
            } catch (RpcException e) {
                log.error("BID OrderTx Error = " + e.getMessage());
            }
        };
        orderExecutorService.submit(runnable);
    }

    public void hardCancelSingleBid() {
        long durationSinceBidHardCxl = Math.abs(Duration.between(Instant.now(),
                lastHardCancelBidTimestamp).toSeconds());

        if (durationSinceBidHardCxl >= 8) {
            // do hard cxl
            Account sessionWsolAccount = new Account();
            Transaction newTx = new Transaction();
            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            210_000
                    )
            );
            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            PRIORITY_UNITS * 2
                    )
            );
            // Create WSOL account for session. 0.5 to start
            newTx.addInstruction(
                    SystemProgram.createAccount(
                            OpenBookConfig.mmAccount.getPublicKey(),
                            sessionWsolAccount.getPublicKey(),
                            (long) (0.01 * 1000000000.0) + 2039280, //.05 SOL
                            165,
                            TokenProgram.PROGRAM_ID
                    )
            );
            newTx.addInstruction(
                    TokenProgram.initializeAccount(
                            sessionWsolAccount.getPublicKey(),
                            SerumUtils.WRAPPED_SOL_MINT,
                            OpenBookConfig.mmAccount.getPublicKey()
                    )
            );
            newTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            OpenBookConfig.BID_CLIENT_ID
                    )
            );
            newTx.addInstruction(
                    SerumProgram.settleFunds(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            sessionWsolAccount.getPublicKey(), //random wsol acct for settles
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe")
                    )
            );
            newTx.addInstruction(TokenProgram.closeAccount(
                    sessionWsolAccount.getPublicKey(),
                    OpenBookConfig.mmAccount.getPublicKey(),
                    OpenBookConfig.mmAccount.getPublicKey()
            ));
            try {
                log.info("hardSettle cxl = " + rpcClient.getApi().sendTransaction(
                        newTx,
                        List.of(
                                OpenBookConfig.mmAccount,
                                sessionWsolAccount
                        ),
                        rpcClient.getApi().getRecentBlockhash(Commitment.PROCESSED)
                ));
            } catch (RpcException e) {
                log.error("hardCXL BID error: " + e.getMessage());
            }
        }
        lastHardCancelBidTimestamp = Instant.now();
    }

    public void hardCancelSingleAsk() {
        long durationSinceAskHardCxl = Math.abs(Duration.between(Instant.now(),
                lastHardCancelAskTimestamp).toSeconds());

        if (durationSinceAskHardCxl >= 8) {
            // do hard cxl
            Account sessionWsolAccount = new Account();
            Transaction newTx = new Transaction();
            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            210_000
                    )
            );

            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            PRIORITY_UNITS * 2
                    )
            );

            // Create WSOL account for session. 0.5 to start
            newTx.addInstruction(
                    SystemProgram.createAccount(
                            OpenBookConfig.mmAccount.getPublicKey(),
                            sessionWsolAccount.getPublicKey(),
                            (long) (0.01 * 1000000000.0) + 2039280, //.05 SOL
                            165,
                            TokenProgram.PROGRAM_ID
                    )
            );

            newTx.addInstruction(
                    TokenProgram.initializeAccount(
                            sessionWsolAccount.getPublicKey(),
                            SerumUtils.WRAPPED_SOL_MINT,
                            OpenBookConfig.mmAccount.getPublicKey()
                    )
            );

            newTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            OpenBookConfig.ASK_CLIENT_ID
                    )
            );

            newTx.addInstruction(
                    SerumProgram.settleFunds(
                            solUsdcMarket,
                            SOL_USDC_OOA,
                            OpenBookConfig.mmAccount.getPublicKey(),
                            sessionWsolAccount.getPublicKey(), //random wsol acct for settles
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe")
                    )
            );

            newTx.addInstruction(TokenProgram.closeAccount(
                    sessionWsolAccount.getPublicKey(),
                    OpenBookConfig.mmAccount.getPublicKey(),
                    OpenBookConfig.mmAccount.getPublicKey()
            ));


            try {
                log.info("ASK hardSettle cxl = " + rpcClient.getApi().sendTransaction(
                        newTx,
                        List.of(
                                OpenBookConfig.mmAccount,
                                sessionWsolAccount
                        ),
                        rpcClient.getApi().getRecentBlockhash(Commitment.PROCESSED)
                ));


            } catch (RpcException e) {
                log.error("hardCXL ASK error: " + e.getMessage());
            }
        }

        lastHardCancelAskTimestamp = Instant.now();
    }

    private void initializeWrappedSolAccount() {
        Account sessionWsolAccount = new Account();
        Transaction newTx = new Transaction();
        newTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitPrice(
                        1811_500_000
                )
        );
        newTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitLimit(
                        10_700
                )
        );
        double startingAmount = OpenBookConfig.WSOL_STARTING_AMOUNT; // get native SOL balance
        try {
            double amount = (double) dataRpcClient.getApi().getAccountInfo(
                    PublicKey.valueOf("mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN")
            , Map.of("commitment", Commitment.PROCESSED)).getValue().getLamports() / 1_000_000_000.0;
            if (amount >= 0 && amount <= 10000) {
                // Use 95% of our SOL, or base amount, whichever is larger
                startingAmount = Math.max(amount * .95, OpenBookConfig.WSOL_STARTING_AMOUNT);
                log.info("Starting WSOL: " + startingAmount);
            }
        } catch (RpcException e) {
            throw new RuntimeException(e);
        }
        // Create WSOL account for session. 0.5 to start
        newTx.addInstruction(
                SystemProgram.createAccount(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        sessionWsolAccount.getPublicKey(),
                        (long) (startingAmount * 1000000000.0) + 5039280, //.05 SOL
                        165,
                        TokenProgram.PROGRAM_ID
                )
        );
        newTx.addInstruction(
                TokenProgram.initializeAccount(
                        sessionWsolAccount.getPublicKey(),
                        SerumUtils.WRAPPED_SOL_MINT,
                        OpenBookConfig.mmAccount.getPublicKey()
                )
        );
        try {
            String txId = rpcClient.getApi().sendTransaction(newTx, List.of(OpenBookConfig.mmAccount, sessionWsolAccount), null);
            OpenBookConfig.WSOL_BASE_WALLET = sessionWsolAccount.getPublicKey();
            log.info("WSOL Base Wallet = " + OpenBookConfig.WSOL_BASE_WALLET.toBase58() + ", TXID + " + txId);
        } catch (RpcException e) {
            log.error(e.getMessage());
        }
    }

    private Optional<Double> getUsdcBalance() {
        try {
            double amount = dataRpcClient.getApi().getTokenAccountBalance(
                            OpenBookConfig.USDC_QUOTE_WALLET,
                            Commitment.PROCESSED
                    )
                    .getUiAmount();

            // For now, always assume a huge bid is sitting (2 units of notional)
            double assumedQuotedSize = 1.5;
            amount -= ((SOL_QUOTE_SIZE * assumedQuotedSize) * START_SOL_PRICE);

            int nonNegativeSafeMinQuantity = 100 + ThreadLocalRandom.current().nextInt(1, 50);
            // to avoid negative by any possibility
            amount = Math.max(amount, nonNegativeSafeMinQuantity);

            // todo Add balance from the current USDC quotes

            return Optional.of(amount);
        } catch (RpcException e) {
            log.error("Unable to get USDC balance: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> getWSolBalance() {
        try {
            double amount = dataRpcClient.getApi().getTokenAccountBalance(
                            OpenBookConfig.WSOL_BASE_WALLET,
                            Commitment.PROCESSED
                    )
                    .getUiAmount();
            return Optional.of(amount);
        } catch (RpcException e) {
            log.error("Unable to get WSOL balance: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Account readMmAccountFromPrivateKey() {
        final Account mmAccount;
        ClassPathResource resource = new ClassPathResource(
                "/mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json",
                SerumApplication.class
        );
        try (InputStream inputStream = resource.getInputStream()) {
            String privateKeyJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            mmAccount = Account.fromJson(privateKeyJson);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return mmAccount;
    }

    private void eventLoopWithCatch() {
        try {
            solUsdcEventLoop();
        } catch (Exception ex) {
            log.error("Exception during event loop: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void start() {
        log.info(this.getClass().getSimpleName() + " started.");
        final Runnable solUsdcEventLoopRunnable = this::eventLoopWithCatch;
        executorService.scheduleAtFixedRate(
                solUsdcEventLoopRunnable,
                OpenBookConfig.EVENT_LOOP_INITIAL_DELAY_MS,
                OpenBookConfig.EVENT_LOOP_DURATION_MS,
                TimeUnit.MILLISECONDS
        );
    }

    public void marketSell(float floatSize) {
        final Transaction mktSellTx = new Transaction();
        mktSellTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitPrice(
                        PRIORITY_MICRO_LAMPORTS
                )
        );
        mktSellTx.addInstruction(
                ComputeBudgetProgram.setComputeUnitLimit(
                        PRIORITY_UNITS
                )
        );
        mktSellTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );

        Order askOrder = Order.builder()
                .buy(false)
                .clientOrderId(OpenBookConfig.ASK_CLIENT_ID + ThreadLocalRandom.current().nextInt(1, 500))
                .orderTypeLayout(OrderTypeLayout.IOC) // ONLY DO LIMIT WHEN WE HAVE CHECKANDSETSEQUENCENumber
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.CANCEL_PROVIDE)
                .floatPrice((float) this.bestBidPrice * .9998f)
                .floatQuantity(floatSize)
                .build();
        serumManager.setOrderPrices(askOrder, solUsdcMarket);
        // Settle - base wallet gets created first then closed after
        mktSellTx.addInstruction(
                SerumProgram.consumeEvents(
                        OpenBookConfig.mmAccount.getPublicKey(),
                        List.of(SOL_USDC_OOA),
                        solUsdcMarket,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );

        mktSellTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        SOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.WSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        mktSellTx.addInstruction(
                SerumProgram.placeOrder(
                        OpenBookConfig.mmAccount,
                        OpenBookConfig.WSOL_BASE_WALLET,
                        SOL_USDC_OOA,
                        solUsdcMarket,
                        askOrder
                )
        );
        mktSellTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        SOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.WSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );

        Runnable runnable = () -> {
            try {
                String orderTx = rpcClient.getApi().sendTransaction(mktSellTx, OpenBookConfig.mmAccount);
                log.info("MKT SELL: " + floatSize + " @ " + ((float) this.bestBidPrice * .9995f) + ", " + orderTx);
            } catch (RpcException e) {
                log.error("MKT SELL OrderTx Error = " + e.getMessage());
            }
        };
        orderExecutorService.submit(runnable);
    }

    // @Scheduled(fixedRate = 5_000L)
    public void hardCxlDetectionLoop() {
        if (bidOrders.stream().filter(order -> order.getOwner().equals(SOL_USDC_OOA)).count() > 1) {
            hardCancelSingleBid();
        }
        if (askOrders.stream().filter(order -> order.getOwner().equals(SOL_USDC_OOA)).count() > 1) {
            hardCancelSingleAsk();
        }
    }
}
