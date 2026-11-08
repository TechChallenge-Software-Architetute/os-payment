# language: pt
Funcionalidade: Polling de pagamentos pendentes
  Cenário: Marcar como pago quando o Mercado Pago aprova o pagamento
    Dado que existe checkout gerado para o pedido "order-123"
    E o Mercado Pago retorna o pagamento "mp-789" com status "approved", valor "125.50" e moeda "BRL"
    Quando os pagamentos pendentes são consultados
    Então a ordem deve ser salva com status "ORDER_PAID"
    E o status "ORDER_PAID" deve ser publicado

  Cenário: Manter aguardando quando o pagamento ainda está pendente
    Dado que existe checkout gerado para o pedido "order-123"
    E o Mercado Pago retorna o pagamento "mp-789" com status "pending", valor "125.50" e moeda "BRL"
    Quando os pagamentos pendentes são consultados
    Então a ordem deve permanecer com status "ORDER_GENERATED"
    E nenhum status deve ser publicado

  Cenário: Marcar como erro quando o pagamento é recusado
    Dado que existe checkout gerado para o pedido "order-123"
    E o Mercado Pago retorna o pagamento "mp-789" com status "rejected", valor "125.50" e moeda "BRL"
    Quando os pagamentos pendentes são consultados
    Então a ordem deve ser salva com status "ORDER_PAYMENT_ERROR"
    E o status "ORDER_PAYMENT_ERROR" deve ser publicado

  Cenário: Não aprovar pagamento com valor divergente
    Dado que existe checkout gerado para o pedido "order-123"
    E o Mercado Pago retorna o pagamento "mp-789" com status "approved", valor "120.00" e moeda "BRL"
    Quando os pagamentos pendentes são consultados
    Então a ordem deve ser salva com status "ORDER_PAYMENT_ERROR"
    E o status "ORDER_PAYMENT_ERROR" deve ser publicado
