package com.mmorrell.pricing;

import com.mmorrell.pyth.manager.PythManager;
import com.mmorrell.pyth.model.PriceDataAccount;
import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.PublicKey;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;


// Auto feed SOL price into a cache from Pyth
@Component
@Slf4j
public class PythPricingSource {

    private final PythManager pythManager;
    private final PublicKey solUsdPriceDataAccount = new PublicKey("H6ARHf6YXhGYeQfUzQNGk6rDNnLBQKrenN712K4AQJEG");
    private static Optional<Float> solPrice = Optional.empty();
    private static Optional<Float> solPriceConfidence = Optional.empty();

    public PythPricingSource(PythManager pythManager) {
        this.pythManager = pythManager;
    }

    @Scheduled(fixedRate = 190L)
    public void updateSolPriceCache() {
        final PriceDataAccount priceDataAccount = pythManager.getPriceDataAccount(solUsdPriceDataAccount);

        solPrice = Optional.of(priceDataAccount.getAggregatePriceInfo().getPrice());
        solPriceConfidence = Optional.of(priceDataAccount.getAggregatePriceInfo().getConfidence());
    }

    public double getSolBidPrice() {
        if (solPrice.isPresent() && solPriceConfidence.isPresent()) {
            return solPrice.get() - solPriceConfidence.get();
        } else {
            return 0.0;
        }
    }

    public double getSolAskPrice() {
        if (solPrice.isPresent() && solPriceConfidence.isPresent()) {
            return solPrice.get() + solPriceConfidence.get();
        } else {
            return 999999.9;
        }
    }

    public Optional<Float> getSolMidpointPrice() {
        return solPrice;
    }

    public Optional<Float> getSolPriceConfidence() {
        return solPriceConfidence;
    }

    public boolean hasSolPrice() {
        return solPrice.isPresent() && solPriceConfidence.isPresent();
    }

}
