# Protocolo de teste: deteção de PIDs (ZS125GY-13-E55)

Objetivo: saber, sem confiar na lista que a centralina (ECU) anuncia, quais os
PIDs OBD (modo 01) que ela responde de facto, que bytes mexem em cada um, e que
outras mensagens CAN circulam no barramento (por exemplo entre a ECU e o
painel de origem). Em particular: descobrir de onde pode vir a velocidade.

## O que o firmware faz

O comando `SCAN` (por BLE) ou a opção de menuconfig
*PID scan (discovery test) → Start the PID scan at boot* arrancam o teste:

1. Pede **todos** os PIDs de 0x00 a 0xFF, um a um, mesmo os que a ECU diz não
   ter. Os que ficam calados são pedidos uma segunda vez.
2. Imprime um relatório completo (demora cerca de 1 a 2 minutos a chegar aqui).
3. Continua a pedir só os PIDs que responderam e regista o mínimo, o máximo e
   quantas vezes cada byte mudou. Imprime o relatório a cada 30 s.
4. Durante todo o teste, conta todas as outras mensagens CAN que passam no
   barramento (ID, frequência, e os mesmos mínimos/máximos por byte).

O painel continua a funcionar durante o teste (os pedidos são intercalados com
os normais) e mostra `SCAN` a ciano ao lado de "CYL HEAD TEMP".

Comandos BLE (app de terminal BLE, ligada ao dispositivo `OBDII`):

| Comando       | Efeito                                                   |
|---------------|----------------------------------------------------------|
| `SCAN`        | Começa um teste novo (apaga as contagens anteriores)     |
| `SCAN REPORT` | Imprime o relatório agora                                |
| `SCAN STOP`   | Para o teste e imprime o relatório final                 |

O relatório sai sempre no monitor série e, se o teste foi pedido por BLE,
também na app BLE.

## Preparação

- Gravar esta versão do firmware.
- Registar a saída de uma destas formas:
  - telemóvel com uma app de terminal BLE (por exemplo "Serial Bluetooth
    Terminal"), ligada a `OBDII`, com o registo para ficheiro ativo. Só cabe um
    cliente BLE de cada vez: fechar antes qualquer app ELM327;
  - ou portátil por USB: `idf.py monitor | tee scan.log`.
- Anotar a hora de início de cada teste e o que se fez.

## Testes

Cada teste começa com `SCAN` (contagens a zero) e acaba com `SCAN REPORT`.
Esperar pelo **primeiro relatório** antes de fazer a ação do teste, para o
varrimento completo já ter terminado.

**A. Ignição ligada, motor parado, mota parada (2 min).**
Mostra o que a ECU responde em repouso e o que anuncia mas não responde.

**B. Empurrar a mota com a ignição ligada e o motor parado.**
Em ponto morto, empurrar uns 10 m a passo, parar, repetir duas vezes.
Só a roda se mexe: o motor está parado. Qualquer byte que mude neste teste
(num PID ou numa mensagem CAN) é a velocidade ou um contador da roda. Se nada
mudar, a ECU não recebe o sinal da roda, e o painel lê-o diretamente do sensor.

**C. Motor ao ralenti, quente (2 min), com umas aceleradelas em ponto morto.**
Separa os bytes que seguem as rotações, o acelerador e a pressão de admissão.

**D. Andar (com segurança, num sítio calmo).**
- Velocidade constante, pelo velocímetro de origem: 30, 50 e 70 km/h,
  cerca de 20 s cada.
- Depois, embraiagem puxada, deixar a mota rolar de 50 até 20 km/h. Assim a
  velocidade muda enquanto as rotações ficam no ralenti, o que distingue a
  velocidade das rotações.
- No fim: `SCAN STOP`.

Enviar no fio do projeto os registos dos quatro testes, com as horas e as
velocidades anotadas.

## Como ler o relatório

```
==== PID SCAN REPORT  t=95 s  sweeps=2  watch=12  lost=0 ====
Bitmaps answered (2): 00 20
Answered (11): 01 04 05 06 07 0B 0C 0D 0E 0F 11
Claimed but never answered (4): 42 46 5C 5E
Answered but not claimed (0): -
Negative response (1): 22(NRC 12)
-- Answering PIDs: ECU, claimed, replies/asks, bytes now[min-max xchanges] --
PID 0C RPM     7E8 Y 12/12 B0 0B[0A-2C x9] B1 40[00-FC x11]  = 720 rpm
PID 0D SPEED   7E8 Y 12/12 B0 00[fixed]  = 0 km/h
-- Other CAN traffic: 3 IDs (not OBD replies) --
ID 120 n=9500 every 10 ms B0 10[fixed] B1 0A[00-FF x9499] ...
```

- **Answered**: PIDs que deram resposta real.
- **Claimed but never answered**: a ECU diz que tem, mas nunca responde.
- **Answered but not claimed**: responde, embora a ECU diga que não tem.
- **Negative response**: a ECU recusou o pedido (NRC 12 = PID não suportado).
- Linha `PID`: endereço da ECU que respondeu (7E8 é a ECU do motor; um 7E9 ou
  outro seria um segundo módulo), se estava anunciado (Y/N), respostas/pedidos,
  e para cada byte `valor_atual[mínimo-máximo xmudanças]`. `fixed` = nunca mudou.
  No fim, o valor descodificado pela norma, quando o PID é conhecido.
- Linha `ID`: mensagem CAN que não é resposta OBD: quantas, de quantos em
  quantos ms, e os bytes no mesmo formato.
- `lost`: mensagens que não couberam na fila (normalmente 0). Se for alto, as
  contagens de tráfego são aproximadas.

O que procurar:
- PID 0D com `= 0 km/h` e `fixed` no teste D: a ECU responde, mas não sabe a
  velocidade. Nesse caso a velocidade tem de vir do pino do sensor da roda no
  painel, ou de uma mensagem CAN do teste B/D.
- Um byte de uma linha `ID` que mude nos testes B e D, e fique parado no A e no
  C: é o candidato à velocidade.
