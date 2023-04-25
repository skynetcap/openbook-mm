package com.mmorrell.config;

import com.mmorrell.serum.model.Market;
import com.mmorrell.serum.model.MarketBuilder;
import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;

import java.util.List;
import java.util.Optional;

@Slf4j
public class OpenBookConfig {
    public static final PublicKey SOL_USDC_MARKET_ID
            = new PublicKey("8BnEgHoWFysVcuFFX7QztDmzuH8r5ZFvyP3sYwn1XTh6");
    public static final PublicKey SOL_USDC_OOA
            = new PublicKey("1rAS3fWujnbcLZ7hNuMJbu2nFEubHyevKzYUfbPVLPY");
    public static final PublicKey USDC_QUOTE_WALLET
            = new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe");

    // JitoSol
    public static final PublicKey JITOSOL_USDC_MARKET_ID
            = new PublicKey("JAmhJbmBzLp2aTp9mNJodPsTcpCJsmq5jpr6CuCbWHvR");
    public static final PublicKey JITOSOL_USDC_OOA
            = new PublicKey("9YMZ97VzSpwdTrr8JxG55D1LwHYqjz6Z4PrsgYpd8i2x");
    public static final PublicKey JITOSOL_BASE_WALLET
            = new PublicKey("45RRcrU7JMBQug4Gy2G7bapZVqfkeBgjERoMFL16vd6x");

    // stSOL
    public static final PublicKey STSOL_USDC_MARKET_ID
            = new PublicKey("JCKa72xFYGWBEVJZ7AKZ2ofugWPBfrrouQviaGaohi3R");
    public static final PublicKey STSOL_USDC_OOA
            = new PublicKey("997oiwbj29k5xDDVn9H5h1QmA6zsQ7UB1jqrCaLBqNEY");
    public static final PublicKey STSOL_BASE_WALLET
            = new PublicKey("CEGu1nP3t2BStW5e71YnvjZRN1oMstJxgtm4PXNa8KGj");

    // Parameters
    public static final float INITIAL_CAPITAL = 40f;  // minus ~1k from actual capital, since some SOL at start
    // higher max target units longer afk. 28 good for an active session (1.5 hrs), probably 30 longer.
    public static final float TARGET_MAX_UNITS = 5f; // 13 units = 19000/ 24/13 (sol) = 60 SOL/bet. more units better

