package com.mmorrell.strategies.openbook.spl;

import com.mmorrell.SerumApplication;
import com.mmorrell.pricing.JupiterPricingSource;
import com.mmorrell.serum.manager.SerumManager;
import com.mmorrell.serum.model.Market;
import com.mmorrell.serum.model.MarketBuilder;
import com.mmorrell.serum.model.Order;
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
import org.p2p.solanaj.programs.MemoProgram;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.mmorrell.config.BeanConfig.MEMO;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_MICRO_LAMPORTS;
import static com.mmorrell.config.OpenBookConfig.PRIORITY_UNITS;
import static com.mmorrell.config.OpenBookConfig.USDC_THRESHOLD_TO_LEAN_WSOL;
import static com.mmorrell.config.OpenBookConfig.generateLeanFactor;


//@Component
@Slf4j
@Getter
public class OpenBookBonkUsdc extends Strategy {

    private static final int EVENT_LOOP_INITIAL_DELAY_MS = 0;
    private static final int EVENT_LOOP_DURATION_MS = 5000;

    private final RpcClient rpcClient;
    private final SerumManager serumManager;
    private final ScheduledExecutorService executorService;
    private final JupiterPricingSource jupiterPricingSource;

    // Finals
    private final Account mmAccount;
    private final Market market;
    private final MarketBuilder marketBuilder;
    public static final PublicKey MARKET_ID =
            new PublicKey("8PhnCfgqpgFM7ZJvttGdBVMXHuU4Q23ACxCvWkbs1M71");

    private static final PublicKey MARKET_OOA = new PublicKey("8UpnZCvSYjZnK6GkyJNEaSGaJXpfVwio8qgvLdBtJZS8");
    private static final PublicKey BASE_SPL_WALLET = new PublicKey("7yLq1mUfRaNksPwyUodw5gnatBmuiqtsUEJuEUwQtJV2");
    private static final PublicKey USDC_QUOTE_WALLET = new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe");
    private static final long BID_CLIENT_ID = 113371L;
    private static final long ASK_CLIENT_ID = 14201L;

    private static final long BASE_QUOTE_SIZE = 80_000_000;
    private static float ASK_AMOUNT = BASE_QUOTE_SIZE;
    private static float USDC_BID_AMOUNT = BASE_QUOTE_SIZE;
    private static final float ASK_SPREAD_MULTIPLIER = 1.0050f;
    private static final float BID_SPREAD_MULTIPLIER = 0.9960f;
    private static final float MIN_MIDPOINT_CHANGE = 0.0005f;

    private float lastPlacedBidPrice = 0.0f, lastPlacedAskPrice = 0.0f;

    private static final double RLB_THRESHOLD_TO_LEAN_USDC = BASE_QUOTE_SIZE;
    private static Optional<Double> USDC_BALANCE = Optional.empty();
    private static Optional<Double> BASE_BALANCE = Optional.empty();

    // Jupiter pricing
    private final String BASE_SYMBOL = "BONK";
    private final String bonkMint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";

    // Used to delay 2000ms on first order place.
    private static boolean firstLoadComplete = false;

