# language: pt
Funcionalidade: Geração do checkout para um pedido
  Cenário: Publicar checkout criado pelo Mercado Pago
    Dado que não existe pagamento para o pedido "order-123"
    E o pedido tem email "cliente@example.com", total "125.50" e moeda "BRL"
    E o Mercado Pago cria o checkout "https://mp.example/checkout/abc" com identificador "pref-abc"
    Quando o pedido é processado
    Então a ordem deve ser salva com status "ORDER_GENERATED"
    E o checkout "https://mp.example/checkout/abc" deve ser publicado para a ordem

  Cenário: Não gerar checkout novamente para pedido já processado
    Dado que já existe pagamento gerado para o pedido "order-123"
    Quando o pedido é processado novamente
    Então a criação do checkout não deve ser chamada
    E nenhum status deve ser publicado

  Cenário: Registrar e publicar erro ao falhar na criação do checkout
    Dado que não existe pagamento para o pedido "order-123"
    E o pedido tem email "cliente@example.com", total "125.50" e moeda "BRL"
    E o Mercado Pago falha ao criar o checkout
    Quando o pedido é processado
    Então a ordem deve ser salva com status "ORDER_PAYMENT_ERROR"
    E um erro de pagamento deve ser publicado
