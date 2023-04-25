package com.mmorrell.strategies.openbook.sol;

import com.google.common.collect.ImmutableList;
import com.mmorrell.SerumApplication;
import com.mmorrell.config.OpenBookConfig;
import com.mmorrell.pricing.JupiterPricingSource;
import com.mmorrell.pricing.PythPricingSource;
import com.mmorrell.serum.manager.SerumManager;
import com.mmorrell.serum.model.MarketBuilder;
import com.mmorrell.serum.model.Order;
import com.mmorrell.serum.model.OrderBook;
import com.mmorrell.serum.model.OrderTypeLayout;
import com.mmorrell.serum.model.SelfTradeBehaviorLayout;
import com.mmorrell.serum.program.SerumProgram;
import com.mmorrell.strategies.Strategy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.ComputeBudgetProgram;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.mmorrell.config.OpenBookConfig.ALLOWED_PRICING_BPS_MISMATCH;
import static com.mmorrell.config.OpenBookConfig.ASK_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.BID_API_TUNING_FACTOR;
import static com.mmorrell.config.OpenBookConfig.CROSS_DETECTION_PADDING;
import static com.mmorrell.config.OpenBookConfig.JITOSOL_USDC_MARKET_ID;
import static com.mmorrell.config.OpenBookConfig.KNOWN_FISH;
import static com.mmorrell.config.OpenBookConfig.KNOWN_SHARPS;
import static com.mmorrell.config.OpenBookConfig.MIN_MIDPOINT_CHANGE;
import static com.mmorrell.config.OpenBookConfig.NEW_ORDER_DELAY_DURATION_SECONDS;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_MICRO_LAMPORTS;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_UNITS;
import static com.mmorrell.config.OpenBookConfig.SOL_ASK_AMOUNT;
import static com.mmorrell.config.OpenBookConfig.USDC_BID_AMOUNT_IN_WSOL;
import static com.mmorrell.config.OpenBookConfig.jitoSolUsdcMarket;

// @Component
@Slf4j
@Getter
public class OpenBookJitoSolUsdc extends Strategy {
    private final RpcClient rpcClient;
    private final RpcClient dataRpcClient;
    private final SerumManager serumManager;
    private final ScheduledExecutorService executorService;
    private final ExecutorService orderExecutorService = Executors.newFixedThreadPool(2);
    private final PythPricingSource pythPricingSource;
    private final JupiterPricingSource jupiterPricingSource;
    private double bestBidPrice;
    private double bestAskPrice;
    private float lastPlacedBidPrice = 0.0f, lastPlacedAskPrice = 0.0f;
    private static Instant lastBidTimestamp = Instant.now();
    private static Instant lastAskTimestamp = Instant.now();
    private static final Deque<Float> smaValues = new ArrayDeque<>(10);
    private static final Deque<Float> askSmaValues = new ArrayDeque<>(5);
    private List<Order> bidOrders;
    private List<Order> askOrders;

    private static final float JITOSOL_BID_SPREAD = 0.996f;
    private static final float JITOSOL_ASK_SPREAD = 1.002f;
    private static final String JUP_SYMBOL = "JitoSOL";

    public OpenBookJitoSolUsdc(final SerumManager serumManager,
                               final RpcClient rpcClient,
                               @Qualifier("data") final RpcClient dataRpcClient,
                               final PythPricingSource pythPricingSource,
                               final JupiterPricingSource jupiterPricingSource) {
        this.executorService = Executors.newScheduledThreadPool(2);
        this.serumManager = serumManager;
        this.rpcClient = rpcClient;
        this.dataRpcClient = dataRpcClient;
        this.pythPricingSource = pythPricingSource;
        this.jupiterPricingSource = jupiterPricingSource;
        OpenBookConfig.mmAccount = readMmAccountFromPrivateKey();
        OpenBookConfig.jitoSolUsdcMarketBuilder = new MarketBuilder()
                .setClient(dataRpcClient)
                .setPublicKey(JITOSOL_USDC_MARKET_ID)
                .setRetrieveOrderBooks(true);
        jitoSolUsdcMarket = OpenBookConfig.jitoSolUsdcMarketBuilder.build();
        this.bestBidPrice = jitoSolUsdcMarket.getBidOrderBook().getBestBid().getFloatPrice();
        this.bestAskPrice = jitoSolUsdcMarket.getAskOrderBook().getBestAsk().getFloatPrice();
        updateOb();
    }

    private void jitoSolUsdcEventLoop() {
        // Whole block synchronized, dont want diff states sent at same time. Orders are async/instant / non-blocking
        synchronized (this) {
            OrderBook bidOrderBook;
            OrderBook askOrderBook;
            Optional<Float> pythSolPrice;
            bidOrderBook = jitoSolUsdcMarket.getBidOrderBook();
            bidOrders = ImmutableList.copyOf(bidOrderBook.getOrders());
            askOrderBook = jitoSolUsdcMarket.getAskOrderBook();
            askOrders = ImmutableList.copyOf(askOrderBook.getOrders());

            Optional<Double> jupiterPrice = jupiterPricingSource.getCachedPrice(JUP_SYMBOL);
            if (jupiterPrice.isEmpty()) {
                return;
            }
            pythSolPrice = Optional.of(jupiterPrice.get().floatValue());
            final Optional<Order> topOfBookFish = askOrders.stream()
                    .filter(order -> KNOWN_FISH.contains(order.getOwner()))
                    .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()));
            this.bestBidPrice = pythSolPrice.get();
            this.bestAskPrice = pythSolPrice.get();

