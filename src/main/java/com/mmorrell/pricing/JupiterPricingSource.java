package com.mmorrell.pricing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class JupiterPricingSource {

    private final Map<String, Double> priceMap = new HashMap<>();
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public JupiterPricingSource(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    // https://price.jup.ag/v4/price?ids=ORCA&vsAmount=300
    // https://price.jup.ag/v4/price?ids=DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263&vsAmount=1500
    public Optional<Double> getUsdcPriceForSymbol(String symbol, long usdcAmount) {
        Request request = new Request.Builder()
                .url(String.format("https://price.jup.ag/v4/price?ids=%s&vsAmount=%d", symbol, usdcAmount))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String json = response.body().string();
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
            });

            double price = (double)((Map<?, ?>)((Map<?, ?>) map.get("data")).get(symbol)).get("price");
            return Optional.of(price);

        } catch (Exception e) {
            log.error("Error getting Jupiter price for " + symbol + ", " + e.getMessage());
            return Optional.empty();
        }
    }

    public void updatePriceMap(String symbol, double price) {
        priceMap.put(symbol, price);
    }

    public Optional<Double> getCachedPrice(String symbol) {
        return Optional.ofNullable(priceMap.get(symbol));
    }
}
