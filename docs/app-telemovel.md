# App do telemóvel (Mota Dash)

A app liga-se sozinha à placa sempre que a ignição liga, envia o GPS do
telemóvel (a fonte `Vel_GPS_Ext`) e mostra o painel completo. O GPS só
está ligado enquanto a placa estiver ligada: fora da mota o gasto é quase zero.

## Instalar

1. No telemóvel, abrir
   <https://github.com/ArturPelicho/ESP32_S3_SN65HVD230_CAN_SNIFFER_DASHBOARD/releases/latest>
   e tocar em `mota-dash-v0.1.N-xxxxxxx.apk` (é o APK direto, sem zip; o nome traz a versão).
2. Abrir o ficheiro descarregado e instalar (permitir "instalar apps
   desconhecidas" para o browser ou para o gestor de ficheiros).
3. Se o Xiaomi disser "A app não foi instalada": não instalar a partir de
   dentro de um zip; desligar por momentos a verificação de segurança do
   gestor de ficheiros / Play Protect para esta instalação.
4. As versões seguintes instalam-se por cima (mesma chave de assinatura).

## Primeira vez

1. Abrir a app e aceitar Bluetooth, localização e notificações.
2. Separador **Controlo** → *Ligação e telemóvel*:
   - **Permitir sempre** a localização (sem isto não arranca sozinha depois
     de reiniciar o telemóvel);
   - **Desligar otimização** de bateria.
3. **Xiaomi / Redmi (HyperOS/MIUI)**: em *Definições da app* ligar
   **Arranque automático** e pôr a bateria em **Sem restrições**. Sem isto o
   sistema mata a app em segundo plano.
4. **Motorola**: chega o passo 2.
5. Ligar a ignição com a app aberta: procura `OBDII`, guarda a placa e daí em
   diante liga-se sozinha.

## Separadores

- **Mapa**: mapa offline que segue a mota (ver abaixo).
- **Painel**: luzes de troca, velocidade (roda > mudança > GPS), mudança, RPM,
  depósito e temperatura da cabeça em barras de segmentos, consumo agora e
  médio, autonomia, hora, bateria, ar de admissão, acelerador, viagem, e um
  aviso a piscar quando a centralina tem erros.
- **Controlo**: reset da média; abastecimento ("atestei" com os litros da
  bomba, ou somar litros, ou definir o nível); escolha da fonte de velocidade
  dos consumos; parâmetros da placa em unidades normais (depósito em litros,
  consumo da autonomia em L/100 km); orientação do ecrã; reaprender mudanças.
- **Erros**: luz de avaria, erros memorizados, pendentes e permanentes (lidos
  sozinhos ao ligar) e o botão para os apagar (ignição ligada, motor parado).
- **Diagnóstico**: todas as fontes de velocidade lado a lado, frequência real
  do GPS, mudanças aprendidas, dados do motor e uma consola de comandos.

## Orientação

Em **Controlo > Ecrã**: automática, vertical, horizontal, horizontal invertida
(a do DMD2 com o telemóvel rodado para a direita) ou horizontal nas duas.

## Autonomia

A autonomia usa um consumo que mistura o valor guardado (`BASE_L100`) com a
média desde o abastecimento: nos primeiros km quase só o valor guardado, depois
de umas centenas de km quase só a média real. A cada abastecimento, se andaste
30 km ou mais, esse consumo passa a ser o novo valor guardado. Não é preciso
mexer, mas podes acertá-lo à mão.

## Calibração com o depósito

Atesta, carrega **Registar** com "Atestei" ligado e os litros da bomba. No
atestar seguinte faz o mesmo: a placa compara o que estimou com os litros da
bomba e sugere um novo `FUEL_K`, que se aplica com um botão.

## Mudanças

Não há nada a fazer: numa volta normal, com o GPS ligado, a placa aprende a
relação de cada mudança (troços estáveis de ~2 s). O progresso aparece no
Painel e no Diagnóstico. Se mudares de pneu ou de cremalheira, **Reaprender**.

## Mapa

- **Mapas offline**: ☰ → *Mapas offline*. Descarrega cada região com Wi-Fi
  (Portugal ~616 MB, relevo 88 MB, limites 8 MB). Cada região pode ser
  desligada sem a apagar. "Procurar atualizações" mostra quando há mapas novos
  (refeitos todos os meses com dados novos do OpenStreetMap). Se já tens o
  mapa de antes dos limites, "Só limites e radares" descarrega só esses.
- **Botões à direita**: modo (▲ direção para cima, 3D, N norte para cima),
  ◎ voltar a seguir a mota, tema AUTO/DIA/NOITE, ☰ mapas e opções.
  À esquerda + e − por cima da velocidade.
- **Zoom automático** pela velocidade (perto na cidade, longe na estrada) e
  inclinação automática em 3D. Pinça ou +/− mandam no zoom 10 s; arrastar o
  mapa deixa de seguir, e volta a seguir sozinho a andar.
- **Parado**, a seta e o mapa rodam com a bússola do telemóvel.
- **Velocidade** fica a 0 parado. Ao lado aparece o sinal do limite da estrada
  (fica vermelha acima do limite) e em cima um aviso RADAR com a distância,
  com bip (desliga-se em ☰). Limites e radares vêm do OpenStreetMap:
  os sinais na estrada mandam sempre.
- **GPS**: toca em "GPS" no canto para ver satélites, precisão e altitude.
- **Trajetos** (botão GPX em cima à direita): abre GPX, KML, KMZ, GeoJSON,
  TCX e FIT, ou usa "Abrir com"/"Partilhar" noutra app. Em cada trajeto:
  mostrar, cor, espessura, nome (toca nele), ver no mapa, apagar e
  **Seguir** (setas no sentido da viagem, "Faltam X km" e aviso laranja
  quando sais do trajeto, com sentido inverso opcional). Ficheiro de teste:
  [samples/gpx/teste-lisboa-marginal.gpx](../samples/gpx/teste-lisboa-marginal.gpx).
- **Tamanho da interface**: Controlo → 85 % a 150 %.
