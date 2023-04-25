package com.mmorrell.strategies.openbook.eth;

import com.mmorrell.SerumApplication;
import com.mmorrell.serum.manager.SerumManager;
import com.mmorrell.serum.model.Market;
import com.mmorrell.serum.model.MarketBuilder;
import com.mmorrell.serum.model.Order;
import com.mmorrell.serum.model.OrderTypeLayout;
import com.mmorrell.serum.model.SelfTradeBehaviorLayout;
import com.mmorrell.serum.program.SerumProgram;
import com.mmorrell.strategies.Strategy;
import lombok.Getter;
import lombok.Setter;
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
@Setter
public class OpenBookEthUsdcJump extends Strategy {

    private static final int EVENT_LOOP_INITIAL_DELAY_MS = 0;
    private static final int EVENT_LOOP_DURATION_MS = 5000;

    private final RpcClient rpcClient;
    private final SerumManager serumManager;
    private final ScheduledExecutorService executorService;

    // Dynamic
    private double bestBidPrice;
    private double bestAskPrice;

    // Finals
    private final Account mmAccount;
    private final Market solUsdcMarket;
    private final MarketBuilder solUsdcMarketBuilder;
    public static final PublicKey MARKET_ID =
            new PublicKey("FZxi3yWkE5mMjyaZj6utmYL54QQYfMCKMcLaQZq4UwnA");

    private static final PublicKey MARKET_OOA = new PublicKey("ByHALKtEGFr96YFomG9jgpkDpCrGM1Jn7h9enzBQDYJx");
    private static final PublicKey ETH_BASE_WALLET = new PublicKey("9mKDakPNoJR2SD9q8pFEwTj5L2CDWx2PCNZwHuaqsaek");
    private static final PublicKey USDC_QUOTE_WALLET = new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe");
    private static final long BID_CLIENT_ID = 113371L;
    private static final long ASK_CLIENT_ID = 14201L;

    private static final float BASE_QUOTE_SIZE = 0.085f;
    private static float ASK_AMOUNT = BASE_QUOTE_SIZE;
    private static float USDC_BID_AMOUNT = BASE_QUOTE_SIZE;
    private static final float ASK_SPREAD_MULTIPLIER = 1.0012f;
    private static final float BID_SPREAD_MULTIPLIER = 0.9988f;
    private static final float MIN_MIDPOINT_CHANGE = 0.0010f;

    private float lastPlacedBidPrice = 0.0f, lastPlacedAskPrice = 0.0f;

    // Leaning
    private static final double ETH_THRESHOLD_TO_LEAN_USDC = BASE_QUOTE_SIZE * 4;
    private static Optional<Double> USDC_BALANCE = Optional.empty();
    private static Optional<Double> ETH_BALANCE = Optional.empty();

    private Market marketPricingSource;

    // Used to delay 2000ms on first order place.
    private static boolean firstLoadComplete = false;

