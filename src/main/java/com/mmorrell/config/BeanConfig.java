package com.mmorrell.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmorrell.pyth.manager.PythManager;
import com.mmorrell.serum.manager.SerumManager;
import okhttp3.OkHttpClient;
import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@PropertySource("classpath:openbook.properties")
@EnableScheduling
@EnableAsync
public class BeanConfig {

    public static final String MEMO = "mmorrell.com / @skynetcap";

    @Value("${solana.rpc.url}")
    public String RPC_URL;

    @Value("${solana.data.rpc.url}")
    public String DATA_RPC_URL;

    @Bean
    public RpcClient rpcClient() {
        int readTimeoutMs = 1050;
        int connectTimeoutMs = 470;
        int writeTimeoutMs = 955;
        return new RpcClient(
                RPC_URL,
                readTimeoutMs,
                connectTimeoutMs,
                writeTimeoutMs
        );
    }

    @Bean(name = "data")
    public RpcClient dataRpcClient() {
        int readTimeoutMs = 1050;
        int connectTimeoutMs = 470;
        int writeTimeoutMs = 955;
        return new RpcClient(
                DATA_RPC_URL,
                readTimeoutMs,
                connectTimeoutMs,
                writeTimeoutMs
        );
    }

    @Bean
    public SerumManager serumManager() {
        return new SerumManager(rpcClient());
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public PythManager pythManager() {
        return new PythManager(dataRpcClient());
    }

}