    public OpenBookBonkUsdc(final SerumManager serumManager,
                            final RpcClient rpcClient,
                            final JupiterPricingSource jupiterPricingSource) {
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        this.jupiterPricingSource = jupiterPricingSource;

        this.serumManager = serumManager;
        this.rpcClient = rpcClient;

        this.marketBuilder = new MarketBuilder()
                .setClient(rpcClient)
                .setPublicKey(MARKET_ID)
                .setRetrieveOrderBooks(true);
        this.market = this.marketBuilder.build();

        // Load private key
        ClassPathResource resource = new ClassPathResource(
                "/mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json",
                SerumApplication.class
        );

        try (InputStream inputStream = resource.getInputStream()) {
            String privateKeyJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            this.mmAccount = Account.fromJson(privateKeyJson);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        jupiterPriceUpdateLoop();
    }

    @Override
    public void start() {
        log.info(this.getClass().getSimpleName() + " started.");

        updateLeanSizes();

        // Start loop
        executorService.scheduleAtFixedRate(
                () -> {
                    try {
                        // Get latest prices
                        market.reload(marketBuilder);

                        boolean isCancelBid =
                                market.getBidOrderBook().getOrders().stream().anyMatch(order -> order.getOwner().equals(MARKET_OOA));

                        // Jupiter bid pricing
                        Optional<Double> jupiterPriceOptional = jupiterPricingSource.getCachedPrice(bonkMint);
                        boolean hasPricingSource = jupiterPriceOptional.isPresent();
                        if (hasPricingSource) {
                            double jupiterPrice = jupiterPriceOptional.get();

                            float percentageChangeFromLastBid =
                                    1.00f - (lastPlacedBidPrice / ((float) jupiterPrice * BID_SPREAD_MULTIPLIER));

                            // Only place bid if we haven't placed, or the change is >= 0.1% change
                            if (lastPlacedBidPrice == 0 || (Math.abs(percentageChangeFromLastBid) >= MIN_MIDPOINT_CHANGE)) {
                                // Top of book bid
                                placeUsdcBid(
                                        USDC_BID_AMOUNT * 0.5f,
                                        (float) jupiterPrice * BID_SPREAD_MULTIPLIER,
                                        isCancelBid
                                );

                                // Bottom of book bid
                                placeUsdcSecondBid(
                                        USDC_BID_AMOUNT * 0.5f,
                                        (float) jupiterPrice * (BID_SPREAD_MULTIPLIER * 0.9f),
                                        isCancelBid
                                );

                                lastPlacedBidPrice = (float) jupiterPrice * BID_SPREAD_MULTIPLIER;
                            }

                            boolean isCancelAsk =
                                    market.getAskOrderBook().getOrders().stream().anyMatch(order -> order.getOwner().equals(MARKET_OOA));

                            float percentageChangeFromLastAsk =
                                    1.00f - (lastPlacedAskPrice / ((float) jupiterPrice * ASK_SPREAD_MULTIPLIER));

                            // Only place ask if we haven't placed, or the change is >= 0.1% change
                            if (lastPlacedAskPrice == 0 || (Math.abs(percentageChangeFromLastAsk) >= MIN_MIDPOINT_CHANGE)) {
                                placeTopBaseAsk(ASK_AMOUNT * 0.5f, (float) jupiterPrice * ASK_SPREAD_MULTIPLIER,
                                        isCancelAsk);

                                placeLowerBaseAsk(ASK_AMOUNT * 0.5f,
                                        (float) jupiterPrice * (ASK_SPREAD_MULTIPLIER * 1.1f),
                                        isCancelAsk);
                                lastPlacedAskPrice = (float) jupiterPrice * ASK_SPREAD_MULTIPLIER;
                            }

                            if (!firstLoadComplete) {
                                try {
                                    log.info("Sleeping 2000ms...");
                                    Thread.sleep(2000L);
                                    log.info("Fist load complete.");
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                                firstLoadComplete = true;
                            }
                        } else {
                            log.error("No " + BASE_SYMBOL + " pricing source, skipping.");
                        }
                    } catch (Exception ex) {
                        log.error("Unhandled exception during event loop: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                },
                EVENT_LOOP_INITIAL_DELAY_MS,
                EVENT_LOOP_DURATION_MS,
                TimeUnit.MILLISECONDS
        );

        try {
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void placeTopBaseAsk(float solAmount, float price, boolean cancel) {
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
                        mmAccount.getPublicKey(),
                        List.of(MARKET_OOA),
                        market,
                        BASE_SPL_WALLET,
                        USDC_QUOTE_WALLET
                )
        );

        Order askOrder = Order.builder()
                .buy(false)
                .clientOrderId(ASK_CLIENT_ID)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY)
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.DECREMENT_TAKE)
                .floatPrice(price)
                .floatQuantity(solAmount)
                .build();

        serumManager.setOrderPrices(askOrder, market);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            market,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            ASK_CLIENT_ID
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        market,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        BASE_SPL_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        BASE_SPL_WALLET,
                        MARKET_OOA,
                        market,
                        askOrder
                )
        );

        placeTx.addInstruction(
                MemoProgram.writeUtf8(
                        mmAccount.getPublicKey(),
                        MEMO
                )
        );

        try {
            String orderTx = rpcClient.getApi().sendTransaction(placeTx, mmAccount);
            log.info(BASE_SYMBOL + " Ask: " + askOrder.getFloatQuantity() + " @ " + askOrder.getFloatPrice());
        } catch (RpcException e) {
            log.error("OrderTx Error = " + e.getMessage());
        }
    }

    private void placeLowerBaseAsk(float solAmount, float price, boolean cancel) {
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
                        mmAccount.getPublicKey(),
                        List.of(MARKET_OOA),
                        market,
                        BASE_SPL_WALLET,
                        USDC_QUOTE_WALLET
                )
        );

