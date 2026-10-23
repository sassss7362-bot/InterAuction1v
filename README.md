# InterAuction (Paper 1.21.x, Java 21)
Окремий плагін-аукціон. Збірка: mvn clean package -> target/InterAuction.jar -> plugins/

Команди:
  /ah                 - аукціон
  /ah sell <ціна>     - виставити предмет з руки (500, 10к, 1.5м)
  /ah search <текст>  - пошук
  /ah my              - мої лоти / повернення
  /ah balance         - баланс

Гроші: InterCasino (монети) -> Vault -> внутрішній баланс. Налаштування: config.yml
