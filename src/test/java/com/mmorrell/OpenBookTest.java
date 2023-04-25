package com.mmorrell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmorrell.config.OpenBookConfig;
import com.mmorrell.pricing.JupiterPricingSource;
import com.mmorrell.pyth.manager.PythManager;
import com.mmorrell.pyth.model.PriceDataAccount;
import com.mmorrell.serum.model.Market;
import com.mmorrell.serum.model.MarketBuilder;
import com.mmorrell.serum.model.OpenOrdersAccount;
import com.mmorrell.serum.model.Order;
import com.mmorrell.serum.model.SerumUtils;
import com.mmorrell.serum.program.SerumProgram;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.ComputeBudgetProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.p2p.solanaj.rpc.Cluster;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.ConfirmedTransaction;
import org.p2p.solanaj.rpc.types.SignatureInformation;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.mmorrell.config.OpenBookConfig.PRIORITY_UNITS;
import static com.mmorrell.config.OpenBookConfig.SOL_USDC_MARKET_ID;
import static com.mmorrell.config.OpenBookConfig.solUsdcMarket;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class OpenBookTest {

    private static final RpcClient rpcClient = new RpcClient(Cluster.BLOCKDAEMON);

    @Test
    public void jupiterPricingTest() {
        log.info("Jupiter pricing test.");
        JupiterPricingSource jupiterPricingSource = new JupiterPricingSource(
                new OkHttpClient(),
                new ObjectMapper()
        );

        Optional<Double> price = jupiterPricingSource.getUsdcPriceForSymbol("ORCA", 1000);
        if (price.isPresent()) {
            log.info("ORCA Price ($100 worth): " + price);
            assertTrue(price.get() >= 0);
        } else {
            log.error("Unable to get ORCA price from Jupiter.");
        }
    }

    @Test
    public void eqTest() {
        Market mkt = new MarketBuilder()
                .setClient(rpcClient)
                .setPublicKey(SOL_USDC_MARKET_ID)
                .setRetrieveEventQueue(true)
                .setRetrieveOrderBooks(true)
                .build();
        int size = mkt.getEventQueue().getEvents().stream()
                .toList().size();
        log.info("EQ Size: " + size);
    }

    @Test
    public void hardCancelAndSettle() throws RpcException {
        // Load private key
        ClassPathResource resource = new ClassPathResource(
                "/mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json",
                SerumApplication.class
        );

        try (InputStream inputStream = resource.getInputStream()) {
            String privateKeyJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Account account = Account.fromJson(privateKeyJson);

            Account sessionWsolAccount = new Account();
            Transaction newTx = new Transaction();
            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            690_000
                    )
            );

            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            PRIORITY_UNITS * 4
                    )
            );

            // Create WSOL account for session. 0.5 to start
            newTx.addInstruction(
                    SystemProgram.createAccount(
                            account.getPublicKey(),
                            sessionWsolAccount.getPublicKey(),
                            (long) (0.03 * 1000000000.0) + 2039280, //.05 SOL
                            165,
                            TokenProgram.PROGRAM_ID
                    )
            );

            newTx.addInstruction(
                    TokenProgram.initializeAccount(
                            sessionWsolAccount.getPublicKey(),
                            SerumUtils.WRAPPED_SOL_MINT,
                            account.getPublicKey()
                    )
            );

            Market mkt = new MarketBuilder().setPublicKey(new PublicKey(
                            "8BnEgHoWFysVcuFFX7QztDmzuH8r5ZFvyP3sYwn1XTh6")).setRetrieveOrderBooks(true)
                    .setClient(rpcClient).build();

            newTx.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            mkt,
                            new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY"),
                            account.getPublicKey(),
                            14201L
                    )
            );

            newTx.addInstruction(
                    SerumProgram.settleFunds(
                            mkt,
                            new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY"),
                            account.getPublicKey(),
                            sessionWsolAccount.getPublicKey(), //random wsol acct for settles
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe")
                    )
            );

            newTx.addInstruction(TokenProgram.closeAccount(
                    sessionWsolAccount.getPublicKey(),
                    account.getPublicKey(),
                    account.getPublicKey()
            ));

            log.info("ASK cxl = " + rpcClient.getApi().sendTransaction(newTx, List.of(account,
                            sessionWsolAccount),
                    rpcClient.getApi().getRecentBlockhash(Commitment.PROCESSED)));

            ////////////////////////////// BID

            Account sessionWsolAccount2 = new Account();
            Transaction newTx2 = new Transaction();
            newTx2.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            890_000
                    )
            );

            newTx2.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            PRIORITY_UNITS
                    )
            );

            // Create WSOL account for session. 0.5 to start
            newTx2.addInstruction(
                    SystemProgram.createAccount(
                            account.getPublicKey(),
                            sessionWsolAccount2.getPublicKey(),
                            (long) (0.03 * 1000000000.0) + 2039280, //.05 SOL
                            165,
                            TokenProgram.PROGRAM_ID
                    )
            );

            newTx2.addInstruction(
                    TokenProgram.initializeAccount(
                            sessionWsolAccount2.getPublicKey(),
                            SerumUtils.WRAPPED_SOL_MINT,
                            account.getPublicKey()
                    )
            );


            newTx2.addInstruction(
                    SerumProgram.cancelOrderByClientId(
                            mkt,
                            new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY"),
                            account.getPublicKey(),
                            113371L
                    )
            );

            newTx2.addInstruction(
                    SerumProgram.settleFunds(
                            mkt,
                            new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY"),
                            account.getPublicKey(),
                            sessionWsolAccount2.getPublicKey(), //random wsol acct for settles
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe")
                    )
            );

            newTx2.addInstruction(TokenProgram.closeAccount(
                    sessionWsolAccount2.getPublicKey(),
                    account.getPublicKey(),
                    account.getPublicKey()
            ));

            log.info("BID cxl = " + rpcClient.getApi().sendTransaction(newTx2, List.of(account,
                            sessionWsolAccount2),
                    rpcClient.getApi().getRecentBlockhash(Commitment.PROCESSED)));


        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Test
    public void fishCheck() {
        Market mkt = new MarketBuilder()
                .setClient(rpcClient)
                .setPublicKey(OpenBookConfig.SOL_USDC_MARKET_ID)
                .setRetrieveOrderBooks(true)
                .build();


        Optional<Order> topOfBookFish = mkt.getAskOrderBook().getOrders().stream()
                .filter(order -> order.getOwner().equals(PublicKey.valueOf(
                        "6LqKv8iTZXi369pv7B9ZxAF56TC6oU9uFxWYsVdfLzMJ")))
                .min((o1, o2) -> Float.compare(o1.getFloatPrice(), o2.getFloatPrice()));

        topOfBookFish.ifPresent(order -> log.info("Top fish bid: " + topOfBookFish.get()));
        log.info("Best bid: " + mkt.getAskOrderBook().getBestAsk());

        // Avg fish ask between the next best ask.

    }

    @Test
    public void hardSettle() throws RpcException {
        // Load private key
        ClassPathResource resource = new ClassPathResource(
                "/mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json",
                SerumApplication.class
        );

        try (InputStream inputStream = resource.getInputStream()) {
            String privateKeyJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Account account = Account.fromJson(privateKeyJson);

            Account sessionWsolAccount = new Account();
            Transaction newTx = new Transaction();
            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            1000_000
                    )
            );

            newTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            PRIORITY_UNITS
                    )
            );

            // Create WSOL account for session. 0.5 to start
            newTx.addInstruction(
                    SystemProgram.createAccount(
                            account.getPublicKey(),
                            sessionWsolAccount.getPublicKey(),
                            (long) (0.04 * 1000000000.0) + 2039280, //.05 SOL
                            165,
                            TokenProgram.PROGRAM_ID
                    )
            );

            newTx.addInstruction(
                    TokenProgram.initializeAccount(
                            sessionWsolAccount.getPublicKey(),
                            SerumUtils.WRAPPED_SOL_MINT,
                            account.getPublicKey()
                    )
            );


            Market mkt = new MarketBuilder().setPublicKey(new PublicKey(
                            "8BnEgHoWFysVcuFFX7QztDmzuH8r5ZFvyP3sYwn1XTh6")).setRetrieveOrderBooks(true)
                    .setClient(rpcClient).build();

            newTx.addInstruction(
                    SerumProgram.settleFunds(
                            mkt,
                            new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY"),
                            account.getPublicKey(),
                            sessionWsolAccount.getPublicKey(), //random wsol acct for settles
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe")
                    )
            );


            newTx.addInstruction(TokenProgram.closeAccount(
                    sessionWsolAccount.getPublicKey(),
                    account.getPublicKey(),
                    account.getPublicKey()
            ));

            log.info("hardSettle cxl = " + rpcClient.getApi().sendTransaction(newTx, List.of(account,
                            sessionWsolAccount),
                    rpcClient.getApi().getRecentBlockhash()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Test
    public void dataTest() throws RpcException {
        // Calculate average lamports per TX from last 100 TX
        List<SignatureInformation> signatureInformations = rpcClient.getApi().getSignaturesForAddress(
                PublicKey.valueOf("mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN"),
                200,
                Commitment.CONFIRMED
        );

        for (SignatureInformation signatureInformation : signatureInformations) {
            // Get TXID
            String txId = signatureInformation.getSignature();
            ConfirmedTransaction tx = rpcClient.getApi().getTransaction(txId, Commitment.CONFIRMED);
            //tx.
        }
    }

    @Test
    public void getSingleTxTest() throws RpcException {
        ConfirmedTransaction tx = rpcClient.getApi().getTransaction(
                "26DsRzShP4nu8HBhuCVTZvS2dbm5fqvbNfPRRjxPGnvz2tE11hrLePiNj4kDffemsbgjHY5Ehthr3Je3VhJRKMri",
                Commitment.CONFIRMED);

        log.info(tx.toString());
        log.info("Pre: " + tx.getMeta().getPreBalances().get(0).toString());
        log.info("Post: " + tx.getMeta().getPostBalances().get(0).toString());
    }

    @Test
    public void pythPriceTest() {
        PythManager pythManager = new PythManager(rpcClient);
        PublicKey solUsdPriceDataAccount = new PublicKey("H6ARHf6YXhGYeQfUzQNGk6rDNnLBQKrenN712K4AQJEG");
        final PriceDataAccount priceDataAccount = pythManager.getPriceDataAccount(solUsdPriceDataAccount);

        log.info("SOL/USD price: " + priceDataAccount.getAggregatePriceInfo().getPrice());
        log.info("SOL/USD price: " + priceDataAccount.getAggregatePriceInfo().getConfidence());
    }

    @Test
    public void ooaOwnerLookupTest() throws RpcException {
        byte[] data = rpcClient.getApi().getAccountInfo(PublicKey.valueOf(
                "AuhHEQCENAZBjojbT7US6B8MXHG8WknLeS7iddQJEcyF")).getDecodedData();

        OpenOrdersAccount ooa = OpenOrdersAccount.readOpenOrdersAccount(data);
        log.info(ooa.getOwner().toBase58());
    }

}