            if (topOfBookFish.isPresent()) {
                Order fishOrder = topOfBookFish.get(); // Average it with the next best quote
                if (fishOrder.getFloatPrice() <= bestAskPrice) {
                    Optional<Order> topOfBookNotFish = askOrders.stream()
                            .filter(order -> !KNOWN_FISH.contains(order.getOwner()))
                            .filter(order -> !order.getOwner().equals(OpenBookConfig.JITOSOL_USDC_OOA)) // not us either
                            .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()));
                    topOfBookNotFish.ifPresent(order -> this.bestAskPrice =
                            (fishOrder.getFloatPrice() + order.getFloatPrice()) / 2.0);
                }
            }
            boolean isCancelBid, isReadyToNewBid, shouldCancelBid;
            synchronized (this) {
                isCancelBid = bidOrders.stream().anyMatch(order -> order.getOwner().equals(OpenBookConfig.JITOSOL_USDC_OOA));
                isReadyToNewBid = Math.abs(Duration.between(Instant.now(), lastBidTimestamp).toSeconds()) >=
                        NEW_ORDER_DELAY_DURATION_SECONDS;
                shouldCancelBid = isCancelBid || !isReadyToNewBid;
            }
            float nextPlacedBidPrice = (float) bestBidPrice * JITOSOL_BID_SPREAD;
            long durationSinceBid = Math.abs(Duration.between(Instant.now(), lastBidTimestamp).toSeconds());
            float percentageChangeFromLastBid = 1.00f - (lastPlacedBidPrice / nextPlacedBidPrice);
            boolean asxBidPresent = false;
            Order ourCurrentBid = null;
            for (Order bidOrder : bidOrders) {
                if (bidOrder.getOwner().equals(OpenBookConfig.JITOSOL_USDC_OOA)) {
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
                }
            }
            float bidSmoothingFactor = 0.9999f;
            float smoothedBidPrice = nextPlacedBidPrice * bidSmoothingFactor;
            Optional<Order> jumpsBestBid = askOrders.stream()
                    .filter(order -> KNOWN_SHARPS.contains(order.getOwner()))
                    .max((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .stream().findFirst();
            if (jumpsBestBid.isPresent()) {
                float jumpsPrice = jumpsBestBid.get().getFloatPrice();
                if (Math.abs(jumpsPrice - smoothedBidPrice) <= 0.0056 && jumpsPrice >= smoothedBidPrice) {
                    smoothedBidPrice += 0.0057f;
                    smoothedBidPrice = smoothedBidPrice * bidSmoothingFactor; // another smoothing
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
                    placeUsdcBid(USDC_BID_AMOUNT_IN_WSOL / 2, incomingBid, shouldCancelBid);
                    lastPlacedBidPrice = incomingBid;
                }
            }
            // Asks
            boolean isCancelAsk, isReadyToPlaceNewAsk, shouldCancelAsk;
            synchronized (this) {
                isCancelAsk = askOrders.stream().anyMatch(order -> order.getOwner().equals(OpenBookConfig.JITOSOL_USDC_OOA));
                isReadyToPlaceNewAsk = Math.abs(Duration.between(Instant.now(), lastAskTimestamp).toSeconds()) >=
                        NEW_ORDER_DELAY_DURATION_SECONDS;
                shouldCancelAsk = isCancelAsk || !isReadyToPlaceNewAsk;
            }
            float nextPlacedAskPrice = (float) bestAskPrice * JITOSOL_ASK_SPREAD;
            long durationSinceAsk = Math.abs(Duration.between(Instant.now(), lastAskTimestamp).toSeconds());
            // If adversity in past 4 sec, widen
            // If recent ask adversity, use a massive priority fee.
            float percentageChangeFromLastAsk = 1.00f - (lastPlacedAskPrice / nextPlacedAskPrice);
            boolean asxAskPresent = false; // If ASX isn't quoting (BELOW YOU) raise proirity rate
            Order ourCurrentAsk = null;
            for (Order askOrder : askOrders) {
                if (askOrder.getOwner().equals(OpenBookConfig.JITOSOL_USDC_OOA)) {
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
            // Cheat codes
            final List<PublicKey> askSharps = new ArrayList<>(KNOWN_SHARPS);
            askSharps.add(PublicKey.valueOf("7pYyuhKrMTswQqZ9eXx813Qsx99yzvnbaUD3mUvX7wFm")); // ground
            Optional<Order> jumpsBestAsk = askOrders.stream()
                    .filter(order -> askSharps.contains(order.getOwner()))
                    .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()))
                    .stream().findFirst();
            if (jumpsBestAsk.isPresent()) {
                float jumpsPrice = jumpsBestAsk.get().getFloatPrice();
                if (Math.abs(jumpsPrice - nextPlacedAskPrice) <= 0.0053 && jumpsPrice <= nextPlacedAskPrice) {
                    nextPlacedAskPrice -= 0.0054f;
                }

            }
            // API tuning
            if (ASK_API_TUNING_FACTOR != 1f) {
                nextPlacedAskPrice = nextPlacedAskPrice * ASK_API_TUNING_FACTOR;
                log.info("Tuning ask: " + nextPlacedAskPrice + ", " + ASK_API_TUNING_FACTOR);
            }
            // Only place ask if we haven't placed, or the change is >= 0.1% change
            if (lastPlacedAskPrice == 0 || (Math.abs(percentageChangeFromLastAsk) >= MIN_MIDPOINT_CHANGE) ||
                    (!asxAskPresent && durationSinceAsk >= 1) || !isOurAskInModel) {
                if (nextPlacedAskPrice <= lastPlacedBidPrice * (1 + CROSS_DETECTION_PADDING) /* padding */ && (durationSinceAsk <= 1)) {
                    log.info(JUP_SYMBOL + " Ask cross: " + nextPlacedAskPrice + ", last bid: " + lastPlacedBidPrice);
                } else {
                    placeBaseAsk(SOL_ASK_AMOUNT / 2, nextPlacedAskPrice, shouldCancelAsk);
                    lastPlacedAskPrice = nextPlacedAskPrice;
                }
            }
        }
    }

    @Scheduled(fixedRate = OpenBookConfig.ORDER_BOOK_POLL_INTERVAL * 5)
    public void updateOb() {
        try {
            jitoSolUsdcMarket.reload(OpenBookConfig.jitoSolUsdcMarketBuilder);
        } catch (Exception ex) {
            log.debug(ex.getMessage());
        }
    }

    private void placeBaseAsk(float solAmount, float price, boolean cancel) {
        if (price <= lastPlacedBidPrice) {
            log.info(JUP_SYMBOL + " Ask cross: " + price + ", last bid: " + lastPlacedBidPrice);
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
                        List.of(OpenBookConfig.JITOSOL_USDC_OOA),
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        float inputPrice = price;
        int smaPeriod = 12;
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
        serumManager.setOrderPrices(askOrder, jitoSolUsdcMarket);
        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            jitoSolUsdcMarket,
                            OpenBookConfig.JITOSOL_USDC_OOA,
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
                        List.of(OpenBookConfig.JITOSOL_USDC_OOA),
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.JITOSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        OpenBookConfig.mmAccount,
                        OpenBookConfig.JITOSOL_BASE_WALLET,
                        OpenBookConfig.JITOSOL_USDC_OOA,
                        jitoSolUsdcMarket,
                        askOrder
                )
        );
        Runnable runnable = () -> {
            try {
                String orderTx = rpcClient.getApi().sendTransaction(placeTx, OpenBookConfig.mmAccount);
                log.info(JUP_SYMBOL + " Ask: $" + askOrder.getFloatPrice() + " x " + askOrder.getFloatQuantity() + ":" +
                        " " +
                        orderTx + ", Pyth: " + pythPricingSource.getSolMidpointPrice().orElse(0.0f) + " +/-" +
                        pythPricingSource.getSolPriceConfidence().orElse(0.0f));
            } catch (RpcException e) {
                log.error(JUP_SYMBOL + " OrderTx Error = " + e.getMessage());
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
                        List.of(OpenBookConfig.JITOSOL_USDC_OOA),
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        float inputPrice = price;
        int smaPeriod = 6;
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
        serumManager.setOrderPrices(bidOrder, jitoSolUsdcMarket);
        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            jitoSolUsdcMarket,
                            OpenBookConfig.JITOSOL_USDC_OOA,
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
                        List.of(OpenBookConfig.JITOSOL_USDC_OOA),
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_BASE_WALLET,
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        jitoSolUsdcMarket,
                        OpenBookConfig.JITOSOL_USDC_OOA,
                        OpenBookConfig.mmAccount.getPublicKey(),
                        OpenBookConfig.JITOSOL_BASE_WALLET, //random wsol acct for settles
                        OpenBookConfig.USDC_QUOTE_WALLET
                )
        );
        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        OpenBookConfig.mmAccount,
                        OpenBookConfig.USDC_QUOTE_WALLET,
                        OpenBookConfig.JITOSOL_USDC_OOA,
                        jitoSolUsdcMarket,
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
            jitoSolUsdcEventLoop();
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
                OpenBookConfig.EVENT_LOOP_DURATION_MS * 20,
                TimeUnit.MILLISECONDS
        );
    }

    @Scheduled(fixedRate = 10_000L)
    public void jupiterPriceUpdateLoop() {
        String symbol = JUP_SYMBOL;
        Optional<Double> orcaPrice = jupiterPricingSource.getUsdcPriceForSymbol(symbol, 500);
        orcaPrice.ifPresent(aDouble -> jupiterPricingSource.updatePriceMap(symbol, aDouble));
        orcaPrice.ifPresent(aDouble -> log.info(symbol + " Price: " + aDouble));
    }
}
