# Wondertravel Passagens

Monitor gratuito em desenvolvimento para encontrar emissões Smiles abaixo de 31.000 milhas por passageiro e trecho.

## Busca-alvo

- 2 adultos e 2 crianças de 2 a 11 anos, sempre consultados juntos.
- Ida: GRU, CGH ou VCP para BPS em 9, 10 ou 11/10/2026.
- Volta: BPS para GRU, CGH ou VCP em 17 ou 18/10/2026.
- Tarifa-alvo: Clube Smiles, somente milhas, abaixo de 31.000 por pessoa.
- Meta total: menos de 124.000 milhas por sentido, mais taxas.

## Situação atual

A primeira etapa é uma prova de conceito manual para GRU → BPS em 09/10/2026. Ela abre a busca pública da Smiles em um navegador automatizado e guarda evidências por sete dias no GitHub Actions.

Os alertas e o agendamento horário permanecem desativados até uma execução real confirmar:

1. Que a Smiles permite a consulta no ambiente gratuito do GitHub.
2. Que o valor capturado é por passageiro.
3. Que a tarifa é do Clube Smiles e não Smiles & Money.
4. Que existem quatro assentos no mesmo preço.

Nenhuma senha ou acesso à conta Smiles é usado nesta etapa.

## Executar a prova

No GitHub, abra **Actions → Testar consulta Smiles → Run workflow**. Ao terminar, abra a execução e baixe o artefato **smiles-probe**.
