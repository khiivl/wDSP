wDSP — 1.1 "Bulldozer" (compilação 26)
Alterações desde a versão 0.4.9.8, a distribuição anterior de 13.09.2026
========================================================================

Este lançamento é a minha modificação do wDSP de Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, e inclui todo o seu trabalho até à sua versão 1.0.2.

INSTALAÇÃO

- Sobre uma versão anterior do mod: instale o APK por cima; as predefinições e configurações são
  mantidas.
- Sobre a versão do autor: ambas estão assinadas com chaves diferentes e o Android não instalará
  uma sobre a outra. Guarde as suas predefinições com a cópia de segurança da app, desinstale a
  versão do autor e instale o mod.
- Abra o wDSP uma vez após a instalação.

Os manuais (nove idiomas, incluindo o mapa de gestos do protetor de ecrã) estão no arquivo. Abrem
em qualquer navegador sem ligação à Internet.

-----------------------------------------------------------------------------
NOVIDADES
-----------------------------------------------------------------------------

A versão 1.0 do autor
- Ultra Bass, o novo Loudness, uma curva de analisador em tempo real por trás do equalizador,
  "Desl." na lista de subwoofer para carros sem ele, GALA desativável por predefinição.
- O separador "Teste de Áudio": sinais de teste e gravações de teste para cada coluna.

Interface
- Dois estilos de interface em Definições → "Estilo de interface": Clássico (o aspeto do autor) e
  Moderno. Temas diurno e noturno em ambos.
- Bloqueio de pares para atrasos: "Frontais juntos" e "Traseiros juntos" mantêm a diferença entre
  esquerda e direita.

Medição do habitáculo
- Definições → cartão do habitáculo: indique o tipo de habitáculo, as suas dimensões e toque nas
  colunas instaladas.
- A aplicação escolhe o volume de medição autonomamente.
- Após "Apply Auto-EQ", um breve varrimento de controlo indica se a correção funcionou.
- O relatório começa com uma lista de problemas em linguagem simples — por exemplo, uma coluna
  fraca, um tweeter com polaridade invertida, canais trocados, um subwoofer a tocar contra as portas
  (com indicação para alterar o interruptor de fase). Problemas que um equalizador não pode resolver
  são comunicados em vez de serem "corrigidos".

Espectro
- Um comutador no ecrã principal: "Desligado", "Música" (o que a predefinição faz da música),
  "Microphone" (como o habitáculo realmente soa).
- "Zero RTA" no Teste de Áudio calibra o espectro com uma única pressão.
- Aviso quando o microfone está sobrecarregado: reduza a sua sensibilidade nas definições do carro.

Retoma do leitor
- Definições: "Retomar leitor após reiniciar" e "Retomar leitor após suspensão". O leitor que estava
  a tocar regressa sozinho, sem que a sua janela salte sobre o navegador.
- Para a suspensão, prima "Permitir wDSP ativo em suspensão" e ative o wDSP na lista apresentada.

Protetor de ecrã
- Deslize a partir do centro do ecrã para ajustar o volume.
- Mantenha premido o botão de estilo durante um segundo para abrir o equalizador.
- Um toque na capa abre o leitor ou rádio que está efetivamente a tocar.
- Interruptor "Cobrir a barra de estado".

-----------------------------------------------------------------------------
CORRIGIDO
-----------------------------------------------------------------------------

- Chamadas: a predefinição já não salta durante uma chamada, o microfone é libertado de imediato e
  o volume após a chamada é restabelecido corretamente.
- Arranque: as configurações chegam com fiabilidade ao processador de som após ligar e após atualizações.
- O protetor de ecrã surge agora em equipamentos com "vídeo flutuante" ou "navegação flutuante" ativados,
  não cobre o navegador e funciona em ecrãs verticais.
- O ecrã do equalizador e as caixas de diálogo adaptam-se a ecrãs estreitos e verticais.
- Espectro do microfone após a suspensão e após utilização do microfone pelo assistente de voz.
- Vários erros na medição do habitáculo, especialmente nos graves profundos e com o subwoofer.
- O GALA já não deixa o volume aumentado após abrandar.
- Falha ao abrir a aplicação no separador "Curva F-M".

-----------------------------------------------------------------------------
POR FAVOR ENVIE
-----------------------------------------------------------------------------

A versão do autor não inclui medição do habitáculo, pelo que as medições vão para o grupo do mod.

1. Após uma medição do habitáculo: o arquivo de medição ("Guardar arquivo") e o relatório do
   sistema (Diagnóstico → "Relatório do sistema"), ambos em Download/wDSP, acompanhados de breves
   palavras sobre como mudou o som e o palco sonoro.
2. Se o protetor de ecrã nunca surgir: Diagnóstico → "Screen Topology" → guardar e enviar.
3. Se a retoma do leitor funcionou no seu caso, e com que leitor.

Grupo do mod: https://t.me/kostyamat_dev/92
Projeto original: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
AGRADECIMENTOS
-----------------------------------------------------------------------------

A Volodymyr Chebanenko (khiivl), autor do wDSP, pela aplicação sobre a qual este lançamento é construído.
A Mirek (Polónia) por testar o equalizador do autor em hardware real. Aos testadores que enviam
medições e relatórios.
