# Open Book Market Maker
An HFT market making client for OpenBook on Solana.

- Installation Guide: [SETUP.md](SETUP.md)

## Adding New Strategy

- Create class MyNewStrategy `extends Strategy`.
- Create bean of MyNewStrategy using `@Component` annotation, or in a config.
- Override `void start()` from the Strategy interface with business logic.
- Wire `MyNewStrategy` bean into `StrategyManager` constructor.
- Add `myNewStrategy.start()` call inside of `StrategyManager.strategyStartup()`.