    // Finals - Sensitive
    public static final float START_SOL_PRICE = 22f; // on the conservative estimate, 26 is only a fallback
    public static final float DEFAULT_BID_SPREAD_MULTIPLIER = 0.99884f;
    public static final float DEFAULT_ASK_SPREAD_MULTIPLIER = 0.99897f;
    public static final float CROSS_DETECTION_PADDING = 0.00005f; // since gte or lte is too precise
    public static final float MIN_MIDPOINT_CHANGE = 0.00015f; //0.00035f possible new value
    public static final float PYTH_PREDICTIVE_FACTOR_BIDS = 0.00043f; // how many bps of pyth predictiveness
    public static final float PYTH_PREDICTIVE_FACTOR = 0.00046f; // how many bps of pyth predictiveness
    public static final float PYTH_CONFIDENCE_INTERVAL_CONFIDENCE = 0.999f;
    public static final float ALLOWED_PRICING_BPS_MISMATCH = 0.00002f; // widen to lower quote rate: 0.000052f;
    public static final int EVENT_QUEUE_SIZE_THRESHOLD_FOR_WIDEN = 430;
    public static final float EVENT_QUEUE_SIZE_ASK_WIDEN = 1.0003f;
    public static final int EVENT_LOOP_DURATION_MS = 165;
    public static final long ORDER_BOOK_POLL_INTERVAL = 210L;
    public static final long LEAN_CALCULATION_INITIAL_DELAY = 1000L;
    public static final long LEAN_CALCULATION_INTERVAL = 9_000L;
    public static final long BID_CLIENT_ID = 113371L;
    public static final long ASK_CLIENT_ID = 14201L;
    public static final int EVENT_LOOP_INITIAL_DELAY_MS = 0;
    public static float BID_SPREAD_MULTIPLIER = DEFAULT_BID_SPREAD_MULTIPLIER;
    public static float ASK_SPREAD_MULTIPLIER = DEFAULT_ASK_SPREAD_MULTIPLIER;
    public static boolean GIGA_LEANING = false;
    public static boolean IS_WSOL_LEANING = false; // Used when pricing against ASX and we have giga inventory
    public static float SOL_QUOTE_SIZE = Math.round(INITIAL_CAPITAL / START_SOL_PRICE / TARGET_MAX_UNITS) / 2f;
    public static float USDC_BID_AMOUNT_IN_WSOL = SOL_QUOTE_SIZE / 2;
    public static float SOL_ASK_AMOUNT = SOL_QUOTE_SIZE * .80f;
    public static final double WSOL_STARTING_AMOUNT = (SOL_QUOTE_SIZE * 1.02) + 2; // 55
    public static final double WSOL_THRESHOLD_TO_LEAN_USDC = WSOL_STARTING_AMOUNT * 1.2;
    public static final double USDC_THRESHOLD_TO_LEAN_WSOL = INITIAL_CAPITAL / 3.0;
    public static int PRIORITY_MICRO_LAMPORTS_DEFAULT = 101_420; //Rate
    public static final int NEW_ORDER_DELAY_DURATION_SECONDS = 16; // DEJ sniped me when this was at 14
    public static final int PRIORITY_UNITS = 54_800; // Limit
    public static final double ADVERSITY_BASE_PRIORITY_RATE_TO_ADD = 11_500.0;
    public static final double ADVERSITY_BASE_REDUCTION = 55_000.0;
    public static int PRIORITY_MICRO_LAMPORTS = PRIORITY_MICRO_LAMPORTS_DEFAULT + 12_000; // Rate
    public static float BID_API_TUNING_FACTOR = 1f;
    public static float ASK_API_TUNING_FACTOR = 1f;
    public static Optional<Double> USDC_BALANCE = Optional.empty();
    public static Optional<Double> WSOL_BALANCE = Optional.empty();
    public static PublicKey WSOL_BASE_WALLET;
    public static Account mmAccount;
    public static Market solUsdcMarket;
    public static MarketBuilder solUsdcMarketBuilder;
    public static Market jitoSolUsdcMarket;
    public static MarketBuilder jitoSolUsdcMarketBuilder;
    public static Market stSolUsdcMarket;
    public static MarketBuilder stSolUsdcMarketBuilder;
    public static final PublicKey SPACE_MONKEY = new PublicKey("5Di65JsuLU7n8RLZBPhWwHyxVTHM1feLXZnX6VjGpG7S");
    public static final PublicKey JUMP_TRADING = new PublicKey("D8nvp2VbmnMjk7pgAjvHbmwKG5ZDmGAJpUcgr4ia95s9");
    public static final List<PublicKey> KNOWN_SHARPS = List.of(
            SPACE_MONKEY,
            JUMP_TRADING,
            PublicKey.valueOf("CYmcDS6vgNKYaVkEq7Y7T9C8Nbc417ypKiqbxW8jpU9e"), //A6T drift/zeta/ob MEV\
            PublicKey.valueOf("4gGTcGVSbbE4djDwgx8akJrMHjLY7VXuRTnug9UWPXEa")  //DSA MEV BOT
    );
    public static final List<PublicKey> KNOWN_FISH = List.of(
            // PublicKey.valueOf("6LqKv8iTZXi369pv7B9ZxAF56TC6oU9uFxWYsVdfLzMJ"), //SoL
            PublicKey.valueOf("D2ibD81iWxrwDYhKq6ZRDrWFLM675MH8mzY5uPUT5FoS"), //tu4
            PublicKey.valueOf("7pYyuhKrMTswQqZ9eXx813Qsx99yzvnbaUD3mUvX7wFm")  //ground
    );

    public static double generateLeanFactor(String token) {
        double leanFactor = 1.1;  // closer to 1 for longer afk
        log.info("Leaning " + token + ": " + leanFactor + "x");
        return leanFactor;
    }

    public static void setPriorityMicroLamports(int rate) {
        PRIORITY_MICRO_LAMPORTS = rate;
    }

    public static int getPriorityMicroLamports() {
        return PRIORITY_MICRO_LAMPORTS;
    }

    public static void useDefaultPriorityMicroLamports() {
        PRIORITY_MICRO_LAMPORTS = PRIORITY_MICRO_LAMPORTS_DEFAULT;
    }

    public static void setPriorityMicroLamportsDefault(int rate) {
        PRIORITY_MICRO_LAMPORTS_DEFAULT = rate;
    }

    public static void widenBids() {
        BID_API_TUNING_FACTOR -= 0.0001f;
    }

    public static void tightenBids() {
        BID_API_TUNING_FACTOR += 0.0001f;
    }

    public static void tightenBidsHalf() {
        BID_API_TUNING_FACTOR += 0.00005f;
    }

    public static void widenAsks() {
        ASK_API_TUNING_FACTOR += 0.0001f;
    }

    public static void tightenAsks() {
        ASK_API_TUNING_FACTOR -= 0.0001f;
    }

    public static void setQuoteSize(float newSize) {
        SOL_QUOTE_SIZE = newSize;
    }

    public static void tightenAsksHalf() {
        ASK_API_TUNING_FACTOR -= 0.00005f;
    }

    public static void resetBids() {
        BID_API_TUNING_FACTOR = 1;
    }

    public static void resetAsks() {
        ASK_API_TUNING_FACTOR = 1;
    }

}