    public OpenBookEthUsdcJump(final SerumManager serumManager,
                               final RpcClient rpcClient) {
        this.executorService = Executors.newSingleThreadScheduledExecutor();

        this.serumManager = serumManager;
        this.rpcClient = rpcClient;

        this.solUsdcMarketBuilder = new MarketBuilder()
                .setClient(rpcClient)
                .setPublicKey(MARKET_ID)
                .setRetrieveOrderBooks(true);
        this.solUsdcMarket = this.solUsdcMarketBuilder.build();
        this.bestBidPrice = this.solUsdcMarket.getBidOrderBook().getBestBid().getFloatPrice();
        this.bestAskPrice = this.solUsdcMarket.getAskOrderBook().getBestAsk().getFloatPrice();

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
                        solUsdcMarket.reload(solUsdcMarketBuilder);

                        Order bestBid, bestAsk;
                        if (marketPricingSource != null) {
                            bestBid = marketPricingSource.getBidOrderBook().getBestBid();
                            bestAsk = marketPricingSource.getAskOrderBook().getBestAsk();
                        } else {
                            bestBid = solUsdcMarket.getBidOrderBook().getBestBid();
                            bestAsk = solUsdcMarket.getAskOrderBook().getBestAsk();
                        }

                        this.bestBidPrice = bestBid.getFloatPrice();
                        this.bestAskPrice = bestAsk.getFloatPrice();

                        boolean isCancelBid =
                                solUsdcMarket.getBidOrderBook().getOrders().stream().anyMatch(order -> order.getOwner().equals(MARKET_OOA));

                        float percentageChangeFromLastBid =
                                1.00f - (lastPlacedBidPrice / ((float) bestBidPrice * BID_SPREAD_MULTIPLIER));

                        // Only place bid if we haven't placed, or the change is >= 0.1% change
                        if (lastPlacedBidPrice == 0 || (Math.abs(percentageChangeFromLastBid) >= MIN_MIDPOINT_CHANGE)) {
                            placeUsdcBid(USDC_BID_AMOUNT, (float) bestBidPrice * BID_SPREAD_MULTIPLIER, isCancelBid);
                            lastPlacedBidPrice = (float) bestBidPrice * BID_SPREAD_MULTIPLIER;
                        }

                        boolean isCancelAsk =
                                solUsdcMarket.getAskOrderBook().getOrders().stream().anyMatch(order -> order.getOwner().equals(MARKET_OOA));

                        float percentageChangeFromLastAsk =
                                1.00f - (lastPlacedAskPrice / ((float) bestAskPrice * ASK_SPREAD_MULTIPLIER));

                        // Only place ask if we haven't placed, or the change is >= 0.1% change
                        if (lastPlacedAskPrice == 0 || (Math.abs(percentageChangeFromLastAsk) >= MIN_MIDPOINT_CHANGE)) {
                            placeEthAsk(ASK_AMOUNT, (float) bestAskPrice * ASK_SPREAD_MULTIPLIER, isCancelAsk);
                            lastPlacedAskPrice = (float) bestAskPrice * ASK_SPREAD_MULTIPLIER;
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
                    } catch (Exception ex) {
                        log.error("Unhandled exception during event loop: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                },
                EVENT_LOOP_INITIAL_DELAY_MS,
                EVENT_LOOP_DURATION_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void placeEthAsk(float solAmount, float price, boolean cancel) {
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
                        solUsdcMarket,
                        ETH_BASE_WALLET,
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

        serumManager.setOrderPrices(askOrder, solUsdcMarket);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            ASK_CLIENT_ID
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        ETH_BASE_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        ETH_BASE_WALLET,
                        MARKET_OOA,
                        solUsdcMarket,
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
            log.info("ETH Ask: " + askOrder.getFloatQuantity() + " @ " + askOrder.getFloatPrice());
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
                        solUsdcMarket,
                        ETH_BASE_WALLET,
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

        serumManager.setOrderPrices(bidOrder, solUsdcMarket);

        if (cancel) {
            placeTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            solUsdcMarket,
                            MARKET_OOA,
                            mmAccount.getPublicKey(),
                            BID_CLIENT_ID
                    )
            );
        }


        // Settle - base wallet gets created first then closed after
        placeTx.addInstruction(
                SerumProgram.settleFunds(
                        solUsdcMarket,
                        MARKET_OOA,
                        mmAccount.getPublicKey(),
                        ETH_BASE_WALLET, //random wsol acct for settles
                        USDC_QUOTE_WALLET
                )
        );

        placeTx.addInstruction(
                SerumProgram.placeOrder(
                        mmAccount,
                        USDC_QUOTE_WALLET,
                        MARKET_OOA,
                        solUsdcMarket,
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
                            ETH_BASE_WALLET,
                            Commitment.PROCESSED
                    )
                    .getUiAmount();
            return Optional.of(amount);
        } catch (RpcException e) {
            log.error("Unable to get Base balance: " + e.getMessage());
            return Optional.empty();
        }
    }

    //@Scheduled(initialDelay = 30_000L, fixedRate = 30_000L)
    public void updateLeanSizes() {
        // Lean WSOL is USDC balance is low.
        USDC_BALANCE = getUsdcBalance();
        ETH_BALANCE = getBaseBalance();

        if (USDC_BALANCE.isPresent()) {
            double amount = USDC_BALANCE.get();
            if (amount <= USDC_THRESHOLD_TO_LEAN_WSOL) {
                double leanFactor = generateLeanFactor("ETH");
                ASK_AMOUNT = BASE_QUOTE_SIZE * (float) leanFactor;

                // set last placed Ask to zero, so it re-quotes
                lastPlacedAskPrice = 0f;
            } else {
                ASK_AMOUNT = BASE_QUOTE_SIZE;
            }
        }

        // Lean USDC is WSOL balance is low.
        if (ETH_BALANCE.isPresent()) {
            double amount = ETH_BALANCE.get();
            if (amount <= ETH_THRESHOLD_TO_LEAN_USDC) {
                double leanFactor = generateLeanFactor("USDC");
                USDC_BID_AMOUNT = BASE_QUOTE_SIZE * (float) leanFactor;

                // set last placed Bid to zero, so it re-quotes
                lastPlacedBidPrice = 0f;
            } else {
                USDC_BID_AMOUNT = BASE_QUOTE_SIZE;
            }
        }
    }

}