        Order askOrder = Order.builder()
                .buy(false)
                .clientOrderId(ASK_CLIENT_ID + 1)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY)
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.DECREMENT_TAKE)
                .floatPrice(price)
                .floatQuantity(solAmount)
                .build();

        serumManager.setOrderPrices(askOrder, market);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            market,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            ASK_CLIENT_ID + 1
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        market,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        BASE_SPL_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        BASE_SPL_WALLET,
                        MARKET_OOA,
                        market,
                        askOrder
                )
        );

        placeTx.addInstruction(
                MemoProgram.writeUtf8(
                        mmAccount.getPublicKey(),
                        MEMO
                )
        );

        try {
            String orderTx = rpcClient.getApi().sendTransaction(placeTx, mmAccount);
            log.info(BASE_SYMBOL + " Low Ask: " + askOrder.getFloatQuantity() + " @ " + askOrder.getFloatPrice());
        } catch (RpcException e) {
            log.error("OrderTx Error = " + e.getMessage());
        }
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
                        mmAccount.getPublicKey(),
                        List.of(MARKET_OOA),
                        market,
                        BASE_SPL_WALLET,
                        USDC_QUOTE_WALLET
                )
        );

        Order bidOrder = Order.builder()
                .buy(true)
                .clientOrderId(BID_CLIENT_ID)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY)
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.DECREMENT_TAKE)
                .floatPrice(price)
                .floatQuantity(amount)
                .build();

        serumManager.setOrderPrices(bidOrder, market);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            market,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            BID_CLIENT_ID
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        market,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        BASE_SPL_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        USDC_QUOTE_WALLET,
                        MARKET_OOA,
                        market,
                        bidOrder
                )
        );

        placeTx.addInstruction(
                MemoProgram.writeUtf8(
                        mmAccount.getPublicKey(),
                        MEMO
                )
        );

        try {
            String orderTx = rpcClient.getApi().sendTransaction(placeTx, mmAccount);
            log.info("USDC Bid: " + bidOrder.getFloatQuantity() + " @ " + bidOrder.getFloatPrice());
        } catch (RpcException e) {
            log.error("OrderTx Error = " + e.getMessage());
        }
    }

    private void placeUsdcSecondBid(float amount, float price, boolean cancel) {
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
                        mmAccount.getPublicKey(),
                        List.of(MARKET_OOA),
                        market,
                        BASE_SPL_WALLET,
                        USDC_QUOTE_WALLET
                )
        );

        Order bidOrder = Order.builder()
                .buy(true)
                .clientOrderId(BID_CLIENT_ID + 1)
                .orderTypeLayout(OrderTypeLayout.POST_ONLY)
                .selfTradeBehaviorLayout(SelfTradeBehaviorLayout.DECREMENT_TAKE)
                .floatPrice(price)
                .floatQuantity(amount)
                .build();

        serumManager.setOrderPrices(bidOrder, market);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            market,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            BID_CLIENT_ID + 1
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        market,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        BASE_SPL_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        USDC_QUOTE_WALLET,
                        MARKET_OOA,
                        market,
                        bidOrder
                )
        );

        placeTx.addInstruction(
                MemoProgram.writeUtf8(
                        mmAccount.getPublicKey(),
                        MEMO
                )
        );

        try {
            String orderTx = rpcClient.getApi().sendTransaction(placeTx, mmAccount);
            log.info("USDC Low Bid: " + bidOrder.getFloatQuantity() + " @ " + bidOrder.getFloatPrice());
        } catch (RpcException e) {
            log.error("OrderTx Error = " + e.getMessage());
        }
    }

    private Optional<Double> getUsdcBalance() {
        try {
            double amount = rpcClient.getApi().getTokenAccountBalance(
                            USDC_QUOTE_WALLET,
                            Commitment.PROCESSED
                    )
                    .getUiAmount();
            return Optional.of(amount);
        } catch (RpcException e) {
            log.error("Unable to get USDC balance: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> getBaseBalance() {
        try {
            double amount = rpcClient.getApi().getTokenAccountBalance(
                            BASE_SPL_WALLET,
                            Commitment.PROCESSED
                    )
                    .getUiAmount();
            return Optional.of(amount);
        } catch (RpcException e) {
            log.error("Unable to get Base balance: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Scheduled(initialDelay = 30_000L, fixedRate = 30_000L)
    public void updateLeanSizes() {
        USDC_BALANCE = getUsdcBalance();
        BASE_BALANCE = getBaseBalance();

        if (USDC_BALANCE.isPresent()) {
            double amount = USDC_BALANCE.get();
            if (amount <= USDC_THRESHOLD_TO_LEAN_WSOL) {
                double leanFactor = generateLeanFactor(BASE_SYMBOL);
                ASK_AMOUNT = BASE_QUOTE_SIZE * (float) leanFactor;

                // set last placed Ask to zero, so it re-quotes
                lastPlacedAskPrice = 0f;
            } else {
                ASK_AMOUNT = BASE_QUOTE_SIZE;
            }
        }

        if (BASE_BALANCE.isPresent()) {
            double amount = BASE_BALANCE.get();
            if (amount <= RLB_THRESHOLD_TO_LEAN_USDC) {
                double leanFactor = generateLeanFactor("USDC");
                USDC_BID_AMOUNT = BASE_QUOTE_SIZE * (float) leanFactor;

                // set last placed Bid to zero, so it re-quotes
                lastPlacedBidPrice = 0f;
            } else {
                USDC_BID_AMOUNT = BASE_QUOTE_SIZE;
            }
        }
    }

    //@Scheduled(fixedRate = 6_000L)
    public void jupiterPriceUpdateLoop() {
        int bonkUsdcValue = 300;

        Optional<Double> jupiterPrice = jupiterPricingSource.getUsdcPriceForSymbol(
                bonkMint,
                bonkUsdcValue
        );

        jupiterPrice.ifPresent(aDouble -> jupiterPricingSource.updatePriceMap(bonkMint, aDouble));
        jupiterPrice.ifPresent(aDouble -> log.info(BASE_SYMBOL + " Price: " + aDouble));
    }

}
