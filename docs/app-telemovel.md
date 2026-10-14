# App do telemóvel (Mota Dash)

A app liga-se sozinha à placa sempre que a ignição liga, envia o GPS do
telemóvel (a fonte `Vel_GPS_Ext`) e mostra o painel completo. O GPS só
está ligado enquanto a placa estiver ligada: fora da mota o gasto é quase zero.

## Instalar

1. No telemóvel, abrir
   <https://github.com/ArturPelicho/ESP32_S3_SN65HVD230_CAN_SNIFFER_DASHBOARD/releases/tag/app-latest>
   e tocar em `mota-dash.apk` (é o APK direto, sem zip).
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

- **Painel**: velocidade (roda > mudança > GPS), mudança, RPM com a zona de
  troca, temperatura da cabeça (mesmas cores do ecrã), consumo agora/médio,
  depósito, autonomia, bateria, acelerador. Roda o telemóvel para paisagem.
- **Controlo**: reset da média; abastecimento ("atestei" com os litros da
  bomba, ou somar litros, ou definir o nível); escolha da fonte de velocidade
  dos consumos; parâmetros da placa; reaprender mudanças.
- **Diagnóstico**: todas as fontes de velocidade lado a lado, frequência real
  do GPS, mudanças aprendidas, dados do motor e uma consola de comandos.

## Calibração com o depósito

Atesta, carrega **Registar** com "Atestei" ligado e os litros da bomba. No
atestar seguinte faz o mesmo: a placa compara o que estimou com os litros da
bomba e sugere um novo `FUEL_K`, que se aplica com um botão.

## Mudanças

Não há nada a fazer: numa volta normal, com o GPS ligado, a placa aprende a
relação de cada mudança (troços estáveis de ~2 s). O progresso aparece no
Painel e no Diagnóstico. Se mudares de pneu ou de cremalheira, **Reaprender**.
